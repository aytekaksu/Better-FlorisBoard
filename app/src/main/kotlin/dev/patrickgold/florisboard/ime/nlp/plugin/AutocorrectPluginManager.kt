/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.florisboard.autocorrect.api.AutocorrectCandidate
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.autocorrect.api.AutocorrectRequest
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.api.candidateEventBundle
import org.florisboard.autocorrect.api.candidatesFromBundle
import org.florisboard.autocorrect.api.finishSessionBundle
import org.florisboard.autocorrect.api.removalRequestBundle
import org.florisboard.autocorrect.api.removalResultFromBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class AutocorrectPluginDescriptor(
    val componentName: ComponentName,
    val label: String,
    val settingsActivity: ComponentName?,
) {
    val id: String
        get() = componentName.flattenToString()
}

data class AutocorrectPluginSuggestions(
    val sessionId: Long,
    val candidates: List<AutocorrectCandidate>,
) {
    companion object {
        val Empty = AutocorrectPluginSuggestions(-1L, emptyList())
    }
}

/**
 * Discovers and talks to a user-selected external autocorrect service.
 *
 * This manager never starts a service. It binds for an active typing session and unbinds as soon as
 * input finishes, which leaves Android in full control of the provider process lifetime.
 */
class AutocorrectPluginManager(context: Context) {
    companion object {
        private const val CONNECTION_TIMEOUT_MS = 350L
        private const val RESPONSE_TIMEOUT_MS = 500L
    }

    private val appContext by context.appContext()
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextId = AtomicLong(1L)
    private val pendingSuggestions = ConcurrentHashMap<Long, CompletableDeferred<List<AutocorrectCandidate>>>()
    private val pendingRemovals = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val _providers = MutableStateFlow<List<AutocorrectPluginDescriptor>>(emptyList())
    private val replyMessenger = Messenger(ReplyHandler())

    val providers = _providers.asStateFlow()

    @Volatile private var remote: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var activeSession: AutocorrectSession? = null
    @Volatile private var connectionReady = CompletableDeferred<Messenger?>()
    @Volatile private var latestSuggestionRequestId = -1L
    private var serviceConnection: ServiceConnection? = null

    fun refreshProviders() {
        scope.launch {
            _providers.value = runCatching { discoverProviders() }.getOrDefault(emptyList())
        }
    }

    fun hasSelectedProvider(): Boolean {
        return prefs.suggestion.autocorrectPluginComponent.get().isNotBlank()
    }

    @Synchronized
    fun startSession(
        subtype: Subtype,
        editorInfo: FlorisEditorInfo,
        isPrivateSession: Boolean,
    ) {
        finishSession()
        if (
            !prefs.suggestion.enabled.get() ||
            isPrivateSession ||
            editorInfo.isRawInputEditor ||
            editorInfo.inputAttributes.isPassword ||
            editorInfo.inputAttributes.flagTextNoSuggestions
        ) {
            return
        }
        val selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        if (selectedProviderId.isBlank()) return
        val session = AutocorrectSession(
            sessionId = nextId.getAndIncrement(),
            primaryLanguageTag = subtype.primaryLocale.localeTag(),
            secondaryLanguageTags = subtype.secondaryLocales.map { it.localeTag() },
            inputType = editorInfo.inputAttributes.raw,
            capsMode = editorInfo.initialCapsMode.toInt(),
        )
        activeSession = session
        connectionReady = CompletableDeferred()
        scope.launch {
            val discoveredProviders = providers.value.ifEmpty {
                runCatching { discoverProviders() }.getOrDefault(emptyList()).also {
                    _providers.value = it
                }
            }
            val descriptor = discoveredProviders.firstOrNull { it.id == selectedProviderId }
            if (descriptor == null) {
                if (activeSession?.sessionId == session.sessionId) {
                    unbind()
                }
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                if (activeSession?.sessionId == session.sessionId) {
                    bind(descriptor)
                }
            }
        }
    }

    @Synchronized
    fun finishSession() {
        val session = activeSession
        if (session != null) {
            send(
                what = AutocorrectPluginContract.MSG_FINISH_SESSION,
                data = finishSessionBundle(session.sessionId),
            )
        }
        activeSession = null
        latestSuggestionRequestId = -1L
        cancelPending()
        unbind()
    }

    suspend fun suggest(
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): AutocorrectPluginSuggestions {
        val session = activeSession ?: return AutocorrectPluginSuggestions.Empty
        if (!prefs.suggestion.enabled.get()) {
            finishSession()
            return AutocorrectPluginSuggestions.Empty
        }
        if (isPrivateSession) {
            finishSession()
            return AutocorrectPluginSuggestions.Empty
        }
        if (!content.localSelection.isCursorMode) return AutocorrectPluginSuggestions.Empty
        val service = remote ?: withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            connectionReady.await()
        } ?: return AutocorrectPluginSuggestions.Empty

        val (requestId, deferred) = synchronized(this) {
            if (activeSession?.sessionId != session.sessionId) {
                return AutocorrectPluginSuggestions.Empty
            }
            val requestId = nextId.getAndIncrement()
            val previousRequestId = latestSuggestionRequestId
            latestSuggestionRequestId = requestId
            if (previousRequestId >= 0) {
                pendingSuggestions.remove(previousRequestId)?.cancel()
                send(AutocorrectPluginContract.MSG_CANCEL, Bundle(), service)
            }
            val request = AutocorrectRequest(
                sessionId = session.sessionId,
                requestId = requestId,
                text = content.text,
                selectionStart = content.localSelection.start,
                selectionEnd = content.localSelection.end,
                composingStart = content.localComposing.start,
                composingEnd = content.localComposing.end,
                currentWordStart = content.localCurrentWord.start,
                currentWordEnd = content.localCurrentWord.end,
                maxCandidateCount = maxCandidateCount,
                allowPossiblyOffensive = allowPossiblyOffensive,
            )
            val deferred = CompletableDeferred<List<AutocorrectCandidate>>()
            pendingSuggestions[requestId] = deferred
            if (!send(AutocorrectPluginContract.MSG_SUGGEST, request.toBundle(), service)) {
                pendingSuggestions.remove(requestId)
                return AutocorrectPluginSuggestions.Empty
            }
            requestId to deferred
        }
        val result = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            deferred.await()
        }
        if (result == null && pendingSuggestions.remove(requestId, deferred)) {
            synchronized(this) {
                if (
                    activeSession?.sessionId == session.sessionId &&
                    requestId == latestSuggestionRequestId
                ) {
                    send(AutocorrectPluginContract.MSG_CANCEL, Bundle(), service)
                }
            }
        }
        return AutocorrectPluginSuggestions(
            sessionId = session.sessionId,
            candidates = result.orEmpty().take(
                maxCandidateCount.coerceIn(1, AutocorrectPluginContract.MAX_CANDIDATES),
            ),
        )
    }

    fun notifyAccepted(sessionId: Long, candidateId: String) {
        notifyCandidateEvent(AutocorrectPluginContract.MSG_ACCEPTED, sessionId, candidateId)
    }

    fun notifyReverted(sessionId: Long, candidateId: String) {
        notifyCandidateEvent(AutocorrectPluginContract.MSG_REVERTED, sessionId, candidateId)
    }

    suspend fun removeSuggestion(sessionId: Long, candidateId: String): Boolean {
        val (requestId, deferred) = synchronized(this) {
            val session = activeSession?.takeIf { it.sessionId == sessionId } ?: return false
            val service = remote ?: return false
            val requestId = nextId.getAndIncrement()
            val deferred = CompletableDeferred<Boolean>()
            pendingRemovals[requestId] = deferred
            if (!send(
                    AutocorrectPluginContract.MSG_REMOVE,
                    removalRequestBundle(session.sessionId, requestId, candidateId),
                    service,
                )
            ) {
                pendingRemovals.remove(requestId)
                return false
            }
            requestId to deferred
        }
        val removed = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            deferred.await()
        } ?: false
        pendingRemovals.remove(requestId, deferred)
        return removed
    }

    @Synchronized
    private fun notifyCandidateEvent(what: Int, sessionId: Long, candidateId: String) {
        if (activeSession?.sessionId != sessionId) return
        send(what, candidateEventBundle(sessionId, candidateId))
    }

    private fun createServiceConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            attach(this, binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            detach(this)
        }

        override fun onBindingDied(name: ComponentName) {
            detach(this)
        }

        override fun onNullBinding(name: ComponentName) {
            detach(this)
        }
    }

    @Synchronized
    private fun attach(connection: ServiceConnection, binder: IBinder) {
        if (serviceConnection !== connection) return
        val service = Messenger(binder)
        remote = service
        connectionReady.complete(service)
        activeSession?.let { session ->
            send(AutocorrectPluginContract.MSG_START_SESSION, session.toBundle(), service)
        } ?: unbind()
    }

    @Synchronized
    private fun detach(connection: ServiceConnection) {
        if (serviceConnection === connection) unbind()
    }

    @Synchronized
    private fun bind(descriptor: AutocorrectPluginDescriptor) {
        if (activeSession == null) return
        val connection = createServiceConnection()
        serviceConnection = connection
        val intent = Intent(AutocorrectPluginContract.ACTION_BIND_PROVIDER)
            .setComponent(descriptor.componentName)
        bound = runCatching {
            appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND,
            )
        }.getOrDefault(false)
        if (!bound) {
            unbind()
        }
    }

    private fun send(what: Int, data: Bundle, service: Messenger? = remote): Boolean {
        service ?: return false
        return try {
            service.send(Message.obtain(null, what).apply {
                this.data = data
                replyTo = replyMessenger
            })
            true
        } catch (_: RemoteException) {
            detach(service)
            false
        }
    }

    @Synchronized
    private fun detach(service: Messenger) {
        if (remote === service) unbind()
    }

    @Synchronized
    private fun unbind() {
        val connection = serviceConnection
        if (bound && connection != null) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        serviceConnection = null
        clearConnection()
    }

    private fun clearConnection() {
        remote = null
        activeSession = null
        connectionReady.complete(null)
        cancelPending()
    }

    private fun cancelPending() {
        pendingSuggestions.values.forEach { it.cancel() }
        pendingSuggestions.clear()
        pendingRemovals.values.forEach { it.cancel() }
        pendingRemovals.clear()
    }

    private suspend fun discoverProviders(): List<AutocorrectPluginDescriptor> = withContext(Dispatchers.IO) {
        val packageManager = appContext.packageManager
        val intent = Intent(AutocorrectPluginContract.ACTION_BIND_PROVIDER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }
        resolveInfos.mapNotNull { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
            val protocolVersion = serviceInfo.metaData?.getInt(
                AutocorrectPluginContract.META_PROTOCOL_VERSION,
                0,
            ) ?: 0
            if (!serviceInfo.exported || protocolVersion != AutocorrectPluginContract.PROTOCOL_VERSION) {
                return@mapNotNull null
            }
            val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
            val settingsName = serviceInfo.metaData
                ?.getString(AutocorrectPluginContract.META_SETTINGS_ACTIVITY)
            AutocorrectPluginDescriptor(
                componentName = component,
                label = resolveInfo.loadLabel(packageManager).toString(),
                settingsActivity = settingsName?.let { name ->
                    ComponentName(
                        serviceInfo.packageName,
                        if (name.startsWith(".")) serviceInfo.packageName + name else name,
                    )
                },
            )
        }.sortedBy { it.label.lowercase() }
    }

    private inner class ReplyHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                AutocorrectPluginContract.MSG_SUGGESTIONS -> {
                    val (requestId, candidates) = candidatesFromBundle(message.data)
                    val pending = pendingSuggestions.remove(requestId) ?: return
                    if (requestId == latestSuggestionRequestId) {
                        pending.complete(candidates)
                    } else {
                        pending.cancel()
                    }
                }
                AutocorrectPluginContract.MSG_REMOVE_RESULT -> {
                    val (requestId, removed) = removalResultFromBundle(message.data)
                    pendingRemovals.remove(requestId)?.complete(removed)
                }
                else -> super.handleMessage(message)
            }
        }
    }
}
