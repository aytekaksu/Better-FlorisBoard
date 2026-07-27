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
import android.view.accessibility.AccessibilityManager
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidateKind
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.nlp.SuggestionSeparatorBehavior
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.florisboard.autocorrect.api.AutocorrectAcceptanceKind
import org.florisboard.autocorrect.api.AutocorrectCandidate
import org.florisboard.autocorrect.api.AutocorrectCandidateKind
import org.florisboard.autocorrect.api.AutocorrectInputTrace
import org.florisboard.autocorrect.api.AutocorrectInputMode
import org.florisboard.autocorrect.api.AutocorrectKeyGeometry
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectRequest
import org.florisboard.autocorrect.api.AutocorrectSeparatorBehavior
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.api.AutocorrectSuggestionResult
import org.florisboard.autocorrect.api.AutocorrectTouchPoint
import org.florisboard.autocorrect.api.AutocorrectTextEvent
import org.florisboard.autocorrect.api.AutocorrectTextEventKind
import org.florisboard.autocorrect.api.candidateEventBundle
import org.florisboard.autocorrect.api.finishSessionBundle
import org.florisboard.autocorrect.api.pluginUiMutationBundle
import org.florisboard.autocorrect.api.pluginUiRequestBundle
import org.florisboard.autocorrect.api.pluginUiResultFromBundle
import org.florisboard.autocorrect.api.removalRequestBundle
import org.florisboard.autocorrect.api.removalResultFromBundle
import org.florisboard.autocorrect.api.suggestionResultFromBundle
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

/**
 * Discovers and talks to a user-selected external autocorrect service.
 *
 * This manager never starts a service. It binds for an active typing session and unbinds as soon as
 * input finishes, which leaves Android in full control of the provider process lifetime.
 */
class AutocorrectPluginManager(context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.external-autocorrect"
        private const val CONNECTION_TIMEOUT_MS = 350L
        private const val RESPONSE_TIMEOUT_MS = 500L
        private const val UI_RESPONSE_TIMEOUT_MS = 1_500L
    }

    private val appContext by context.appContext()
    private val editorInstance by context.editorInstance()
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextId = AtomicLong(1L)
    private val pendingSuggestions =
        ConcurrentHashMap<Long, CompletableDeferred<AutocorrectSuggestionResult>>()
    private val pendingRemovals = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val _providers = MutableStateFlow<List<AutocorrectPluginDescriptor>>(emptyList())
    private val _pluginUi = MutableStateFlow<AutocorrectPluginUi?>(null)
    private val _pluginUiLoading = MutableStateFlow(false)
    private val _keyboardUiVisible = MutableStateFlow(false)
    @Volatile private var replyMessenger = Messenger(ReplyHandler(""))

    val providers = _providers.asStateFlow()
    val pluginUi = _pluginUi.asStateFlow()
    val pluginUiLoading = _pluginUiLoading.asStateFlow()
    val keyboardUiVisible = _keyboardUiVisible.asStateFlow()

    @Volatile private var remote: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var activeSession: AutocorrectSession? = null
    @Volatile private var connectionReady = CompletableDeferred<Messenger?>()
    @Volatile private var latestSuggestionRequestId = -1L
    @Volatile private var latestPluginUiRequestId = -1L
    @Volatile private var activeProviderId = ""
    @Volatile private var boundProviderId = ""
    @Volatile private var providerQueryComplete = false
    @Volatile private var boostedCodePoints = emptySet<Int>()
    @Volatile private var uiClientCount = 0
    private var serviceConnection: ServiceConnection? = null
    private var traceKeyboard: TextKeyboard? = null
    private var traceWidth = 0f
    private var traceHeight = 0f
    private var traceKeys = emptyList<AutocorrectKeyGeometry>()
    private val tracePoints = mutableListOf<AutocorrectTouchPoint>()

    override val providerId = ProviderId

    fun refreshProviders() {
        scope.launch {
            _providers.value = runCatching { discoverProviders() }.getOrDefault(emptyList())
            providerQueryComplete = true
        }
    }

    @Synchronized
    fun acquirePluginUi() {
        uiClientCount++
        if (uiClientCount == 1) {
            _pluginUiLoading.value = true
            connectPluginUi()
        }
    }

    @Synchronized
    fun releasePluginUi() {
        uiClientCount = (uiClientCount - 1).coerceAtLeast(0)
        if (uiClientCount == 0) {
            send(AutocorrectPluginContract.MSG_PLUGIN_UI_CLOSED, Bundle())
            _pluginUi.value = null
            _pluginUiLoading.value = false
            if (activeSession == null) unbind(clearSession = false)
        }
    }

    fun showKeyboardPluginUi() {
        if (_keyboardUiVisible.compareAndSet(expect = false, update = true)) {
            acquirePluginUi()
        }
    }

    fun hideKeyboardPluginUi() {
        if (_keyboardUiVisible.compareAndSet(expect = true, update = false)) {
            releasePluginUi()
        }
    }

    fun setPluginUiValue(itemId: String, value: String) {
        sendPluginUiMessage(
            AutocorrectPluginContract.MSG_SET_PLUGIN_UI_VALUE,
            itemId,
            value,
        )
    }

    fun invokePluginUiAction(itemId: String) {
        sendPluginUiMessage(
            AutocorrectPluginContract.MSG_INVOKE_PLUGIN_UI_ACTION,
            itemId,
        )
    }

    @Synchronized
    fun notifyTextEvent(text: String, kind: AutocorrectTextEventKind) {
        val session = activeSession ?: return
        if (!session.allowPersonalizedLearning) return
        val event = AutocorrectTextEvent(session.sessionId, text, kind)
        if (
            event.text.isNotBlank() ||
            kind == AutocorrectTextEventKind.DELETE_BACKWARD ||
            kind == AutocorrectTextEventKind.DELETE_FORWARD
        ) {
            send(AutocorrectPluginContract.MSG_TEXT_EVENT, event.toBundle())
        }
    }

    fun selectedProvider() = providers.value.firstOrNull {
        it.id == prefs.suggestion.autocorrectPluginComponent.get()
    }

    fun boostedCodePoints(): Set<Int> {
        if (prefs.suggestion.autocorrectPluginComponent.get().isBlank()) return emptySet()
        val accessibility = appContext.getSystemService(AccessibilityManager::class.java)
        return boostedCodePoints.takeUnless { accessibility?.isEnabled == true }.orEmpty()
    }

    private fun sendPluginUiMessage(what: Int, itemId: String, value: String? = null) {
        val requestId = nextId.getAndIncrement()
        latestPluginUiRequestId = requestId
        _pluginUiLoading.value = true
        if (!send(what, pluginUiMutationBundle(requestId, itemId, value))) {
            _pluginUiLoading.value = false
        } else {
            expirePluginUiRequest(requestId)
        }
    }

    private fun requestPluginUi(service: Messenger? = remote) {
        val requestId = nextId.getAndIncrement()
        latestPluginUiRequestId = requestId
        _pluginUiLoading.value = true
        if (!send(
                AutocorrectPluginContract.MSG_GET_PLUGIN_UI,
                pluginUiRequestBundle(requestId),
                service,
            )
        ) {
            _pluginUiLoading.value = false
        } else {
            expirePluginUiRequest(requestId)
        }
    }

    private fun expirePluginUiRequest(requestId: Long) {
        scope.launch {
            delay(UI_RESPONSE_TIMEOUT_MS)
            if (latestPluginUiRequestId == requestId) {
                _pluginUiLoading.value = false
            }
        }
    }

    private fun connectPluginUi() {
        val selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        if (selectedProviderId.isBlank()) {
            _pluginUiLoading.value = false
            return
        }
        scope.launch {
            val discoveredProviders = if (providerQueryComplete) {
                providers.value
            } else {
                runCatching { discoverProviders() }.getOrDefault(emptyList()).also {
                    _providers.value = it
                    providerQueryComplete = true
                }
            }
            val descriptor = discoveredProviders.firstOrNull { it.id == selectedProviderId }
            withContext(Dispatchers.Main.immediate) {
                synchronized(this@AutocorrectPluginManager) {
                    if (uiClientCount == 0) return@synchronized
                    if (descriptor == null) {
                        _pluginUiLoading.value = false
                    } else if (remote != null && boundProviderId == descriptor.id) {
                        requestPluginUi()
                    } else {
                        if (activeProviderId.isNotBlank() && activeProviderId != descriptor.id) {
                            endSession()
                        }
                        if (boundProviderId.isNotBlank()) unbind(clearSession = false)
                        connectionReady = CompletableDeferred()
                        bind(descriptor)
                    }
                }
            }
        }
    }

    @Synchronized
    fun recordInputTouch(
        data: KeyData,
        keyboard: TextKeyboard,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        isPrivateSession: Boolean,
    ) {
        if (
            prefs.suggestion.autocorrectPluginComponent.get().isBlank() ||
            isPrivateSession ||
            data.type != KeyType.CHARACTER ||
            width <= 0f ||
            height <= 0f
        ) {
            return
        }
        val text = data.asString(isForDisplay = false).takeIf { it.isNotBlank() } ?: return
        if (traceKeyboard !== keyboard || traceWidth != width || traceHeight != height) {
            traceKeyboard = keyboard
            traceWidth = width
            traceHeight = height
            traceKeys = keyboard.keys().asSequence().mapNotNull { key ->
                val keyData = key.computedData
                if (keyData.type != KeyType.CHARACTER) return@mapNotNull null
                AutocorrectKeyGeometry(
                    text = keyData.asString(isForDisplay = false),
                    left = key.touchBounds.left / width,
                    top = key.touchBounds.top / height,
                    right = key.touchBounds.right / width,
                    bottom = key.touchBounds.bottom / height,
                )
            }.take(AutocorrectPluginContract.MAX_TRACE_KEY_COUNT).toList()
        }
        if (tracePoints.size < AutocorrectPluginContract.MAX_TRACE_POINT_COUNT) {
            tracePoints.add(AutocorrectTouchPoint(text, x / width, y / height))
        }
    }

    @Synchronized
    fun clearInputTrace() {
        tracePoints.clear()
        boostedCodePoints = emptySet()
    }

    @Synchronized
    private fun inputTraceFor(content: EditorContent): AutocorrectInputTrace {
        val points = tracePoints.toList()
        return if (points.joinToString(separator = "", transform = AutocorrectTouchPoint::text) ==
            content.currentWordText
        ) {
            AutocorrectInputTrace(traceKeys, points)
        } else {
            AutocorrectInputTrace.Empty
        }
    }

    override suspend fun create() = Unit

    override suspend fun preload(subtype: Subtype) = Unit

    @Synchronized
    private fun ensureSession(
        subtype: Subtype,
        editorInfo: FlorisEditorInfo,
        isPrivateSession: Boolean,
    ): AutocorrectSession? {
        val selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        if (
            !prefs.suggestion.enabled.get() ||
            selectedProviderId.isBlank() ||
            isPrivateSession ||
            editorInfo.isRawInputEditor ||
            editorInfo.inputAttributes.isPassword ||
            editorInfo.inputAttributes.flagTextNoSuggestions
        ) {
            finishSession()
            return null
        }
        val secondaryLanguageTags = subtype.secondaryLocales.map { it.localeTag() }
        activeSession?.takeIf { session ->
            activeProviderId == selectedProviderId &&
                session.primaryLanguageTag == subtype.primaryLocale.localeTag() &&
                session.secondaryLanguageTags == secondaryLanguageTags &&
                session.inputType == editorInfo.inputAttributes.raw &&
                session.capsMode == editorInfo.initialCapsMode.toInt()
        }?.let { return it }
        if (providerQueryComplete && providers.value.none { it.id == selectedProviderId }) {
            finishSession()
            return null
        }
        finishSession()
        val session = AutocorrectSession(
            sessionId = nextId.getAndIncrement(),
            primaryLanguageTag = subtype.primaryLocale.localeTag(),
            secondaryLanguageTags = secondaryLanguageTags,
            inputType = editorInfo.inputAttributes.raw,
            capsMode = editorInfo.initialCapsMode.toInt(),
            allowPersonalizedLearning = !editorInfo.imeOptions.flagNoPersonalizedLearning,
        )
        activeSession = session
        activeProviderId = selectedProviderId
        connectionReady = CompletableDeferred<Messenger?>().also { ready ->
            remote?.takeIf { boundProviderId == selectedProviderId }?.let(ready::complete)
        }
        scope.launch {
            val discoveredProviders = if (providerQueryComplete) {
                providers.value
            } else {
                runCatching { discoverProviders() }.getOrDefault(emptyList()).also {
                    _providers.value = it
                    providerQueryComplete = true
                }
            }
            val descriptor = discoveredProviders.firstOrNull { it.id == selectedProviderId }
            if (descriptor == null) {
                if (activeSession?.sessionId == session.sessionId) {
                    unbind(clearSession = false)
                }
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                if (activeSession?.sessionId == session.sessionId) {
                    if (remote != null && boundProviderId == descriptor.id) {
                        send(
                            AutocorrectPluginContract.MSG_START_SESSION,
                            session.toBundle(),
                        )
                    } else {
                        bind(descriptor)
                    }
                }
            }
        }
        return session
    }

    @Synchronized
    fun finishSession() {
        val hadSession = endSession()
        if (uiClientCount == 0) {
            unbind(clearSession = false)
        } else if (hadSession && remote != null) {
            requestPluginUi()
        }
    }

    private fun endSession(): Boolean {
        val session = activeSession
        if (session != null) {
            send(
                what = AutocorrectPluginContract.MSG_FINISH_SESSION,
                data = finishSessionBundle(session.sessionId),
            )
        }
        activeSession = null
        activeProviderId = ""
        latestSuggestionRequestId = -1L
        clearInputTrace()
        cancelPending()
        return session != null
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        if (!content.localSelection.isCursorMode) return emptyList()
        val session = ensureSession(subtype, editorInstance.activeInfo, isPrivateSession)
            ?: return emptyList()
        return requestCandidates(
            session = session,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            inputTrace = inputTraceFor(content),
        ).map { it.toSuggestionCandidate(session.sessionId, content) }
    }

    suspend fun suggestGesture(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
        inputTrace: AutocorrectInputTrace,
    ): List<SuggestionCandidate> {
        if (
            !content.localSelection.isCursorMode ||
            inputTrace.mode != AutocorrectInputMode.GESTURE ||
            inputTrace.gesturePoints.size < 2
        ) {
            return emptyList()
        }
        val session = ensureSession(subtype, editorInstance.activeInfo, isPrivateSession)
            ?: return emptyList()
        return requestCandidates(
            session = session,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            inputTrace = inputTrace,
        ).map { it.toSuggestionCandidate(session.sessionId, content) }
    }

    private suspend fun requestCandidates(
        session: AutocorrectSession,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        inputTrace: AutocorrectInputTrace,
    ): List<AutocorrectCandidate> {
        val service = remote ?: withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            connectionReady.await()
        } ?: return emptyList()

        val (requestId, deferred) = synchronized(this) {
            if (activeSession?.sessionId != session.sessionId) {
                return emptyList()
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
                inputTrace = inputTrace,
            )
            val deferred = CompletableDeferred<AutocorrectSuggestionResult>()
            pendingSuggestions[requestId] = deferred
            if (!send(AutocorrectPluginContract.MSG_SUGGEST, request.toBundle(), service)) {
                pendingSuggestions.remove(requestId)
                return emptyList()
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
                    boostedCodePoints = emptySet()
                    send(AutocorrectPluginContract.MSG_CANCEL, Bundle(), service)
                }
            }
        }
        return result?.candidates.orEmpty().take(
            maxCandidateCount.coerceIn(1, AutocorrectPluginContract.MAX_CANDIDATES),
        )
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        notifySuggestionAccepted(candidate, AutocorrectAcceptanceKind.MANUAL)
    }

    fun notifySuggestionAccepted(
        candidate: SuggestionCandidate,
        acceptanceKind: AutocorrectAcceptanceKind,
    ) {
        if (candidate is ExternalAutocorrectCandidate) {
            notifyCandidateEvent(
                AutocorrectPluginContract.MSG_ACCEPTED,
                candidate.pluginSessionId,
                candidate.pluginCandidateId,
                acceptanceKind,
            )
        }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        if (candidate is ExternalAutocorrectCandidate) {
            notifyCandidateEvent(
                AutocorrectPluginContract.MSG_REVERTED,
                candidate.pluginSessionId,
                candidate.pluginCandidateId,
            )
        }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        if (candidate !is ExternalAutocorrectCandidate) return false
        val (requestId, deferred) = synchronized(this) {
            val session = activeSession?.takeIf {
                it.sessionId == candidate.pluginSessionId
            } ?: return false
            val service = remote ?: return false
            val requestId = nextId.getAndIncrement()
            val deferred = CompletableDeferred<Boolean>()
            pendingRemovals[requestId] = deferred
            if (!send(
                    AutocorrectPluginContract.MSG_REMOVE,
                    removalRequestBundle(session.sessionId, requestId, candidate.pluginCandidateId),
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

    override suspend fun getListOfWords(subtype: Subtype): List<String> = emptyList()

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double = 0.0

    override suspend fun destroy() {
        _keyboardUiVisible.value = false
        uiClientCount = 0
        finishSession()
    }

    @Synchronized
    private fun notifyCandidateEvent(
        what: Int,
        sessionId: Long,
        candidateId: String,
        acceptanceKind: AutocorrectAcceptanceKind? = null,
    ) {
        if (activeSession?.sessionId != sessionId) return
        send(what, candidateEventBundle(sessionId, candidateId, acceptanceKind))
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
        activeSession?.takeIf { activeProviderId == boundProviderId }?.let { session ->
            send(AutocorrectPluginContract.MSG_START_SESSION, session.toBundle(), service)
        }
        if (uiClientCount > 0) {
            requestPluginUi(service)
        } else if (activeSession == null) {
            unbind(clearSession = false)
        }
    }

    @Synchronized
    private fun detach(connection: ServiceConnection) {
        if (serviceConnection === connection) unbind(clearSession = true)
    }

    @Synchronized
    private fun bind(descriptor: AutocorrectPluginDescriptor) {
        if (activeSession == null && uiClientCount == 0) return
        if (boundProviderId == descriptor.id && serviceConnection != null) return
        val connection = createServiceConnection()
        serviceConnection = connection
        boundProviderId = descriptor.id
        replyMessenger = Messenger(ReplyHandler(descriptor.id))
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
            unbind(clearSession = activeSession != null)
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
        if (remote === service) unbind(clearSession = true)
    }

    @Synchronized
    private fun unbind(clearSession: Boolean = true) {
        val connection = serviceConnection
        if (bound && connection != null) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        serviceConnection = null
        clearConnection(clearSession)
    }

    private fun clearConnection(clearSession: Boolean) {
        remote = null
        boundProviderId = ""
        if (clearSession) {
            activeSession = null
            activeProviderId = ""
            if (uiClientCount > 0) _pluginUi.value = null
        }
        if (uiClientCount > 0) _pluginUiLoading.value = false
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
            val settingsActivity = settingsName?.let { name ->
                ComponentName(
                    serviceInfo.packageName,
                    if (name.startsWith(".")) serviceInfo.packageName + name else name,
                )
            }?.takeIf { activity ->
                runCatching { packageManager.getActivityInfo(activity, 0).exported }
                    .getOrDefault(false)
            }
            AutocorrectPluginDescriptor(
                componentName = component,
                label = resolveInfo.loadLabel(packageManager).toString(),
                settingsActivity = settingsActivity,
            )
        }.sortedBy { it.label.lowercase() }
    }

    private inner class ReplyHandler(
        private val providerId: String,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (providerId != boundProviderId) return
            when (message.what) {
                AutocorrectPluginContract.MSG_SUGGESTIONS -> {
                    val (requestId, result) = suggestionResultFromBundle(message.data)
                    val pending = pendingSuggestions.remove(requestId) ?: return
                    if (requestId == latestSuggestionRequestId) {
                        boostedCodePoints = result.boostedCodePoints
                        pending.complete(result)
                    } else {
                        pending.cancel()
                    }
                }
                AutocorrectPluginContract.MSG_REMOVE_RESULT -> {
                    val (requestId, removed) = removalResultFromBundle(message.data)
                    pendingRemovals.remove(requestId)?.complete(removed)
                }
                AutocorrectPluginContract.MSG_PLUGIN_UI_RESULT -> {
                    val result = pluginUiResultFromBundle(message.data)
                    if (
                        uiClientCount > 0 &&
                        (result.requestId == 0L || result.requestId >= latestPluginUiRequestId)
                    ) {
                        if (result.successful || result.ui != null) _pluginUi.value = result.ui
                        if (result.requestId != 0L) _pluginUiLoading.value = false
                    }
                }
                else -> super.handleMessage(message)
            }
        }
    }

    private fun AutocorrectCandidate.toSuggestionCandidate(
        sessionId: Long,
        content: EditorContent,
    ): ExternalAutocorrectCandidate {
        val localReplacement = EditorRange(replacementStart, replacementEnd).takeIf { range ->
            range.isValid && range.end <= content.text.length
        }
        return ExternalAutocorrectCandidate(
            pluginSessionId = sessionId,
            pluginCandidateId = id,
            delegate = WordSuggestionCandidate(
                text = text,
                secondaryText = secondaryText,
                confidence = confidence,
                isEligibleForAutoCommit = autoCommit,
                isEligibleForUserRemoval = removable,
                sourceProvider = this@AutocorrectPluginManager,
                kind = when (kind) {
                    AutocorrectCandidateKind.TYPED -> SuggestionCandidateKind.TYPED
                    AutocorrectCandidateKind.CORRECTION -> SuggestionCandidateKind.CORRECTION
                    AutocorrectCandidateKind.COMPLETION -> SuggestionCandidateKind.COMPLETION
                    AutocorrectCandidateKind.NEXT_WORD -> SuggestionCandidateKind.NEXT_WORD
                    AutocorrectCandidateKind.EMOJI -> SuggestionCandidateKind.OTHER
                },
            ),
            replacement = localReplacement?.let { range ->
                SuggestionReplacement(
                    range = range.translatedBy(content.offset.coerceAtLeast(0)),
                    originalText = content.text.substring(range.start, range.end),
                    expectedSelection = content.selection,
                )
            },
            separatorBehavior = when (separatorBehavior) {
                AutocorrectSeparatorBehavior.INSERT -> SuggestionSeparatorBehavior.INSERT
                AutocorrectSeparatorBehavior.OMIT -> SuggestionSeparatorBehavior.OMIT
                AutocorrectSeparatorBehavior.DEFAULT -> SuggestionSeparatorBehavior.DEFAULT
            },
        )
    }
}

private class ExternalAutocorrectCandidate(
    val pluginSessionId: Long,
    val pluginCandidateId: String,
    delegate: WordSuggestionCandidate,
    override val replacement: SuggestionReplacement?,
    override val separatorBehavior: SuggestionSeparatorBehavior,
) : SuggestionCandidate by delegate
