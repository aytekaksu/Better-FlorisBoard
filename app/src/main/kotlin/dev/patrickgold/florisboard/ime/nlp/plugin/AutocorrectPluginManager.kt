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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.dictionary.SystemUserDictionaryDatabase
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.dictionary.storedUserDictionaryLocale
import dev.patrickgold.florisboard.ime.dictionary.strictUserDictionaryLocale
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidateKind
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.nlp.SuggestionSeparatorBehavior
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.florisboard.autocorrect.api.AutocorrectAcceptanceKind
import org.florisboard.autocorrect.api.AutocorrectCandidate
import org.florisboard.autocorrect.api.AutocorrectCandidateKind
import org.florisboard.autocorrect.api.AutocorrectCapsMode
import org.florisboard.autocorrect.api.AutocorrectEditorFlags
import org.florisboard.autocorrect.api.AutocorrectInputTrace
import org.florisboard.autocorrect.api.AutocorrectInputMode
import org.florisboard.autocorrect.api.AutocorrectKeyGeometry
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.autocorrect.api.AutocorrectPluginHostSetting
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectRequest
import org.florisboard.autocorrect.api.AutocorrectSeparatorBehavior
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.api.AutocorrectSuggestionResult
import org.florisboard.autocorrect.api.AutocorrectTouchPoint
import org.florisboard.autocorrect.api.AutocorrectTextEvent
import org.florisboard.autocorrect.api.AutocorrectTextEventKind
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryEntry
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryOperation
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryPage
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryRequest
import org.florisboard.autocorrect.api.AutocorrectUserDictionaryStatus
import org.florisboard.autocorrect.api.candidateEventBundle
import org.florisboard.autocorrect.api.finishSessionBundle
import org.florisboard.autocorrect.api.finishSessionResultFromBundle
import org.florisboard.autocorrect.api.pluginUiDocumentBundle
import org.florisboard.autocorrect.api.pluginUiMutationBundle
import org.florisboard.autocorrect.api.pluginUiRequestBundle
import org.florisboard.autocorrect.api.pluginUiResultFromBundle
import org.florisboard.autocorrect.api.removalRequestBundle
import org.florisboard.autocorrect.api.removalResultFromBundle
import org.florisboard.autocorrect.api.suggestionResultFromBundle
import org.florisboard.autocorrect.api.userDictionaryRequestFromBundle
import org.florisboard.autocorrect.api.userDictionaryResultBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class AutocorrectPluginDescriptor(
    val componentName: ComponentName,
    val label: String,
    val uid: Int,
) {
    val id: String
        get() = componentName.flattenToString()
}

internal data class AutocorrectPluginSuggestionBatch(
    val candidates: List<SuggestionCandidate>,
    val handled: Boolean,
)

internal fun isCurrentEditorRequest(
    requestEditorGeneration: Long,
    activeEditorGeneration: Long,
) = requestEditorGeneration == activeEditorGeneration

internal fun isCurrentAutocorrectCandidate(
    candidateSessionId: Long,
    candidateEditorGeneration: Long,
    activeSessionId: Long?,
    admittedSessionId: Long,
    activeEditorGeneration: Long,
    providerMatches: Boolean,
) = providerMatches &&
    candidateSessionId == activeSessionId &&
    candidateSessionId == admittedSessionId &&
    candidateEditorGeneration == activeEditorGeneration

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
        private const val RESPONSE_TIMEOUT_MS = 1_000L
        private const val FINISH_TIMEOUT_MS = 2_000L
        private const val UI_RESPONSE_TIMEOUT_MS = 1_500L
        private const val UI_OPERATION_TIMEOUT_MS = 120_000L
        private const val DICTIONARY_ACTION_TIMEOUT_MS = 10_000L
    }

    private data class DictionaryMutationGrant(
        val providerId: String,
        val expiresAtMillis: Long,
    )

    private val appContext by context.appContext()
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextId = AtomicLong(1L)
    private val pendingSuggestions =
        ConcurrentHashMap<Long, CompletableDeferred<AutocorrectSuggestionResult>>()
    private val pendingRemovals = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val pendingSessionFinishes = mutableSetOf<Long>()
    private val pendingHostSettingValues =
        ConcurrentHashMap<AutocorrectPluginHostSetting, Boolean>()
    private val pendingPluginUiOperations = mutableSetOf<Long>()
    private val pendingDictionaryMutationActions =
        mutableMapOf<Long, DictionaryMutationGrant>()
    private val hostSettingMutationGuard = Mutex()
    private val providerQueryGuard = Mutex()
    private val userDictionaryMutationGuard = Mutex()
    private val hostUserDictionary by lazy { SystemUserDictionaryDatabase(appContext) }
    private val _providers = MutableStateFlow<List<AutocorrectPluginDescriptor>>(emptyList())
    private val _pluginUi = MutableStateFlow<AutocorrectPluginUi?>(null)
    private val _pluginUiLoading = MutableStateFlow(false)
    private val _pluginUiError = MutableStateFlow(false)
    private val _keyboardUiVisible = MutableStateFlow(false)
    @Volatile private var replyMessenger = Messenger(ReplyHandler("", -1, 0L))
    @Volatile private var providerPluginUi: AutocorrectPluginUi? = null
    @Volatile private var providerBindingEpoch = 0L

    val providers = _providers.asStateFlow()
    val pluginUi = _pluginUi.asStateFlow()
    val pluginUiLoading = _pluginUiLoading.asStateFlow()
    val pluginUiError = _pluginUiError.asStateFlow()
    val keyboardUiVisible = _keyboardUiVisible.asStateFlow()

    @Volatile private var remote: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var activeSession: AutocorrectSession? = null
    @Volatile private var connectionReady = CompletableDeferred<Messenger?>()
    @Volatile private var latestSuggestionRequestId = -1L
    @Volatile private var latestPluginUiRequestId = -1L
    @Volatile private var activeProviderId = ""
    @Volatile private var boundProviderId = ""
    @Volatile private var admittedSessionId = -1L
    @Volatile private var editorGeneration = 0L
    @Volatile private var providerQueryComplete = false
    @Volatile private var providerQueryRevision = 0L
    @Volatile private var boostedCodePoints = emptySet<Int>()
    @Volatile private var uiClientCount = 0
    private var preparingPluginUiDocuments = 0
    private var pendingProviderBind: AutocorrectPluginDescriptor? = null
    private var serviceConnection: ServiceConnection? = null
    private var traceKeyboard: TextKeyboard? = null
    private var traceWidth = 0f
    private var traceHeight = 0f
    private var traceKeys = emptyList<AutocorrectKeyGeometry>()
    private val tracePoints = mutableListOf<AutocorrectTouchPoint>()
    private val providerPackageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (
                intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                return
            }
            refreshProviders()
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            providerPackageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override val providerId = ProviderId

    fun refreshProviders() {
        val revision = synchronized(this) {
            providerQueryRevision++
            providerQueryComplete = false
            providerQueryRevision
        }
        scope.launch {
            queryProviders(forceRefresh = true, expectedRevision = revision) { result ->
                result.fold(
                    onSuccess = ::reconcileSelectedProvider,
                    onFailure = { handleProviderQueryFailure() },
                )
            }
        }
    }

    @Synchronized
    fun acquirePluginUi() {
        uiClientCount++
        if (uiClientCount == 1) {
            _pluginUiError.value = false
            _pluginUiLoading.value = true
            connectPluginUi()
        }
    }

    @Synchronized
    fun releasePluginUi() {
        uiClientCount = (uiClientCount - 1).coerceAtLeast(0)
        if (uiClientCount == 0) {
            pendingDictionaryMutationActions.clear()
            send(AutocorrectPluginContract.MSG_PLUGIN_UI_CLOSED, Bundle())
            providerPluginUi = null
            _pluginUi.value = null
            _pluginUiLoading.value = false
            releaseBindingIfIdle()
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
        val hostSetting = providerPluginUi.hostSettingFor(itemId)
        if (hostSetting == null) {
            sendPluginUiMessage(
                AutocorrectPluginContract.MSG_SET_PLUGIN_UI_VALUE,
                itemId,
                value,
            )
            return
        }
        val enabled = value.toBooleanStrictOrNull() ?: return
        pendingHostSettingValues[hostSetting] = enabled
        _pluginUi.value = providerPluginUi?.withHostSettingValues()
        sendPluginUiMessage(
            AutocorrectPluginContract.MSG_SET_PLUGIN_UI_VALUE,
            itemId,
            value,
        )
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                hostSettingMutationGuard.withLock {
                    when (hostSetting) {
                        AutocorrectPluginHostSetting.GLIDE_ENABLED -> prefs.glide.enabled.set(enabled)
                        AutocorrectPluginHostSetting.GLIDE_SENSITIVE -> prefs.glide.sensitive.set(enabled)
                        AutocorrectPluginHostSetting.NONE -> Unit
                    }
                }
            } finally {
                pendingHostSettingValues.remove(hostSetting, enabled)
                _pluginUi.value = providerPluginUi?.withHostSettingValues()
            }
        }
    }

    fun invokePluginUiAction(itemId: String) {
        sendPluginUiMessage(
            AutocorrectPluginContract.MSG_INVOKE_PLUGIN_UI_ACTION,
            itemId,
        )
    }

    @Synchronized
    fun sendPluginUiDocument(itemId: String, uri: Uri, write: Boolean) {
        val expectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        if (
            remote == null ||
            expectedProviderId.isBlank() ||
            expectedProviderId != boundProviderId
        ) {
            _pluginUiError.value = true
            return
        }
        preparingPluginUiDocuments++
        scope.launch(Dispatchers.IO) {
            try {
                val sent = runCatching {
                    val resolver = appContext.contentResolver
                    val displayName = runCatching {
                        resolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            cursor.takeIf { column >= 0 && it.moveToFirst() }?.getString(column)
                        }
                    }.getOrNull()
                    val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
                    resolver.openFileDescriptor(
                        uri,
                        if (write) "rwt" else "r",
                    )?.use { fileDescriptor ->
                        sendPluginUiOperation(
                            what = AutocorrectPluginContract.MSG_PLUGIN_UI_DOCUMENT,
                            expectedProviderId = expectedProviderId,
                            keepLoading = true,
                        ) { requestId ->
                            pluginUiDocumentBundle(
                                requestId = requestId,
                                itemId = itemId,
                                displayName = displayName,
                                mimeType = mimeType,
                                write = write,
                                fileDescriptor = fileDescriptor,
                            )
                        } != null
                    } ?: false
                }.getOrDefault(false)
                if (!sent) _pluginUiError.value = true
            } finally {
                finishPluginUiDocumentPreparation()
            }
        }
    }

    @Synchronized
    fun notifyTextEvent(text: String, kind: AutocorrectTextEventKind) {
        val session = activeSession?.takeIf {
            admittedSessionId == it.sessionId &&
                activeProviderId == boundProviderId
        } ?: return
        val service = remote ?: return
        if (!session.allowPersonalizedLearning) return
        val event = AutocorrectTextEvent(session.sessionId, text, kind)
        if (
            event.text.isNotBlank() ||
            kind == AutocorrectTextEventKind.DELETE_BACKWARD ||
            kind == AutocorrectTextEventKind.DELETE_FORWARD
        ) {
            send(AutocorrectPluginContract.MSG_TEXT_EVENT, event.toBundle(), service)
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
        sendPluginUiOperation(
            what = what,
            expectedProviderId = prefs.suggestion.autocorrectPluginComponent.get(),
        ) { requestId ->
            pluginUiMutationBundle(requestId, itemId, value)
        }
    }

    @Synchronized
    private fun sendPluginUiOperation(
        what: Int,
        expectedProviderId: String,
        keepLoading: Boolean = false,
        data: (Long) -> Bundle,
    ): Long? {
        val service = remote?.takeIf {
            expectedProviderId.isNotBlank() &&
                expectedProviderId == boundProviderId &&
                expectedProviderId == prefs.suggestion.autocorrectPluginComponent.get()
        } ?: run {
            _pluginUiError.value = true
            return null
        }
        val requestId = nextId.getAndIncrement()
        latestPluginUiRequestId = requestId
        _pluginUiError.value = false
        _pluginUiLoading.value = true
        pendingPluginUiOperations.add(requestId)
        if (what == AutocorrectPluginContract.MSG_INVOKE_PLUGIN_UI_ACTION) {
            pendingDictionaryMutationActions[requestId] = DictionaryMutationGrant(
                providerId = expectedProviderId,
                expiresAtMillis = SystemClock.elapsedRealtime() + DICTIONARY_ACTION_TIMEOUT_MS,
            )
        }
        if (!send(what, data(requestId), service)) {
            pendingPluginUiOperations.remove(requestId)
            pendingDictionaryMutationActions.remove(requestId)
            _pluginUiError.value = true
            _pluginUiLoading.value = false
            return null
        }
        expirePluginUiOperation(
            requestId = requestId,
            timeoutMillis = if (keepLoading) {
                UI_OPERATION_TIMEOUT_MS
            } else {
                UI_RESPONSE_TIMEOUT_MS
            },
        )
        if (what == AutocorrectPluginContract.MSG_INVOKE_PLUGIN_UI_ACTION) {
            pendingDictionaryMutationActions[requestId]?.let { grant ->
                expireDictionaryMutationAction(requestId, grant)
            }
        }
        return requestId
    }

    @Synchronized
    private fun requestPluginUi(service: Messenger? = remote) {
        val requestId = nextId.getAndIncrement()
        latestPluginUiRequestId = requestId
        _pluginUiError.value = false
        _pluginUiLoading.value = true
        if (!send(
                AutocorrectPluginContract.MSG_GET_PLUGIN_UI,
                pluginUiRequestBundle(requestId, configuredLanguageTags()),
                service,
            )
        ) {
            _pluginUiError.value = true
            _pluginUiLoading.value = false
        } else {
            expirePluginUiRequest(requestId)
        }
    }

    private fun configuredLanguageTags(): List<String> {
        val configured = subtypeManager.subtypes
            .asSequence()
            .flatMap { it.locales().asSequence() }
            .map { it.languageTag() }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        return configured.ifEmpty {
            subtypeManager.activeSubtype.locales().map { it.languageTag() }
        }
    }

    private fun expirePluginUiRequest(requestId: Long) {
        scope.launch {
            delay(UI_RESPONSE_TIMEOUT_MS)
            if (latestPluginUiRequestId == requestId && _pluginUiLoading.value) {
                _pluginUiError.value = true
                _pluginUiLoading.value = false
            }
        }
    }

    private fun expirePluginUiOperation(requestId: Long, timeoutMillis: Long) {
        scope.launch {
            delay(timeoutMillis)
            if (finishPluginUiOperation(requestId)) {
                _pluginUiError.value = true
            }
        }
    }

    private fun expireDictionaryMutationAction(
        requestId: Long,
        grant: DictionaryMutationGrant,
    ) {
        scope.launch {
            delay(DICTIONARY_ACTION_TIMEOUT_MS)
            synchronized(this@AutocorrectPluginManager) {
                pendingDictionaryMutationActions.remove(requestId, grant)
            }
        }
    }

    @Synchronized
    private fun finishPluginUiOperation(requestId: Long): Boolean {
        if (!pendingPluginUiOperations.remove(requestId)) return false
        val wasLatest = latestPluginUiRequestId == requestId
        if (wasLatest) {
            _pluginUiLoading.value = false
        }
        releaseBindingIfIdle()
        return wasLatest
    }

    @Synchronized
    private fun finishPluginUiDocumentPreparation() {
        preparingPluginUiDocuments = (preparingPluginUiDocuments - 1).coerceAtLeast(0)
        releaseBindingIfIdle()
    }

    private fun hasInFlightPluginUiOperation() =
        preparingPluginUiDocuments > 0 || pendingPluginUiOperations.isNotEmpty()

    private fun releaseBindingIfIdle(): Boolean {
        if (hasInFlightPluginUiOperation() || pendingSessionFinishes.isNotEmpty()) return false
        val descriptor = pendingProviderBind.also { pendingProviderBind = null }
        if (descriptor != null && wantsProvider(descriptor.id)) {
            bind(descriptor)
            return true
        }
        val hasDemand = activeSession != null || uiClientCount > 0
        if (boundProviderId.isNotBlank() && !wantsProvider(boundProviderId)) {
            unbind(clearSession = false)
            if (hasDemand) refreshProviders()
            return true
        }
        if (!hasDemand) {
            unbind(clearSession = false)
            return true
        }
        return false
    }

    private fun connectPluginUi() {
        val selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        if (selectedProviderId.isBlank()) {
            _pluginUiLoading.value = false
            return
        }
        scope.launch {
            queryProviders { result ->
                synchronized(this@AutocorrectPluginManager) {
                    if (uiClientCount == 0) return@synchronized
                    val descriptor = result.getOrNull()
                        ?.firstOrNull { it.id == selectedProviderId }
                    if (descriptor == null) {
                        _pluginUiError.value = true
                        _pluginUiLoading.value = false
                    } else if (remote != null && boundProviderId == descriptor.id) {
                        requestPluginUi()
                    } else {
                        if (activeProviderId.isNotBlank() &&
                            activeProviderId != descriptor.id
                        ) {
                            endSession()
                        }
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
        requestEditorGeneration: Long,
    ): AutocorrectSession? {
        if (!isCurrentEditorRequest(requestEditorGeneration, editorGeneration)) return null
        val selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        if (
            !prefs.suggestion.enabled.get() ||
            selectedProviderId.isBlank() ||
            !editorInfo.inputAttributes.allowsAutocorrectPluginSession(
                isPrivateSession = isPrivateSession,
                isRawInputEditor = editorInfo.isRawInputEditor,
            )
        ) {
            finishCurrentSession()
            return null
        }
        val secondaryLanguageTags = subtype.secondaryLocales.map { it.languageTag() }
        val editorFlags = editorInfo.autocorrectEditorFlags()
        val preferredEmojiSkinToneModifier = prefs.emoji.preferredSkinTone.get().id
        activeSession?.takeIf { session ->
            activeProviderId == selectedProviderId &&
                session.primaryLanguageTag == subtype.primaryLocale.languageTag() &&
                session.secondaryLanguageTags == secondaryLanguageTags &&
                session.inputType == editorInfo.inputAttributes.raw &&
                session.capsMode == editorInfo.initialCapsMode.toInt() &&
                session.editorFlags == editorFlags &&
                session.preferredEmojiSkinToneModifier == preferredEmojiSkinToneModifier
        }?.let { return it }
        if (providerQueryComplete && providers.value.none { it.id == selectedProviderId }) {
            finishCurrentSession()
            return null
        }
        finishCurrentSession()
        val session = AutocorrectSession(
            sessionId = nextId.getAndIncrement(),
            primaryLanguageTag = subtype.primaryLocale.languageTag(),
            secondaryLanguageTags = secondaryLanguageTags,
            inputType = editorInfo.inputAttributes.raw,
            capsMode = editorInfo.initialCapsMode.toInt(),
            allowPersonalizedLearning = !editorInfo.imeOptions.flagNoPersonalizedLearning,
            editorFlags = editorFlags,
            preferredEmojiSkinToneModifier = preferredEmojiSkinToneModifier,
        )
        activeSession = session
        activeProviderId = selectedProviderId
        connectionReady = CompletableDeferred()
        scope.launch {
            queryProviders { result ->
                if (result.isFailure) {
                    if (activeSession?.sessionId == session.sessionId) finishCurrentSession()
                    return@queryProviders
                }
                val descriptor = result.getOrThrow()
                    .firstOrNull { it.id == selectedProviderId }
                if (descriptor == null) {
                    if (activeSession?.sessionId == session.sessionId) {
                        endSession()
                        releaseBindingIfIdle()
                    }
                    return@queryProviders
                }
                if (activeSession?.sessionId == session.sessionId) {
                    val service = remote
                    if (service != null && boundProviderId == descriptor.id) {
                        admitSession(session, service)
                    } else {
                        bind(descriptor)
                    }
                }
            }
        }
        return session
    }

    private fun admitSession(session: AutocorrectSession, service: Messenger) {
        if (
            activeSession?.sessionId != session.sessionId ||
            activeProviderId != boundProviderId ||
            remote !== service
        ) {
            return
        }
        if (admittedSessionId == session.sessionId ||
            send(AutocorrectPluginContract.MSG_START_SESSION, session.toBundle(), service)
        ) {
            admittedSessionId = session.sessionId
            connectionReady.complete(service)
        }
    }

    @Synchronized
    internal fun captureEditorGeneration() = editorGeneration

    @Synchronized
    fun finishSession() {
        editorGeneration++
        finishCurrentSession()
    }

    @Synchronized
    private fun finishCurrentSession() {
        val hadSession = endSession()
        if (!releaseBindingIfIdle() &&
            hadSession &&
            uiClientCount > 0 &&
            remote != null &&
            prefs.suggestion.autocorrectPluginComponent.get() == boundProviderId
        ) {
            requestPluginUi()
        }
    }

    private fun endSession(): Boolean {
        val session = activeSession
        if (
            session != null &&
            admittedSessionId == session.sessionId &&
            activeProviderId == boundProviderId
        ) {
            val finalRequest = buildFinalRequest(session)
            pendingSessionFinishes.add(session.sessionId)
            if (send(
                what = AutocorrectPluginContract.MSG_FINISH_SESSION,
                data = finishSessionBundle(session.sessionId, finalRequest),
            )) {
                expireSessionFinish(session.sessionId)
            } else {
                pendingSessionFinishes.remove(session.sessionId)
            }
        }
        if (session?.sessionId == admittedSessionId) admittedSessionId = -1L
        activeSession = null
        activeProviderId = ""
        if (session != null) connectionReady.complete(null)
        latestSuggestionRequestId = -1L
        clearInputTrace()
        cancelPending()
        return session != null
    }

    private fun buildFinalRequest(session: AutocorrectSession): AutocorrectRequest {
        val content = editorInstance.activeContent.takeIf {
            it.localSelection.isCursorMode &&
                it.localSelection.start in 0..it.text.length
        }
        val text = content?.text.orEmpty()
        val cursor = content?.localSelection?.start ?: 0
        val windowStart =
            (cursor - AutocorrectPluginContract.MAX_CONTEXT_CHARS).coerceAtLeast(0)
        val windowEnd = minOf(
            text.length,
            maxOf(cursor, windowStart + AutocorrectPluginContract.MAX_CONTEXT_CHARS),
        )
        fun EditorRange?.inWindow() = this?.takeIf {
            isValid && start >= windowStart && end <= windowEnd
        }?.translatedBy(-windowStart) ?: EditorRange.Unspecified
        val composing = content?.localComposing.inWindow()
        val currentWord = content?.localCurrentWord.inWindow()
        val localCursor = cursor - windowStart
        return AutocorrectRequest(
            sessionId = session.sessionId,
            requestId = nextId.getAndIncrement(),
            text = text.substring(windowStart, windowEnd),
            selectionStart = localCursor,
            selectionEnd = localCursor,
            composingStart = composing.start,
            composingEnd = composing.end,
            currentWordStart = currentWord.start,
            currentWordEnd = currentWord.end,
            maxCandidateCount = 1,
            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            capsMode = keyboardManager.activeState.inputShiftState.toAutocorrectCapsMode(),
        )
    }

    private fun expireSessionFinish(sessionId: Long) {
        scope.launch {
            delay(FINISH_TIMEOUT_MS)
            completeSessionFinish(sessionId)
        }
    }

    @Synchronized
    private fun completeSessionFinish(sessionId: Long) {
        if (!pendingSessionFinishes.remove(sessionId) || pendingSessionFinishes.isNotEmpty()) return
        releaseBindingIfIdle()
    }

    private fun wantsProvider(providerId: String) =
        providerId.isNotBlank() &&
            ((activeSession != null && activeProviderId == providerId) ||
                (uiClientCount > 0 &&
                    prefs.suggestion.autocorrectPluginComponent.get() == providerId))

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        return suggestWithStatus(
            subtype = subtype,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            isPrivateSession = isPrivateSession,
            requestEditorGeneration = captureEditorGeneration(),
        ).candidates
    }

    internal suspend fun suggestWithStatus(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
        requestEditorGeneration: Long,
    ): AutocorrectPluginSuggestionBatch {
        if (!content.localSelection.isCursorMode) {
            return AutocorrectPluginSuggestionBatch(emptyList(), handled = false)
        }
        val session = ensureSession(
            subtype,
            editorInstance.activeInfo,
            isPrivateSession,
            requestEditorGeneration,
        )
            ?: return AutocorrectPluginSuggestionBatch(emptyList(), handled = false)
        val result = requestCandidates(
            session = session,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            inputTrace = inputTraceFor(content),
        ) ?: return AutocorrectPluginSuggestionBatch(emptyList(), handled = false)
        return AutocorrectPluginSuggestionBatch(
            candidates = result.candidates.map {
                it.toSuggestionCandidate(session.sessionId, content)
            },
            handled = result.handled,
        )
    }

    internal suspend fun suggestGesture(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
        inputTrace: AutocorrectInputTrace,
        requestEditorGeneration: Long,
    ): AutocorrectPluginSuggestionBatch {
        if (
            !content.localSelection.isCursorMode ||
            inputTrace.mode != AutocorrectInputMode.GESTURE ||
            inputTrace.gesturePoints.size < 2
        ) {
            return AutocorrectPluginSuggestionBatch(emptyList(), handled = false)
        }
        val session = ensureSession(
            subtype,
            editorInstance.activeInfo,
            isPrivateSession,
            requestEditorGeneration,
        )
            ?: return AutocorrectPluginSuggestionBatch(emptyList(), handled = false)
        val result = requestCandidates(
            session = session,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            inputTrace = inputTrace,
        ) ?: return AutocorrectPluginSuggestionBatch(emptyList(), handled = false)
        return AutocorrectPluginSuggestionBatch(
            candidates = result.candidates.map {
                it.toSuggestionCandidate(session.sessionId, content)
            },
            handled = result.handled,
        )
    }

    private suspend fun requestCandidates(
        session: AutocorrectSession,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        inputTrace: AutocorrectInputTrace,
    ): AutocorrectSuggestionResult? {
        val service = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            connectionReady.await()
        } ?: return null

        val (requestId, deferred) = synchronized(this) {
            if (
                activeSession?.sessionId != session.sessionId ||
                admittedSessionId != session.sessionId ||
                remote !== service ||
                boundProviderId != activeProviderId
            ) {
                return null
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
                capsMode = keyboardManager.activeState.inputShiftState.toAutocorrectCapsMode(),
            )
            val deferred = CompletableDeferred<AutocorrectSuggestionResult>()
            pendingSuggestions[requestId] = deferred
            if (!send(AutocorrectPluginContract.MSG_SUGGEST, request.toBundle(), service)) {
                pendingSuggestions.remove(requestId)
                return null
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
        return result?.copy(
            candidates = result.candidates.take(
                maxCandidateCount.coerceIn(1, AutocorrectPluginContract.MAX_CANDIDATES),
            ),
        )
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        notifySuggestionAccepted(candidate, AutocorrectAcceptanceKind.MANUAL)
    }

    @Synchronized
    fun canCommitCandidate(candidate: SuggestionCandidate): Boolean {
        if (candidate !is ExternalAutocorrectCandidate) return true
        return isCurrentAutocorrectCandidate(
            candidateSessionId = candidate.pluginSessionId,
            candidateEditorGeneration = candidate.editorGeneration,
            activeSessionId = activeSession?.sessionId,
            admittedSessionId = admittedSessionId,
            activeEditorGeneration = editorGeneration,
            providerMatches = remote != null &&
                activeProviderId.isNotBlank() &&
                activeProviderId == boundProviderId,
        )
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
        notifySuggestionReverted(candidate)
    }

    fun notifySuggestionReverted(candidate: SuggestionCandidate) {
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
                it.sessionId == candidate.pluginSessionId &&
                    admittedSessionId == it.sessionId &&
                    activeProviderId == boundProviderId
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
        val removed = try {
            withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
                deferred.await()
            } ?: false
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            false
        }
        pendingRemovals.remove(requestId, deferred)
        return removed
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> = emptyList()

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double = 0.0

    override suspend fun destroy() {
        _keyboardUiVisible.value = false
        uiClientCount = 0
        pendingDictionaryMutationActions.clear()
        finishSession()
    }

    @Synchronized
    private fun notifyCandidateEvent(
        what: Int,
        sessionId: Long,
        candidateId: String,
        acceptanceKind: AutocorrectAcceptanceKind? = null,
    ) {
        if (
            activeSession?.sessionId != sessionId ||
            admittedSessionId != sessionId ||
            activeProviderId != boundProviderId
        ) {
            return
        }
        send(what, candidateEventBundle(sessionId, candidateId, acceptanceKind), remote)
    }

    private fun createServiceConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            attach(this, binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleServiceDisconnected(this)
        }

        override fun onBindingDied(name: ComponentName) {
            handleBindingDied(this)
        }

        override fun onNullBinding(name: ComponentName) {
            handleNullBinding(this)
        }
    }

    @Synchronized
    private fun attach(connection: ServiceConnection, binder: IBinder) {
        if (serviceConnection !== connection) return
        val service = Messenger(binder)
        remote = service
        activeSession?.takeIf { activeProviderId == boundProviderId }?.let { session ->
            admitSession(session, service)
        }
        when {
            uiClientCount > 0 &&
                prefs.suggestion.autocorrectPluginComponent.get() == boundProviderId -> {
                requestPluginUi(service)
            }
            pendingSessionFinishes.isNotEmpty() || hasInFlightPluginUiOperation() -> Unit
            else -> releaseBindingIfIdle()
        }
    }

    @Synchronized
    private fun handleServiceDisconnected(connection: ServiceConnection) {
        if (serviceConnection !== connection) return
        suspendConnection()
    }

    @Synchronized
    private fun handleDeadRemote(service: Messenger) {
        if (remote !== service) return
        suspendConnection()
    }

    private fun suspendConnection() {
        val queuedProvider = pendingProviderBind.also { pendingProviderBind = null }
        pendingSessionFinishes.clear()
        admittedSessionId = -1L
        remote = null
        boostedCodePoints = emptySet()
        pendingPluginUiOperations.clear()
        pendingDictionaryMutationActions.clear()
        connectionReady.complete(null)
        connectionReady = CompletableDeferred()
        failPending()
        when {
            hasInFlightPluginUiOperation() -> {
                pendingProviderBind = queuedProvider?.takeIf { wantsProvider(it.id) }
                if (uiClientCount > 0) _pluginUiLoading.value = true
            }
            queuedProvider != null && wantsProvider(queuedProvider.id) -> {
                unbind(clearSession = false)
                bind(queuedProvider)
            }
            wantsProvider(boundProviderId) -> {
                if (uiClientCount > 0) _pluginUiLoading.value = true
            }
            else -> {
                unbind(clearSession = false)
                if (activeSession != null || uiClientCount > 0) refreshProviders()
            }
        }
    }

    @Synchronized
    private fun handleBindingDied(connection: ServiceConnection) {
        if (serviceConnection !== connection) return
        val failedProviderId = boundProviderId
        val queuedProvider = pendingProviderBind?.takeIf { wantsProvider(it.id) }
        val preserveActiveSession =
            activeSession != null && activeProviderId != failedProviderId
        unbind(clearSession = !preserveActiveSession)
        when {
            queuedProvider != null -> bind(queuedProvider)
            preserveActiveSession -> refreshProviders()
            uiClientCount > 0 -> {
                _pluginUiError.value = false
                _pluginUiLoading.value = true
                connectPluginUi()
            }
        }
    }

    @Synchronized
    private fun handleNullBinding(connection: ServiceConnection) {
        if (serviceConnection !== connection) return
        val failedProviderId = boundProviderId
        val queuedProvider = pendingProviderBind?.takeIf { wantsProvider(it.id) }
        val preserveActiveSession =
            activeSession != null && activeProviderId != failedProviderId
        unbind(clearSession = !preserveActiveSession)
        when {
            queuedProvider != null -> bind(queuedProvider)
            preserveActiveSession -> refreshProviders()
            uiClientCount > 0 -> _pluginUiError.value = true
        }
    }

    @Synchronized
    private fun bind(descriptor: AutocorrectPluginDescriptor) {
        if (!wantsProvider(descriptor.id)) return
        if (boundProviderId == descriptor.id && serviceConnection != null) return
        if (boundProviderId.isNotBlank() && boundProviderId != descriptor.id) {
            if (pendingSessionFinishes.isNotEmpty() || hasInFlightPluginUiOperation()) {
                pendingProviderBind = descriptor
                return
            }
            unbind(clearSession = false)
        }
        val connection = createServiceConnection()
        serviceConnection = connection
        boundProviderId = descriptor.id
        val bindingEpoch = ++providerBindingEpoch
        replyMessenger = Messenger(ReplyHandler(descriptor.id, descriptor.uid, bindingEpoch))
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
        } catch (error: RemoteException) {
            if (error is DeadObjectException) {
                handleDeadRemote(service)
            } else {
                detachRemote(service)
            }
            false
        }
    }

    @Synchronized
    private fun detachRemote(service: Messenger) {
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
        providerBindingEpoch++
        remote = null
        boundProviderId = ""
        admittedSessionId = -1L
        pendingProviderBind = null
        pendingSessionFinishes.clear()
        boostedCodePoints = emptySet()
        pendingPluginUiOperations.clear()
        pendingDictionaryMutationActions.clear()
        if (clearSession) {
            activeSession = null
            activeProviderId = ""
            if (uiClientCount > 0) {
                providerPluginUi = null
                _pluginUi.value = null
            }
        }
        if (uiClientCount > 0) _pluginUiLoading.value = false
        if (clearSession || activeSession == null) connectionReady.complete(null)
        failPending()
    }

    private fun cancelPending() {
        pendingSuggestions.values.forEach { it.cancel() }
        pendingSuggestions.clear()
        pendingRemovals.values.forEach { it.cancel() }
        pendingRemovals.clear()
    }

    private fun failPending() {
        pendingSuggestions.values.forEach {
            it.complete(AutocorrectSuggestionResult.Unhandled)
        }
        pendingSuggestions.clear()
        pendingRemovals.values.forEach { it.complete(false) }
        pendingRemovals.clear()
    }

    private suspend fun queryProviders(
        forceRefresh: Boolean = false,
        expectedRevision: Long? = null,
        consume: (Result<List<AutocorrectPluginDescriptor>>) -> Unit,
    ) = providerQueryGuard.withLock {
        val revision = providerQueryRevision
        if (expectedRevision != null && expectedRevision != revision) return@withLock
        val cached = synchronized(this@AutocorrectPluginManager) {
            providers.value.takeIf { !forceRefresh && providerQueryComplete }
        }
        val result = cached?.let { Result.success(it) } ?: runCatching {
            discoverProviders()
        }.let { queryResult ->
            synchronized(this@AutocorrectPluginManager) {
                if (revision != providerQueryRevision) return@withLock
                queryResult.onSuccess {
                    _providers.value = it
                    providerQueryComplete = true
                }.onFailure {
                    providerQueryComplete = false
                }
            }
        }
        withContext(Dispatchers.Main.immediate) {
            synchronized(this@AutocorrectPluginManager) {
                if (revision == providerQueryRevision) consume(result)
            }
        }
    }

    private fun handleUserDictionaryRequest(
        providerId: String,
        bindingEpoch: Long,
        message: Message,
    ) {
        val request = runCatching {
            userDictionaryRequestFromBundle(message.data)
        }.getOrNull() ?: return
        val replyTo = message.replyTo ?: return
        val providerBinder = replyTo.binder
        if (!isCurrentProviderBinding(providerId, bindingEpoch, providerBinder)) return
        scope.launch(Dispatchers.IO) {
            val result = userDictionaryMutationGuard.withLock {
                val access = synchronized(this@AutocorrectPluginManager) {
                    userDictionaryAccess(
                        providerId,
                        bindingEpoch,
                        providerBinder,
                        request,
                    )
                }
                if (access == null) {
                    AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.DENIED)
                } else {
                    runCatching {
                        performUserDictionaryRequest(request, access)
                    }.getOrElse {
                        AutocorrectUserDictionaryPage(
                            AutocorrectUserDictionaryStatus.UNAVAILABLE,
                        )
                    }
                }
            }
            if (!isCurrentProviderBinding(providerId, bindingEpoch, providerBinder)) return@launch
            try {
                replyTo.send(
                    Message.obtain(
                        null,
                        AutocorrectPluginContract.MSG_HOST_USER_DICTIONARY_RESULT,
                    ).apply {
                        data = userDictionaryResultBundle(
                            requestId = request.requestId,
                            status = result.status,
                            entries = result.entries,
                            nextAfterId = result.nextAfterId,
                        )
                    },
                )
            } catch (_: RemoteException) {
                // The selected provider disappeared while its request was in flight.
            }
        }
    }

    private fun userDictionaryAccess(
        providerId: String,
        bindingEpoch: Long,
        providerBinder: IBinder,
        request: AutocorrectUserDictionaryRequest,
    ): List<String>? {
        if (
            !prefs.dictionary.enableSystemUserDictionary.get() ||
            !isCurrentProviderBinding(providerId, bindingEpoch, providerBinder)
        ) {
            revokeDeniedDictionaryMutation(providerId, request)
            return null
        }
        val selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        return when (request.operation) {
            AutocorrectUserDictionaryOperation.QUERY -> {
                val session = activeSession
                when {
                    uiClientCount > 0 && providerId == selectedProviderId -> {
                        request.languageTags
                    }
                    session != null &&
                        providerId == selectedProviderId &&
                        providerId == activeProviderId &&
                        admittedSessionId == session.sessionId -> {
                        listOf(session.primaryLanguageTag) + session.secondaryLanguageTags
                    }
                    else -> null
                }
            }
            AutocorrectUserDictionaryOperation.UPSERT,
            AutocorrectUserDictionaryOperation.DELETE -> {
                val grant = pendingDictionaryMutationActions[request.originUiRequestId]
                if (
                    uiClientCount > 0 &&
                    providerId == selectedProviderId &&
                    grant?.providerId == providerId &&
                    SystemClock.elapsedRealtime() < grant.expiresAtMillis
                ) {
                    emptyList()
                } else {
                    revokeDeniedDictionaryMutation(providerId, request)
                    null
                }
            }
        }
    }

    @Synchronized
    private fun isCurrentProviderBinding(
        providerId: String,
        bindingEpoch: Long,
        providerBinder: IBinder,
    ) = bindingEpoch == providerBindingEpoch &&
        providerId == boundProviderId &&
        remote?.binder == providerBinder

    private fun revokeDeniedDictionaryMutation(
        providerId: String,
        request: AutocorrectUserDictionaryRequest,
    ) {
        if (request.operation != AutocorrectUserDictionaryOperation.QUERY) {
            pendingDictionaryMutationActions[request.originUiRequestId]
                ?.takeIf { it.providerId == providerId }
                ?.let { pendingDictionaryMutationActions.remove(request.originUiRequestId, it) }
        }
    }

    @Synchronized
    private fun handleProviderQueryFailure() {
        if (remote != null || serviceConnection != null) return
        endSession()
        if (uiClientCount > 0) {
            _pluginUiLoading.value = false
            _pluginUiError.value = true
        }
    }

    private fun reconcileSelectedProvider(
        discoveredProviders: List<AutocorrectPluginDescriptor>,
    ) {
        val selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        if (selectedProviderId.isBlank()) return
        val descriptor = discoveredProviders.firstOrNull { it.id == selectedProviderId }
        synchronized(this) {
            if (prefs.suggestion.autocorrectPluginComponent.get() != selectedProviderId) return
            if (descriptor == null) {
                if (activeProviderId == selectedProviderId) {
                    endSession()
                }
                if (pendingProviderBind?.id == selectedProviderId) {
                    pendingProviderBind = null
                }
                if (boundProviderId == selectedProviderId) {
                    unbind(clearSession = true)
                } else if (
                    pendingSessionFinishes.isEmpty() &&
                    !hasInFlightPluginUiOperation() &&
                    boundProviderId.isNotBlank()
                ) {
                    unbind(clearSession = false)
                }
                if (uiClientCount > 0) {
                    providerPluginUi = null
                    _pluginUi.value = null
                    _pluginUiLoading.value = false
                    _pluginUiError.value = true
                }
                return
            }
            if (remote != null || serviceConnection != null) return
            val activeForProvider = activeSession != null && activeProviderId == descriptor.id
            if (!activeForProvider && uiClientCount == 0) return
            if (activeProviderId.isNotBlank() && activeProviderId != descriptor.id) {
                endSession()
            }
            if (connectionReady.isCompleted) {
                connectionReady = CompletableDeferred()
            }
            bind(descriptor)
        }
    }

    private fun performUserDictionaryRequest(
        request: AutocorrectUserDictionaryRequest,
        allowedLanguageTags: List<String>,
    ): AutocorrectUserDictionaryPage = when (request.operation) {
        AutocorrectUserDictionaryOperation.QUERY -> {
            val allowedLocales = allowedLanguageTags.mapNotNull(::strictUserDictionaryLocale)
            if (allowedLocales.size != allowedLanguageTags.size) {
                return AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.INVALID)
            }
            val page = hostUserDictionary.queryPage(
                afterId = request.afterId,
                limit = request.limit,
                allowedLocales = allowedLocales,
            ) ?: return AutocorrectUserDictionaryPage(
                AutocorrectUserDictionaryStatus.UNAVAILABLE,
            )
            AutocorrectUserDictionaryPage(
                status = AutocorrectUserDictionaryStatus.OK,
                entries = page.entries.mapNotNull { it.toPluginEntry() },
                nextAfterId = page.nextAfterId,
            )
        }
        AutocorrectUserDictionaryOperation.UPSERT -> {
            val incoming = request.entry
                ?: return AutocorrectUserDictionaryPage(
                    AutocorrectUserDictionaryStatus.INVALID,
                )
            val locale = incoming.languageTag?.let { tag ->
                val parsed = strictUserDictionaryLocale(tag)
                    ?: return AutocorrectUserDictionaryPage(
                        AutocorrectUserDictionaryStatus.INVALID,
                    )
                parsed.toString().takeIf { storage ->
                    storage.isNotEmpty() &&
                        storedUserDictionaryLocale(storage)?.toLanguageTag() ==
                        parsed.toLanguageTag()
                } ?: return AutocorrectUserDictionaryPage(
                    AutocorrectUserDictionaryStatus.INVALID,
                )
            }
            val entry = UserDictionaryEntry(
                id = incoming.id,
                word = incoming.word,
                freq = incoming.frequency,
                locale = locale,
                shortcut = incoming.shortcut,
            )
            val persisted = if (entry.id == 0L) {
                val insertedId = hostUserDictionary.insertReturningId(entry)
                    ?: return AutocorrectUserDictionaryPage(
                        AutocorrectUserDictionaryStatus.UNAVAILABLE,
                    )
                entry.copy(id = insertedId)
            } else {
                when (hostUserDictionary.updateById(entry)) {
                    1 -> Unit
                    0 -> return AutocorrectUserDictionaryPage(
                        AutocorrectUserDictionaryStatus.INVALID,
                    )
                    else -> return AutocorrectUserDictionaryPage(
                        AutocorrectUserDictionaryStatus.UNAVAILABLE,
                    )
                }
                entry
            }
            val persistedPluginEntry = persisted.toPluginEntry()
                ?: return AutocorrectUserDictionaryPage(
                    AutocorrectUserDictionaryStatus.UNAVAILABLE,
                )
            AutocorrectUserDictionaryPage(
                AutocorrectUserDictionaryStatus.OK,
                entries = listOf(persistedPluginEntry),
            )
        }
        AutocorrectUserDictionaryOperation.DELETE -> {
            val id = request.entryId.takeIf { it > 0L }
                ?: return AutocorrectUserDictionaryPage(
                    AutocorrectUserDictionaryStatus.INVALID,
                )
            when (hostUserDictionary.deleteById(id)) {
                1 -> AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.OK)
                0 -> AutocorrectUserDictionaryPage(AutocorrectUserDictionaryStatus.INVALID)
                else -> AutocorrectUserDictionaryPage(
                    AutocorrectUserDictionaryStatus.UNAVAILABLE,
                )
            }
        }
    }

    private fun UserDictionaryEntry.toPluginEntry(): AutocorrectUserDictionaryEntry? {
        val languageTag = locale?.let {
            storedUserDictionaryLocale(it)?.toLanguageTag() ?: return null
        }
        return AutocorrectUserDictionaryEntry(
            id = id,
            word = word,
            frequency = freq,
            languageTag = languageTag,
            shortcut = shortcut,
        )
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
            AutocorrectPluginDescriptor(
                componentName = component,
                label = resolveInfo.loadLabel(packageManager).toString(),
                uid = serviceInfo.applicationInfo.uid,
            )
        }.sortedBy { it.label.lowercase() }
    }

    private inner class ReplyHandler(
        private val providerId: String,
        private val providerUid: Int,
        private val bindingEpoch: Long,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (
                message.sendingUid != providerUid ||
                bindingEpoch != providerBindingEpoch ||
                providerId != boundProviderId ||
                remote == null
            ) {
                return
            }
            when (message.what) {
                AutocorrectPluginContract.MSG_SUGGESTIONS -> {
                    val (requestId, result) = suggestionResultFromBundle(message.data)
                    val pending = pendingSuggestions.remove(requestId) ?: return
                    if (requestId == latestSuggestionRequestId) {
                        boostedCodePoints = result.boostedCodePoints.takeIf {
                            result.handled
                        }.orEmpty()
                        pending.complete(result)
                    } else {
                        pending.cancel()
                    }
                }
                AutocorrectPluginContract.MSG_REMOVE_RESULT -> {
                    val (requestId, removed) = removalResultFromBundle(message.data)
                    pendingRemovals.remove(requestId)?.complete(removed)
                }
                AutocorrectPluginContract.MSG_FINISH_SESSION_RESULT -> {
                    completeSessionFinish(finishSessionResultFromBundle(message.data))
                }
                AutocorrectPluginContract.MSG_PLUGIN_UI_RESULT -> {
                    val result = pluginUiResultFromBundle(message.data)
                    synchronized(this@AutocorrectPluginManager) {
                        pendingDictionaryMutationActions.remove(result.requestId)
                    }
                    finishPluginUiOperation(result.requestId)
                    if (
                        uiClientCount > 0 &&
                        (result.requestId == 0L || result.requestId >= latestPluginUiRequestId)
                    ) {
                        _pluginUiError.value = !result.successful
                        if (result.successful || result.ui != null) {
                            providerPluginUi = result.ui
                            _pluginUi.value = result.ui?.withHostSettingValues()
                        }
                        if (result.requestId != 0L) _pluginUiLoading.value = false
                    }
                }
                AutocorrectPluginContract.MSG_HOST_USER_DICTIONARY_REQUEST -> {
                    handleUserDictionaryRequest(providerId, bindingEpoch, message)
                }
                else -> super.handleMessage(message)
            }
        }
    }

    private fun AutocorrectPluginUi?.hostSettingFor(
        itemId: String,
    ): AutocorrectPluginHostSetting? {
        return this?.pages
            .orEmpty()
            .flatMap { it.items }
            .filter { it.id == itemId }
            .map { it.hostSetting }
            .distinct()
            .singleOrNull()
            ?.takeUnless { it == AutocorrectPluginHostSetting.NONE }
    }

    private fun AutocorrectPluginUi.withHostSettingValues(): AutocorrectPluginUi {
        val glideEnabled = pendingHostSettingValues[AutocorrectPluginHostSetting.GLIDE_ENABLED]
            ?: prefs.glide.enabled.get()
        val glideSensitive =
            pendingHostSettingValues[AutocorrectPluginHostSetting.GLIDE_SENSITIVE]
            ?: prefs.glide.sensitive.get()
        return copy(
            pages = pages.map { page ->
                page.copy(
                    items = page.items.map { item ->
                        when (item.hostSetting) {
                            AutocorrectPluginHostSetting.NONE -> item
                            AutocorrectPluginHostSetting.GLIDE_ENABLED -> item.copy(
                                value = glideEnabled.toString(),
                            )
                            AutocorrectPluginHostSetting.GLIDE_SENSITIVE -> item.copy(
                                value = glideSensitive.toString(),
                                enabled = item.enabled && glideEnabled,
                            )
                        }
                    },
                )
            },
        )
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
            editorGeneration = editorGeneration,
            isVisible = visible,
            delegate = WordSuggestionCandidate(
                text = text,
                secondaryText = secondaryText,
                confidence = confidence,
                isEligibleForAutoCommit = autoCommit,
                isEligibleForUserRemoval = removable,
                sourceProvider = this@AutocorrectPluginManager,
                kind = kind.toSuggestionCandidateKind(),
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

internal fun InputAttributes.allowsAutocorrectPluginSession(
    isPrivateSession: Boolean,
    isRawInputEditor: Boolean,
) = !isPrivateSession && !isRawInputEditor && !isPassword

internal fun AutocorrectCandidateKind.toSuggestionCandidateKind() = when (this) {
    AutocorrectCandidateKind.TYPED -> SuggestionCandidateKind.TYPED
    AutocorrectCandidateKind.CORRECTION -> SuggestionCandidateKind.CORRECTION
    AutocorrectCandidateKind.COMPLETION -> SuggestionCandidateKind.COMPLETION
    AutocorrectCandidateKind.NEXT_WORD -> SuggestionCandidateKind.NEXT_WORD
    AutocorrectCandidateKind.EMOJI -> SuggestionCandidateKind.EMOJI
}

private fun InputShiftState.toAutocorrectCapsMode() = when (this) {
    InputShiftState.UNSHIFTED -> AutocorrectCapsMode.UNSHIFTED
    InputShiftState.SHIFTED_MANUAL -> AutocorrectCapsMode.SHIFTED_MANUAL
    InputShiftState.SHIFTED_AUTOMATIC -> AutocorrectCapsMode.SHIFTED_AUTOMATIC
    InputShiftState.CAPS_LOCK -> AutocorrectCapsMode.CAPS_LOCK
}

private fun FlorisEditorInfo.autocorrectEditorFlags(): Int {
    val inputType = inputAttributes.raw
    val targetPackageName = packageName.orEmpty()
    val noAutoCorrect = (inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0
    var flags = if (
        (inputType and InputType.TYPE_MASK_VARIATION) ==
        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
    ) {
        AutocorrectEditorFlags.WEB_FIELD
    } else {
        0
    }

    // Normalize the few source-audited compatibility cases without exposing app identity.
    when {
        targetPackageName == "org.mozilla.firefox" ->
            flags = flags or AutocorrectEditorFlags.WEB_FIELD
        targetPackageName.startsWith("com.replit") && noAutoCorrect ->
            flags = flags or AutocorrectEditorFlags.CODE_LIKE
    }
    return flags
}

private class ExternalAutocorrectCandidate(
    val pluginSessionId: Long,
    val pluginCandidateId: String,
    val editorGeneration: Long,
    override val isVisible: Boolean,
    delegate: WordSuggestionCandidate,
    override val replacement: SuggestionReplacement?,
    override val separatorBehavior: SuggestionSeparatorBehavior,
) : SuggestionCandidate by delegate
