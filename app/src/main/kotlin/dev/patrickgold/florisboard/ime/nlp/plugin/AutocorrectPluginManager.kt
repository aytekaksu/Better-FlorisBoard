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
import dev.patrickgold.florisboard.ime.keyboard.codePointCaseAndBaseVariants
import dev.patrickgold.florisboard.ime.keyboard.isAutocorrectTraceInput
import dev.patrickgold.florisboard.ime.text.keyboard.AutocorrectInputLayoutSnapshot
import dev.patrickgold.florisboard.ime.text.keyboard.isTraceCompatibleWith
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.lowercase
import dev.patrickgold.florisboard.lib.uppercase
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.autocorrect.api.AutocorrectAcceptanceKind
import org.florisboard.autocorrect.api.AutocorrectCandidate
import org.florisboard.autocorrect.api.AutocorrectCandidateKind
import org.florisboard.autocorrect.api.AutocorrectCapsMode
import org.florisboard.autocorrect.api.AutocorrectEditorFlags
import org.florisboard.autocorrect.api.AutocorrectInputTrace
import org.florisboard.autocorrect.api.AutocorrectInputMode
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
import org.florisboard.autocorrect.api.cancellationBundle
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
import org.florisboard.autocorrect.host.core.ConnectionLossKind
import org.florisboard.autocorrect.host.core.MonotonicMillis
import org.florisboard.autocorrect.host.core.ReplyRejectionReason
import org.florisboard.autocorrect.host.core.SessionConfiguration
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

private class AutocorrectProviderSuggestionResult(
    val requestId: Long,
    val result: AutocorrectSuggestionResult,
    val wireContent: EditorContent,
)

internal class AutocorrectWireRequest(
    val content: EditorContent,
    val request: AutocorrectRequest,
)

internal class PluginUiPickerLease(
    val id: Long,
    val providerId: String,
    val lifecycleRevision: Long,
)

internal fun isCurrentPluginUiPickerLease(
    lease: PluginUiPickerLease,
    activeLeaseIds: Set<Long>,
    selectedProviderId: String,
    boundProviderId: String,
    lifecycleRevision: Long,
) = lease.id in activeLeaseIds &&
    lease.lifecycleRevision == lifecycleRevision &&
    lease.providerId.isNotBlank() &&
    lease.providerId == selectedProviderId &&
    lease.providerId == boundProviderId

internal fun isCurrentEditorRequest(
    requestEditorGeneration: Long,
    activeEditorGeneration: Long,
) = requestEditorGeneration == activeEditorGeneration

internal fun isCurrentAutocorrectCandidate(
    candidateSessionId: Long,
    candidateRequestId: Long,
    candidateEditorGeneration: Long,
    activeSessionId: Long?,
    admittedSessionId: Long,
    latestRequestId: Long,
    activeEditorGeneration: Long,
    providerMatches: Boolean,
) = providerMatches &&
    candidateSessionId == activeSessionId &&
    candidateSessionId == admittedSessionId &&
    candidateRequestId == latestRequestId &&
    candidateEditorGeneration == activeEditorGeneration

internal fun predictionCodePointVariants(
    codePoints: Set<Int>,
    subtype: Subtype,
): Set<Int> {
    if (codePoints.isEmpty()) return emptySet()
    return buildSet {
        for (codePoint in codePoints) {
            if (!Character.isValidCodePoint(codePoint)) continue
            addAll(codePointCaseAndBaseVariants(codePoint))
            val text = String(Character.toChars(codePoint))
            sequenceOf(
                text.lowercase(subtype.primaryLocale),
                text.uppercase(subtype.primaryLocale),
                text.uppercase(subtype.primaryLocale).lowercase(subtype.primaryLocale),
            ).forEach { variant ->
                if (variant.codePointCount(0, variant.length) == 1) {
                    addAll(codePointCaseAndBaseVariants(variant.codePointAt(0)))
                }
            }
        }
    }
}

internal fun hasDictionaryMutationAccess(
    uiClientCount: Int,
    selectedProviderId: String,
    providerId: String,
    grantProviderId: String?,
) = uiClientCount > 0 &&
    providerId == selectedProviderId &&
    grantProviderId == providerId

internal data class PredictionHintLease(
    val requestId: Long,
    val codePoints: Set<Int>,
) {
    companion object {
        val Empty = PredictionHintLease(-1L, emptySet())
    }
}

internal fun isPredictionHintLeaseCurrent(
    lease: PredictionHintLease?,
    latestRequestId: Long,
) = lease == null || lease.requestId == latestRequestId

internal fun EditorContent.buildAutocorrectWireRequest(
    sessionId: Long,
    requestId: Long,
    maxCandidateCount: Int,
    allowPossiblyOffensive: Boolean,
    inputTrace: AutocorrectInputTrace = AutocorrectInputTrace.Empty,
    capsMode: AutocorrectCapsMode = AutocorrectCapsMode.UNSPECIFIED,
): AutocorrectWireRequest? {
    val cursor = localSelection.start.takeIf {
        localSelection.isCursorMode && it in 0..text.length
    } ?: return null
    var windowStart =
        (cursor - AutocorrectPluginContract.MAX_CONTEXT_CHARS).coerceAtLeast(0)
    if (
        windowStart > 0 &&
        windowStart < text.length &&
        Character.isLowSurrogate(text[windowStart]) &&
        Character.isHighSurrogate(text[windowStart - 1])
    ) {
        windowStart++
    }
    var windowEnd = minOf(
        text.length,
        maxOf(
            cursor,
            windowStart + AutocorrectPluginContract.MAX_CONTEXT_CHARS,
        ),
    )
    if (
        windowEnd > windowStart &&
        windowEnd < text.length &&
        Character.isHighSurrogate(text[windowEnd - 1]) &&
        Character.isLowSurrogate(text[windowEnd])
    ) {
        windowEnd--
    }
    fun EditorRange.inWindow() = takeIf {
        isValid && start >= windowStart && end <= windowEnd
    }?.translatedBy(-windowStart) ?: EditorRange.Unspecified

    val wireContent = EditorContent(
        text = text.substring(windowStart, windowEnd),
        offset = offset.coerceAtLeast(0) + windowStart,
        localSelection = EditorRange.cursor(
            (cursor - windowStart).coerceIn(0, windowEnd - windowStart),
        ),
        localComposing = localComposing.inWindow(),
        localCurrentWord = localCurrentWord.inWindow(),
    )
    return AutocorrectWireRequest(
        content = wireContent,
        request = AutocorrectRequest(
            sessionId = sessionId,
            requestId = requestId,
            text = wireContent.text,
            selectionStart = wireContent.localSelection.start,
            selectionEnd = wireContent.localSelection.end,
            composingStart = wireContent.localComposing.start,
            composingEnd = wireContent.localComposing.end,
            currentWordStart = wireContent.localCurrentWord.start,
            currentWordEnd = wireContent.localCurrentWord.end,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            inputTrace = inputTrace,
            capsMode = capsMode,
        ),
    )
}

/**
 * Discovers and talks to a user-selected external autocorrect service.
 *
 * This manager never starts a service. It binds while typing, provider UI, or ordered
 * session-finalization work needs the service and releases the binding when that demand ends.
 */
class AutocorrectPluginManager(context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.external-autocorrect"
    }

    private val appContext by context.appContext()
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextId = AtomicLong(1L)
    private val diagnostics = AutocorrectPluginDiagnostics()
    private val suggestionRequestCoordinator = AutocorrectSuggestionRequestCoordinator()
    private val pendingSuggestions =
        ConcurrentHashMap<Long, CompletableDeferred<AutocorrectSuggestionResult>>()
    private val pendingRemovals = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val pendingSessionFinishes = mutableSetOf<Long>()
    private val pendingHostSettingValues =
        ConcurrentHashMap<AutocorrectPluginHostSetting, Boolean>()
    private val pendingPluginUiOperations = mutableSetOf<Long>()
    private val pendingPluginUiDocumentOperations = mutableSetOf<Long>()
    private val pendingDictionaryMutationActions =
        mutableMapOf<Long, String>()
    private val hostSettingMutationGuard = Mutex()
    private val providerQueryGuard = Mutex()
    private val userDictionaryRequestGuard = Mutex()
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

    internal fun diagnosticsSnapshot() = diagnostics.snapshot()

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
    private val activePluginUiPickerLeaseIds = mutableSetOf<Long>()
    private var pluginUiLifecycleRevision = 0L
    private var preparingPluginUiDocuments = 0
    private val pendingPluginUiDocumentJobs = mutableSetOf<Job>()
    private var pendingProviderBind: AutocorrectPluginDescriptor? = null
    private var serviceConnection: ServiceConnection? = null
    private val tracePoints = mutableListOf<AutocorrectTouchPoint>()
    private var traceInputLayout: AutocorrectInputLayoutSnapshot? = null
    private var isInputTraceInvalid = false
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
        if (uiClientCount == 0) return
        uiClientCount--
        closePluginUiIfIdle()
    }

    @Synchronized
    internal fun acquirePluginUiPickerLease(): PluginUiPickerLease? {
        val providerId = prefs.suggestion.autocorrectPluginComponent.get()
        if (
            uiClientCount == 0 ||
            providerId.isBlank() ||
            providerId != boundProviderId ||
            remote == null
        ) {
            return null
        }
        val lease = PluginUiPickerLease(
            id = nextId.getAndIncrement(),
            providerId = providerId,
            lifecycleRevision = pluginUiLifecycleRevision,
        )
        activePluginUiPickerLeaseIds.add(lease.id)
        return lease
    }

    @Synchronized
    internal fun releasePluginUiPickerLease(lease: PluginUiPickerLease) {
        if (!activePluginUiPickerLeaseIds.remove(lease.id)) return
        closePluginUiIfIdle()
    }

    @Synchronized
    internal fun reportPluginUiFailure() {
        _pluginUiError.value = true
        _pluginUiLoading.value = false
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
    internal fun sendPluginUiDocument(
        itemId: String,
        uri: Uri,
        write: Boolean,
        pickerLease: PluginUiPickerLease,
    ) {
        val expectedProviderId = pickerLease.providerId
        if (
            remote == null ||
            !isCurrentPluginUiPickerLease(
                lease = pickerLease,
                activeLeaseIds = activePluginUiPickerLeaseIds,
                selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get(),
                boundProviderId = boundProviderId,
                lifecycleRevision = pluginUiLifecycleRevision,
            )
        ) {
            _pluginUiError.value = true
            return
        }
        activePluginUiPickerLeaseIds.remove(pickerLease.id)
        preparingPluginUiDocuments++
        val uiLifecycleRevision = pickerLease.lifecycleRevision
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
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
                            expectedUiLifecycleRevision = uiLifecycleRevision,
                            isDocumentOperation = true,
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
                if (!sent) {
                    synchronized(this@AutocorrectPluginManager) {
                        if (pluginUiLifecycleRevision == uiLifecycleRevision) {
                            _pluginUiError.value = true
                        }
                    }
                }
            } finally {
                finishPluginUiDocumentPreparation(uiLifecycleRevision)
            }
        }
        pendingPluginUiDocumentJobs.add(job)
        job.invokeOnCompletion {
            synchronized(this) {
                pendingPluginUiDocumentJobs.remove(job)
            }
        }
        job.start()
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

    @Synchronized
    internal fun leaseBoostedCodePoints(): PredictionHintLease {
        val accessibility = appContext.getSystemService(AccessibilityManager::class.java)
        val session = activeSession
        val selectedProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        val editorInfo = editorInstance.activeInfo
        val isEligible =
            accessibility?.isTouchExplorationEnabled != true &&
                prefs.suggestion.enabled.get() &&
                editorInfo.inputAttributes.allowsAutocorrectPluginSession(
                    isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                    isRawInputEditor = editorInfo.isRawInputEditor,
                ) &&
                session != null &&
                admittedSessionId == session.sessionId &&
                selectedProviderId == activeProviderId &&
                activeProviderId == boundProviderId &&
                remote != null
        if (!isEligible) {
            consumePredictionHints()
            return PredictionHintLease.Empty
        }
        val requestId = latestSuggestionRequestId
        if (requestId < 0L) return PredictionHintLease.Empty
        return PredictionHintLease(
            requestId = requestId,
            codePoints = predictionCodePointVariants(
                boostedCodePoints,
                subtypeManager.activeSubtype,
            ),
        )
    }

    @Synchronized
    internal fun consumePredictionHints(lease: PredictionHintLease? = null) {
        if (!isPredictionHintLeaseCurrent(lease, latestSuggestionRequestId)) return
        boostedCodePoints = emptySet()
        val requestId = latestSuggestionRequestId.takeIf { it >= 0L } ?: return
        latestSuggestionRequestId = -1L
        val pending = pendingSuggestions.remove(requestId) ?: return
        pending.cancel()
        send(AutocorrectPluginContract.MSG_CANCEL, cancellationBundle(requestId))
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
        expectedUiLifecycleRevision: Long? = null,
        isDocumentOperation: Boolean = false,
        data: (Long) -> Bundle,
    ): Long? {
        val isCurrentUiLifecycle =
            expectedUiLifecycleRevision == null ||
                expectedUiLifecycleRevision == pluginUiLifecycleRevision
        val service = remote?.takeIf {
            isCurrentUiLifecycle &&
                (
                    uiClientCount > 0 ||
                        (isDocumentOperation && preparingPluginUiDocuments > 0)
                    ) &&
                expectedProviderId.isNotBlank() &&
                expectedProviderId == boundProviderId &&
                expectedProviderId == prefs.suggestion.autocorrectPluginComponent.get()
        } ?: run {
            if (isCurrentUiLifecycle) _pluginUiError.value = true
            return null
        }
        val requestId = nextId.getAndIncrement()
        latestPluginUiRequestId = requestId
        _pluginUiError.value = false
        _pluginUiLoading.value = true
        pendingPluginUiOperations.add(requestId)
        if (isDocumentOperation) {
            pendingPluginUiDocumentOperations.add(requestId)
        }
        if (what == AutocorrectPluginContract.MSG_INVOKE_PLUGIN_UI_ACTION) {
            pendingDictionaryMutationActions[requestId] = expectedProviderId
        }
        if (!send(what, data(requestId), service)) {
            pendingPluginUiOperations.remove(requestId)
            pendingPluginUiDocumentOperations.remove(requestId)
            pendingDictionaryMutationActions.remove(requestId)
            _pluginUiError.value = true
            _pluginUiLoading.value = false
            return null
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

    @Synchronized
    private fun finishPluginUiOperation(requestId: Long): Boolean {
        if (!pendingPluginUiOperations.remove(requestId)) return false
        pendingPluginUiDocumentOperations.remove(requestId)
        val wasLatest = latestPluginUiRequestId == requestId
        if (wasLatest) {
            _pluginUiLoading.value = false
        }
        if (!closePluginUiIfIdle()) releaseBindingIfIdle()
        return wasLatest
    }

    @Synchronized
    private fun finishPluginUiDocumentPreparation(uiLifecycleRevision: Long) {
        if (pluginUiLifecycleRevision != uiLifecycleRevision) return
        preparingPluginUiDocuments = (preparingPluginUiDocuments - 1).coerceAtLeast(0)
        if (!closePluginUiIfIdle()) releaseBindingIfIdle()
    }

    private fun invalidatePluginUiDocuments() {
        pluginUiLifecycleRevision++
        activePluginUiPickerLeaseIds.clear()
        preparingPluginUiDocuments = 0
        pendingPluginUiDocumentOperations.forEach(pendingPluginUiOperations::remove)
        pendingPluginUiDocumentOperations.clear()
        val jobs = pendingPluginUiDocumentJobs.toList()
        pendingPluginUiDocumentJobs.clear()
        jobs.forEach(Job::cancel)
    }

    private fun clearPendingPluginUiOperations() {
        pendingPluginUiOperations.clear()
        pendingPluginUiDocumentOperations.clear()
        pendingDictionaryMutationActions.clear()
    }

    private fun hasPluginUiDemand() =
        uiClientCount > 0 || activePluginUiPickerLeaseIds.isNotEmpty() ||
            preparingPluginUiDocuments > 0 || pendingPluginUiDocumentOperations.isNotEmpty()

    private fun closePluginUiIfIdle(): Boolean {
        if (hasPluginUiDemand()) return false
        invalidatePluginUiDocuments()
        clearPendingPluginUiOperations()
        send(AutocorrectPluginContract.MSG_PLUGIN_UI_CLOSED, Bundle())
        providerPluginUi = null
        _pluginUi.value = null
        _pluginUiLoading.value = false
        releaseBindingIfIdle()
        return true
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
        val hasDemand = activeSession != null || hasPluginUiDemand()
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
    internal fun recordInputTouch(
        data: KeyData,
        inputLayout: AutocorrectInputLayoutSnapshot,
        x: Float,
        y: Float,
        isPrivateSession: Boolean,
    ) {
        if (isInputTraceInvalid) return
        val width = inputLayout.width
        val height = inputLayout.height
        if (
            !prefs.suggestion.enabled.get() ||
            prefs.suggestion.autocorrectPluginComponent.get().isBlank() ||
            !editorInstance.activeInfo.inputAttributes.allowsAutocorrectPluginSession(
                isPrivateSession = isPrivateSession,
                isRawInputEditor = editorInstance.activeInfo.isRawInputEditor,
            ) ||
            !data.isAutocorrectTraceInput(inputLayout.mode) ||
            width <= 0f ||
            height <= 0f
        ) {
            return
        }
        val text = data.asString(isForDisplay = false).takeIf { it.isNotBlank() } ?: return
        val previousLayout = traceInputLayout
        if (previousLayout != null && !previousLayout.isTraceCompatibleWith(inputLayout)) {
            invalidateInputTrace()
            return
        }
        if (previousLayout == null) traceInputLayout = inputLayout
        if (tracePoints.size < AutocorrectPluginContract.MAX_TRACE_POINT_COUNT) {
            tracePoints.add(AutocorrectTouchPoint(text, x / width, y / height))
        }
    }

    @Synchronized
    internal fun onInputLayoutChanged(inputLayout: AutocorrectInputLayoutSnapshot) {
        traceInputLayout?.let { previousLayout ->
            if (previousLayout.isTraceCompatibleWith(inputLayout)) {
                traceInputLayout = inputLayout
            } else {
                invalidateInputTrace()
            }
        }
    }

    @Synchronized
    internal fun invalidateInputTrace() {
        resetInputTrace()
        isInputTraceInvalid = true
    }

    @Synchronized
    fun clearInputTrace() {
        resetInputTrace()
        isInputTraceInvalid = false
    }

    private fun resetInputTrace() {
        tracePoints.clear()
        traceInputLayout = null
        consumePredictionHints()
    }

    @Synchronized
    private fun inputTraceFor(content: EditorContent): AutocorrectInputTrace {
        if (isInputTraceInvalid) return AutocorrectInputTrace.Empty
        val points = tracePoints.toList()
        if (!traceTextMatches(points, content.currentWordText)) {
            clearInputTrace()
            return AutocorrectInputTrace.Empty
        }
        return AutocorrectInputTrace(traceInputLayout?.keys.orEmpty(), points)
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
        diagnostics.record(
            AutocorrectPluginDiagnosticEvent.Session(
                bindingEpoch = providerBindingEpoch,
                sessionId = AutocorrectPluginDiagnosticId.fromHostId(session.sessionId),
                state = AutocorrectPluginDiagnosticState.STARTED,
                error = AutocorrectPluginDiagnosticError.NONE,
            ),
        )
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
        if (admittedSessionId == session.sessionId) return
        if (!send(AutocorrectPluginContract.MSG_START_SESSION, session.toBundle(), service)) {
            diagnostics.record(
                AutocorrectPluginDiagnosticEvent.Session(
                    bindingEpoch = providerBindingEpoch,
                    sessionId = AutocorrectPluginDiagnosticId.fromHostId(session.sessionId),
                    state = AutocorrectPluginDiagnosticState.FAILED,
                    error = AutocorrectPluginDiagnosticError.SEND_FAILED,
                ),
            )
            return
        }
        admittedSessionId = session.sessionId
        suggestionRequestCoordinator.admitSession(
            providerId = boundProviderId,
            bindingEpoch = providerBindingEpoch,
            sessionId = session.sessionId,
            editorGeneration = editorGeneration,
            configuration = session.toHostSessionConfiguration(),
        )
        connectionReady.complete(service)
        diagnostics.record(
            AutocorrectPluginDiagnosticEvent.Session(
                bindingEpoch = providerBindingEpoch,
                sessionId = AutocorrectPluginDiagnosticId.fromHostId(session.sessionId),
                state = AutocorrectPluginDiagnosticState.SUCCEEDED,
                error = AutocorrectPluginDiagnosticError.NONE,
            ),
        )
    }

    @Synchronized
    internal fun captureEditorGeneration() = editorGeneration

    @Synchronized
    internal fun isCurrentEditorGeneration(generation: Long) = generation == editorGeneration

    @Synchronized
    fun finishSession() {
        editorGeneration++
        finishCurrentSession()
    }

    @Synchronized
    fun onSelectedProviderChanged() {
        editorGeneration++
        endSession()
        if (hasPluginUiDemand()) {
            send(AutocorrectPluginContract.MSG_PLUGIN_UI_CLOSED, Bundle())
        }
        providerPluginUi = null
        _pluginUi.value = null
        pendingHostSettingValues.clear()
        unbind(clearSession = true)
        if (uiClientCount > 0) {
            _pluginUiError.value = false
            _pluginUiLoading.value = true
            connectPluginUi()
        }
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
            diagnostics.operationStarted(
                operation = AutocorrectPluginDiagnosticOperation.FINISH_SESSION,
                bindingEpoch = providerBindingEpoch,
                sessionId = session.sessionId,
            )
            if (!send(
                what = AutocorrectPluginContract.MSG_FINISH_SESSION,
                data = finishSessionBundle(session.sessionId, finalRequest),
            )) {
                if (pendingSessionFinishes.remove(session.sessionId)) {
                    diagnostics.operationFinished(
                        operation = AutocorrectPluginDiagnosticOperation.FINISH_SESSION,
                        bindingEpoch = providerBindingEpoch,
                        sessionId = session.sessionId,
                        state = AutocorrectPluginDiagnosticState.FAILED,
                        error = AutocorrectPluginDiagnosticError.SEND_FAILED,
                    )
                }
            }
        }
        if (session?.sessionId == admittedSessionId) admittedSessionId = -1L
        activeSession = null
        activeProviderId = ""
        if (session != null) connectionReady.complete(null)
        suggestionRequestCoordinator.endSession(editorGeneration)
        latestSuggestionRequestId = -1L
        clearInputTrace()
        cancelPending(session?.sessionId ?: 0L)
        if (session != null) {
            diagnostics.record(
                AutocorrectPluginDiagnosticEvent.Session(
                    bindingEpoch = providerBindingEpoch,
                    sessionId = AutocorrectPluginDiagnosticId.fromHostId(session.sessionId),
                    state = AutocorrectPluginDiagnosticState.CLEARED,
                    error = AutocorrectPluginDiagnosticError.NONE,
                ),
            )
        }
        return session != null
    }

    private fun buildFinalRequest(session: AutocorrectSession): AutocorrectRequest {
        val content = editorInstance.activeContent.takeIf {
            it.localSelection.isCursorMode &&
                it.localSelection.start in 0..it.text.length
        } ?: EditorContent.selectionOnly(EditorRange.cursor(0))
        return checkNotNull(
            content.buildAutocorrectWireRequest(
                sessionId = session.sessionId,
                requestId = nextId.getAndIncrement(),
                maxCandidateCount = 1,
                allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                capsMode = keyboardManager.activeState.inputShiftState.toAutocorrectCapsMode(),
            ),
        ).request
    }

    private fun wantsProvider(providerId: String) =
        providerId.isNotBlank() &&
            ((activeSession != null && activeProviderId == providerId) ||
                (hasPluginUiDemand() &&
                    prefs.suggestion.autocorrectPluginComponent.get() == providerId))

    @Synchronized
    private fun completeSessionFinish(sessionId: Long) {
        if (!pendingSessionFinishes.remove(sessionId)) {
            diagnostics.record(
                AutocorrectPluginDiagnosticEvent.ReplyRejected(
                    bindingEpoch = providerBindingEpoch,
                    operation = AutocorrectPluginDiagnosticOperation.FINISH_SESSION,
                    error = AutocorrectPluginDiagnosticError.UNKNOWN_REQUEST,
                ),
            )
            return
        }
        diagnostics.operationFinished(
            operation = AutocorrectPluginDiagnosticOperation.FINISH_SESSION,
            bindingEpoch = providerBindingEpoch,
            sessionId = sessionId,
            state = AutocorrectPluginDiagnosticState.ACKNOWLEDGED,
        )
        if (pendingSessionFinishes.isNotEmpty()) return
        releaseBindingIfIdle()
    }

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
        return suggestEligibleContent(
            subtype = subtype,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            isPrivateSession = isPrivateSession,
            requestEditorGeneration = requestEditorGeneration,
            inputTrace = { inputTraceFor(content) },
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
            inputTrace.mode != AutocorrectInputMode.GESTURE ||
            inputTrace.gesturePoints.size < 2
        ) {
            return AutocorrectPluginSuggestionBatch(emptyList(), handled = false)
        }
        return suggestEligibleContent(
            subtype = subtype,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            isPrivateSession = isPrivateSession,
            requestEditorGeneration = requestEditorGeneration,
            inputTrace = { inputTrace },
        )
    }

    private suspend fun suggestEligibleContent(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
        requestEditorGeneration: Long,
        inputTrace: () -> AutocorrectInputTrace,
    ): AutocorrectPluginSuggestionBatch {
        val unhandled = AutocorrectPluginSuggestionBatch(emptyList(), handled = false)
        if (
            !content.localSelection.isCursorMode ||
            content.localSelection.start !in 0..content.text.length
        ) {
            return unhandled
        }
        val session = ensureSession(
            subtype,
            editorInstance.activeInfo,
            isPrivateSession,
            requestEditorGeneration,
        ) ?: return unhandled
        val response = requestCandidates(
            session = session,
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            inputTrace = inputTrace(),
        ) ?: return unhandled
        return AutocorrectPluginSuggestionBatch(
            candidates = response.result.candidates.mapNotNull {
                it.toSuggestionCandidate(
                    sessionId = session.sessionId,
                    requestId = response.requestId,
                    wireContent = response.wireContent,
                    originContent = content,
                )
            },
            handled = response.result.handled,
        )
    }

    private suspend fun requestCandidates(
        session: AutocorrectSession,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        inputTrace: AutocorrectInputTrace,
    ): AutocorrectProviderSuggestionResult? {
        val service = awaitProviderResult(connectionReady) ?: return null

        val (requestId, deferred, wireContent) = synchronized(this) {
            if (
                activeSession?.sessionId != session.sessionId ||
                admittedSessionId != session.sessionId ||
                remote !== service ||
                boundProviderId != activeProviderId
            ) {
                return null
            }
            val admission = suggestionRequestCoordinator.issueRequest(
                editorGeneration = editorGeneration,
                at = monotonicNow(),
            )
            val admitted = admission as? SuggestionRequestAdmission.Admitted ?: return null
            val requestId = admitted.lease.requestId.value
            val wireRequest = content.buildAutocorrectWireRequest(
                sessionId = session.sessionId,
                requestId = requestId,
                maxCandidateCount = maxCandidateCount,
                allowPossiblyOffensive = allowPossiblyOffensive,
                inputTrace = inputTrace,
                capsMode = keyboardManager.activeState.inputShiftState.toAutocorrectCapsMode(),
            ) ?: run {
                suggestionRequestCoordinator.cancelRequest(requestId)
                return null
            }
            latestSuggestionRequestId = requestId
            boostedCodePoints = emptySet()
            admitted.cancelledLeases.forEach { cancelledLease ->
                val cancelledRequestId = cancelledLease.requestId.value
                pendingSuggestions.remove(cancelledRequestId)?.let { previous ->
                    previous.cancel()
                    diagnostics.operationFinished(
                        operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                        bindingEpoch = providerBindingEpoch,
                        sessionId = session.sessionId,
                        requestId = cancelledRequestId,
                        state = AutocorrectPluginDiagnosticState.CANCELLED,
                        error = AutocorrectPluginDiagnosticError.SUPERSEDED,
                    )
                }
                send(
                    AutocorrectPluginContract.MSG_CANCEL,
                    cancellationBundle(cancelledRequestId),
                    service,
                )
            }
            val deferred = CompletableDeferred<AutocorrectSuggestionResult>()
            pendingSuggestions[requestId] = deferred
            diagnostics.operationStarted(
                operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                bindingEpoch = providerBindingEpoch,
                sessionId = session.sessionId,
                requestId = requestId,
            )
            if (
                !send(
                    AutocorrectPluginContract.MSG_SUGGEST,
                    wireRequest.request.toBundle(),
                    service,
                )
            ) {
                suggestionRequestCoordinator.requestSendFailed(
                    lease = admitted.lease,
                    at = monotonicNow(),
                )
                val wasPending = pendingSuggestions.remove(requestId) != null
                if (latestSuggestionRequestId == requestId) {
                    latestSuggestionRequestId = -1L
                }
                if (wasPending) {
                    diagnostics.operationFinished(
                        operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                        bindingEpoch = providerBindingEpoch,
                        sessionId = session.sessionId,
                        requestId = requestId,
                        state = AutocorrectPluginDiagnosticState.FAILED,
                        error = AutocorrectPluginDiagnosticError.SEND_FAILED,
                    )
                }
                return null
            }
            Triple(requestId, deferred, wireRequest.content)
        }
        val result = try {
            awaitProviderResult(deferred)
        } finally {
            if (pendingSuggestions.remove(requestId, deferred)) {
                suggestionRequestCoordinator.cancelRequest(requestId)
                synchronized(this) {
                    if (
                        activeSession?.sessionId == session.sessionId &&
                        requestId == latestSuggestionRequestId
                    ) {
                        latestSuggestionRequestId = -1L
                        boostedCodePoints = emptySet()
                        send(
                            AutocorrectPluginContract.MSG_CANCEL,
                            cancellationBundle(requestId),
                            service,
                        )
                        diagnostics.operationFinished(
                            operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                            bindingEpoch = providerBindingEpoch,
                            sessionId = session.sessionId,
                            requestId = requestId,
                            state = AutocorrectPluginDiagnosticState.CANCELLED,
                        )
                    }
                }
            }
        }
        return result?.let {
            AutocorrectProviderSuggestionResult(
                requestId = requestId,
                result = it.copy(
                    candidates = it.candidates.take(
                        maxCandidateCount.coerceIn(
                            1,
                            AutocorrectPluginContract.MAX_CANDIDATES,
                        ),
                    ),
                ),
                wireContent = wireContent,
            )
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        notifySuggestionAccepted(candidate, AutocorrectAcceptanceKind.MANUAL)
    }

    @Synchronized
    fun canCommitCandidate(candidate: SuggestionCandidate): Boolean {
        if (candidate !is ExternalAutocorrectCandidate) return true
        return isCurrentAutocorrectCandidate(
            candidateSessionId = candidate.pluginSessionId,
            candidateRequestId = candidate.pluginRequestId,
            candidateEditorGeneration = candidate.editorGeneration,
            activeSessionId = activeSession?.sessionId,
            admittedSessionId = admittedSessionId,
            latestRequestId = latestSuggestionRequestId,
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
            diagnostics.operationStarted(
                operation = AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE,
                bindingEpoch = providerBindingEpoch,
                sessionId = session.sessionId,
                requestId = requestId,
            )
            if (!send(
                    AutocorrectPluginContract.MSG_REMOVE,
                    removalRequestBundle(session.sessionId, requestId, candidate.pluginCandidateId),
                    service,
                )
            ) {
                if (pendingRemovals.remove(requestId) != null) {
                    diagnostics.operationFinished(
                        operation = AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE,
                        bindingEpoch = providerBindingEpoch,
                        sessionId = session.sessionId,
                        requestId = requestId,
                        state = AutocorrectPluginDiagnosticState.FAILED,
                        error = AutocorrectPluginDiagnosticError.SEND_FAILED,
                    )
                }
                return false
            }
            requestId to deferred
        }
        return try {
            awaitProviderResult(deferred) ?: false
        } finally {
            if (pendingRemovals.remove(requestId, deferred)) {
                diagnostics.operationFinished(
                    operation = AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE,
                    bindingEpoch = providerBindingEpoch,
                    requestId = requestId,
                    state = AutocorrectPluginDiagnosticState.CANCELLED,
                )
            }
        }
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> = emptyList()

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double = 0.0

    override suspend fun destroy() {
        synchronized(this) {
            _keyboardUiVisible.value = false
            if (hasPluginUiDemand()) {
                send(AutocorrectPluginContract.MSG_PLUGIN_UI_CLOSED, Bundle())
            }
            uiClientCount = 0
            invalidatePluginUiDocuments()
            clearPendingPluginUiOperations()
            pendingSessionFinishes.clear()
            pendingHostSettingValues.clear()
            providerPluginUi = null
            _pluginUi.value = null
            _pluginUiLoading.value = false
            _pluginUiError.value = false
            editorGeneration++
            endSession()
            unbind(clearSession = true)
        }
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

    private fun recordBinding(
        state: AutocorrectPluginDiagnosticState,
        error: AutocorrectPluginDiagnosticError = AutocorrectPluginDiagnosticError.NONE,
        bindingEpoch: Long = providerBindingEpoch,
    ) {
        diagnostics.record(
            AutocorrectPluginDiagnosticEvent.Binding(
                bindingEpoch = bindingEpoch,
                state = state,
                error = error,
            ),
        )
    }

    @Synchronized
    private fun attach(connection: ServiceConnection, binder: IBinder) {
        if (serviceConnection !== connection) {
            recordBinding(
                state = AutocorrectPluginDiagnosticState.REJECTED,
                error = AutocorrectPluginDiagnosticError.STALE_BINDING,
            )
            return
        }
        val service = Messenger(binder)
        remote = service
        recordBinding(state = AutocorrectPluginDiagnosticState.CONNECTED)
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
        recordBinding(
            state = AutocorrectPluginDiagnosticState.DISCONNECTED,
            error = AutocorrectPluginDiagnosticError.SERVICE_DISCONNECTED,
        )
        suggestionRequestCoordinator.connectionLost(
            kind = ConnectionLossKind.SERVICE_DISCONNECTED,
            at = monotonicNow(),
            editorGeneration = editorGeneration,
        )
        suspendConnection()
    }

    @Synchronized
    private fun handleDeadRemote(service: Messenger) {
        if (remote !== service) return
        recordBinding(
            state = AutocorrectPluginDiagnosticState.FAILED,
            error = AutocorrectPluginDiagnosticError.DEAD_REMOTE,
        )
        suggestionRequestCoordinator.connectionLost(
            kind = ConnectionLossKind.DEAD_REMOTE,
            at = monotonicNow(),
            editorGeneration = editorGeneration,
        )
        suspendConnection()
    }

    private fun suspendConnection() {
        val queuedProvider = pendingProviderBind.also { pendingProviderBind = null }
        pendingSessionFinishes.forEach { sessionId ->
            diagnostics.operationFinished(
                operation = AutocorrectPluginDiagnosticOperation.FINISH_SESSION,
                bindingEpoch = providerBindingEpoch,
                sessionId = sessionId,
                state = AutocorrectPluginDiagnosticState.FAILED,
                error = AutocorrectPluginDiagnosticError.NOT_CONNECTED,
            )
        }
        pendingSessionFinishes.clear()
        admittedSessionId = -1L
        remote = null
        boostedCodePoints = emptySet()
        invalidatePluginUiDocuments()
        clearPendingPluginUiOperations()
        connectionReady.complete(null)
        connectionReady = CompletableDeferred()
        failPending()
        when {
            queuedProvider != null && wantsProvider(queuedProvider.id) -> {
                unbind(clearSession = false)
                bind(queuedProvider)
            }
            wantsProvider(boundProviderId) -> {
                if (uiClientCount > 0) _pluginUiLoading.value = true
            }
            else -> {
                unbind(clearSession = false)
                if (activeSession != null || hasPluginUiDemand()) {
                    refreshProviders()
                }
            }
        }
    }

    @Synchronized
    private fun handleBindingDied(connection: ServiceConnection) {
        if (serviceConnection !== connection) return
        recordBinding(
            state = AutocorrectPluginDiagnosticState.FAILED,
            error = AutocorrectPluginDiagnosticError.BINDING_DIED,
        )
        suggestionRequestCoordinator.connectionLost(
            kind = ConnectionLossKind.BINDING_DIED,
            at = monotonicNow(),
            editorGeneration = editorGeneration,
        )
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
        recordBinding(
            state = AutocorrectPluginDiagnosticState.FAILED,
            error = AutocorrectPluginDiagnosticError.NULL_BINDING,
        )
        suggestionRequestCoordinator.connectionLost(
            kind = ConnectionLossKind.NULL_BINDING,
            at = monotonicNow(),
            editorGeneration = editorGeneration,
        )
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
            if (pendingSessionFinishes.isNotEmpty()) {
                pendingProviderBind = descriptor
                return
            }
            clearPendingPluginUiOperations()
            unbind(clearSession = false)
        }
        val connection = createServiceConnection()
        serviceConnection = connection
        boundProviderId = descriptor.id
        val bindingEpoch = ++providerBindingEpoch
        recordBinding(
            state = AutocorrectPluginDiagnosticState.STARTED,
            bindingEpoch = bindingEpoch,
        )
        replyMessenger = Messenger(ReplyHandler(descriptor.id, descriptor.uid, bindingEpoch))
        val intent = Intent(AutocorrectPluginContract.ACTION_BIND_PROVIDER)
            .setComponent(descriptor.componentName)
        bound = runCatching {
            traceAutocorrectPerformance(AutocorrectPerformanceSection.BIND) {
                appContext.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND,
                )
            }
        }.getOrDefault(false)
        if (!bound) {
            recordBinding(
                state = AutocorrectPluginDiagnosticState.FAILED,
                error = AutocorrectPluginDiagnosticError.BIND_REJECTED,
                bindingEpoch = bindingEpoch,
            )
            unbind(clearSession = activeSession != null)
        }
    }

    private fun send(
        what: Int,
        data: Bundle,
        service: Messenger? = remote,
    ): Boolean {
        service ?: return false
        return try {
            traceAutocorrectPerformance(AutocorrectPerformanceSection.SEND) {
                service.send(Message.obtain(null, what).apply {
                    this.data = data
                    replyTo = replyMessenger
                })
            }
            true
        } catch (error: RemoteException) {
            if (error is DeadObjectException) {
                handleDeadRemote(service)
            } else {
                recordBinding(
                    state = AutocorrectPluginDiagnosticState.FAILED,
                    error = AutocorrectPluginDiagnosticError.REMOTE_FAILURE,
                )
                suggestionRequestCoordinator.connectionLost(
                    kind = ConnectionLossKind.DEAD_REMOTE,
                    at = monotonicNow(),
                    editorGeneration = editorGeneration,
                )
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
        val disconnectedEpoch = providerBindingEpoch
        val hadConnection = connection != null || boundProviderId.isNotBlank()
        if (bound && connection != null) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        serviceConnection = null
        clearConnection(clearSession)
        if (hadConnection) {
            recordBinding(
                state = AutocorrectPluginDiagnosticState.DISCONNECTED,
                bindingEpoch = disconnectedEpoch,
            )
        }
    }

    private fun clearConnection(clearSession: Boolean) {
        val clearedEpoch = providerBindingEpoch
        providerBindingEpoch++
        remote = null
        boundProviderId = ""
        admittedSessionId = -1L
        pendingProviderBind = null
        pendingSessionFinishes.forEach { sessionId ->
            diagnostics.operationFinished(
                operation = AutocorrectPluginDiagnosticOperation.FINISH_SESSION,
                bindingEpoch = clearedEpoch,
                sessionId = sessionId,
                state = AutocorrectPluginDiagnosticState.CANCELLED,
                error = AutocorrectPluginDiagnosticError.NOT_CONNECTED,
            )
        }
        pendingSessionFinishes.clear()
        boostedCodePoints = emptySet()
        invalidatePluginUiDocuments()
        clearPendingPluginUiOperations()
        if (clearSession) {
            activeSession = null
            activeProviderId = ""
            providerPluginUi = null
            _pluginUi.value = null
        }
        if (uiClientCount > 0) _pluginUiLoading.value = false
        if (clearSession || activeSession == null) connectionReady.complete(null)
        suggestionRequestCoordinator.endSession(editorGeneration)
        failPending(bindingEpoch = clearedEpoch)
    }

    private fun cancelPending(sessionId: Long = activeSession?.sessionId ?: 0L) {
        latestSuggestionRequestId = -1L
        boostedCodePoints = emptySet()
        pendingSuggestions.keys.forEach { requestId ->
            diagnostics.operationFinished(
                operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                bindingEpoch = providerBindingEpoch,
                sessionId = sessionId,
                requestId = requestId,
                state = AutocorrectPluginDiagnosticState.CANCELLED,
            )
        }
        pendingSuggestions.values.forEach { it.cancel() }
        pendingSuggestions.clear()
        pendingRemovals.keys.forEach { requestId ->
            diagnostics.operationFinished(
                operation = AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE,
                bindingEpoch = providerBindingEpoch,
                sessionId = sessionId,
                requestId = requestId,
                state = AutocorrectPluginDiagnosticState.CANCELLED,
            )
        }
        pendingRemovals.values.forEach { it.cancel() }
        pendingRemovals.clear()
    }

    private fun failPending(bindingEpoch: Long = providerBindingEpoch) {
        latestSuggestionRequestId = -1L
        boostedCodePoints = emptySet()
        val sessionId = activeSession?.sessionId ?: 0L
        pendingSuggestions.keys.forEach { requestId ->
            diagnostics.operationFinished(
                operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                bindingEpoch = bindingEpoch,
                sessionId = sessionId,
                requestId = requestId,
                state = AutocorrectPluginDiagnosticState.FAILED,
                error = AutocorrectPluginDiagnosticError.NOT_CONNECTED,
            )
        }
        pendingSuggestions.values.forEach {
            it.complete(AutocorrectSuggestionResult.Unhandled)
        }
        pendingSuggestions.clear()
        pendingRemovals.keys.forEach { requestId ->
            diagnostics.operationFinished(
                operation = AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE,
                bindingEpoch = bindingEpoch,
                requestId = requestId,
                state = AutocorrectPluginDiagnosticState.FAILED,
                error = AutocorrectPluginDiagnosticError.NOT_CONNECTED,
            )
        }
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
        val result = cached?.let { Result.success(it) } ?: run {
            diagnostics.discoveryStarted()
            runCatching {
                discoverProviders()
            }.also { queryResult ->
                diagnostics.discoveryFinished(
                    providerCount = queryResult.getOrNull()?.size ?: 0,
                    error = if (queryResult.isSuccess) {
                        AutocorrectPluginDiagnosticError.NONE
                    } else {
                        AutocorrectPluginDiagnosticError.QUERY_FAILED
                    },
                )
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
        }.getOrNull() ?: run {
            diagnostics.record(
                AutocorrectPluginDiagnosticEvent.ReplyRejected(
                    bindingEpoch = bindingEpoch,
                    operation = AutocorrectPluginDiagnosticOperation.USER_DICTIONARY,
                    error = AutocorrectPluginDiagnosticError.MALFORMED_MESSAGE,
                ),
            )
            return
        }
        val replyTo = message.replyTo ?: run {
            diagnostics.record(
                AutocorrectPluginDiagnosticEvent.ReplyRejected(
                    bindingEpoch = bindingEpoch,
                    operation = AutocorrectPluginDiagnosticOperation.USER_DICTIONARY,
                    error = AutocorrectPluginDiagnosticError.MALFORMED_MESSAGE,
                ),
            )
            return
        }
        val providerBinder = replyTo.binder
        if (!isCurrentProviderBinding(providerId, bindingEpoch, providerBinder)) {
            diagnostics.record(
                AutocorrectPluginDiagnosticEvent.ReplyRejected(
                    bindingEpoch = bindingEpoch,
                    operation = AutocorrectPluginDiagnosticOperation.USER_DICTIONARY,
                    error = AutocorrectPluginDiagnosticError.STALE_BINDING,
                ),
            )
            return
        }
        diagnostics.operationStarted(
            operation = AutocorrectPluginDiagnosticOperation.USER_DICTIONARY,
            bindingEpoch = bindingEpoch,
            requestId = request.requestId,
        )
        scope.launch(Dispatchers.IO) {
            val result = userDictionaryRequestGuard.withLock {
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
            if (!isCurrentProviderBinding(providerId, bindingEpoch, providerBinder)) {
                diagnostics.operationFinished(
                    operation = AutocorrectPluginDiagnosticOperation.USER_DICTIONARY,
                    bindingEpoch = bindingEpoch,
                    requestId = request.requestId,
                    state = AutocorrectPluginDiagnosticState.REJECTED,
                    error = AutocorrectPluginDiagnosticError.STALE_BINDING,
                )
                return@launch
            }
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
                val error = when (result.status) {
                    AutocorrectUserDictionaryStatus.OK ->
                        AutocorrectPluginDiagnosticError.NONE
                    AutocorrectUserDictionaryStatus.DENIED ->
                        AutocorrectPluginDiagnosticError.ACCESS_DENIED
                    AutocorrectUserDictionaryStatus.INVALID ->
                        AutocorrectPluginDiagnosticError.INVALID_REQUEST
                    AutocorrectUserDictionaryStatus.UNAVAILABLE ->
                        AutocorrectPluginDiagnosticError.OPERATION_UNAVAILABLE
                }
                diagnostics.operationFinished(
                    operation = AutocorrectPluginDiagnosticOperation.USER_DICTIONARY,
                    bindingEpoch = bindingEpoch,
                    requestId = request.requestId,
                    state = if (error == AutocorrectPluginDiagnosticError.NONE) {
                        AutocorrectPluginDiagnosticState.SUCCEEDED
                    } else {
                        AutocorrectPluginDiagnosticState.REJECTED
                    },
                    itemCount = result.entries.size,
                    error = error,
                )
            } catch (_: RemoteException) {
                // The selected provider disappeared while its request was in flight.
                diagnostics.operationFinished(
                    operation = AutocorrectPluginDiagnosticOperation.USER_DICTIONARY,
                    bindingEpoch = bindingEpoch,
                    requestId = request.requestId,
                    state = AutocorrectPluginDiagnosticState.FAILED,
                    error = AutocorrectPluginDiagnosticError.REMOTE_FAILURE,
                )
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
                val grantedProviderId =
                    pendingDictionaryMutationActions[request.originUiRequestId]
                if (
                    hasDictionaryMutationAccess(
                        uiClientCount = uiClientCount,
                        selectedProviderId = selectedProviderId,
                        providerId = providerId,
                        grantProviderId = grantedProviderId,
                    )
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
                ?.takeIf { it == providerId }
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
                diagnostics.record(
                    AutocorrectPluginDiagnosticEvent.Discovery(
                        state = AutocorrectPluginDiagnosticState.REJECTED,
                        duration = AutocorrectPluginDiagnosticDuration.UNKNOWN,
                        providerCount = discoveredProviders.size,
                        error = AutocorrectPluginDiagnosticError.PROVIDER_NOT_FOUND,
                    ),
                )
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

    private suspend fun discoverProviders(): List<AutocorrectPluginDescriptor> =
        withContext(Dispatchers.IO) {
            traceAutocorrectPerformance(AutocorrectPerformanceSection.DISCOVER) {
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
                    if (
                        !serviceInfo.exported ||
                        protocolVersion != AutocorrectPluginContract.PROTOCOL_VERSION
                    ) {
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
        }

    private inner class ReplyHandler(
        private val providerId: String,
        private val providerUid: Int,
        private val bindingEpoch: Long,
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val rejectedBy = when {
                message.sendingUid != providerUid ->
                    AutocorrectPluginDiagnosticError.UNAUTHORIZED_SENDER
                bindingEpoch != providerBindingEpoch || providerId != boundProviderId ->
                    AutocorrectPluginDiagnosticError.STALE_BINDING
                remote == null -> AutocorrectPluginDiagnosticError.NOT_CONNECTED
                else -> null
            }
            if (rejectedBy != null) {
                diagnostics.record(
                    AutocorrectPluginDiagnosticEvent.ReplyRejected(
                        bindingEpoch = bindingEpoch,
                        operation = diagnosticOperationForMessage(message.what),
                        error = rejectedBy,
                    ),
                )
                return
            }
            when (message.what) {
                AutocorrectPluginContract.MSG_SUGGESTIONS -> {
                    val parsed = runCatching {
                        traceAutocorrectPerformance(
                            AutocorrectPerformanceSection.DECODE_REPLY,
                        ) {
                            suggestionResultFromBundle(message.data)
                        }
                    }.getOrElse {
                        diagnostics.record(
                            AutocorrectPluginDiagnosticEvent.ReplyRejected(
                                bindingEpoch = bindingEpoch,
                                operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                                error = AutocorrectPluginDiagnosticError.MALFORMED_MESSAGE,
                            ),
                        )
                        return
                    }
                    val (requestId, result) = parsed
                    val replyDecision = suggestionRequestCoordinator.acceptReply(
                        requestId = requestId,
                        at = monotonicNow(),
                    )
                    if (replyDecision !is SuggestionReplyDecision.Accept) {
                        pendingSuggestions.remove(requestId)?.cancel()
                        diagnostics.record(
                            AutocorrectPluginDiagnosticEvent.ReplyRejected(
                                bindingEpoch = bindingEpoch,
                                operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                                error = replyDecision.toDiagnosticError(),
                            ),
                        )
                        return
                    }
                    val pendingResult = synchronized(this@AutocorrectPluginManager) {
                        val pending = pendingSuggestions.remove(requestId)
                            ?: return@synchronized null
                        boostedCodePoints = result.boostedCodePoints.takeIf {
                            result.handled
                        }.orEmpty()
                        pending
                    }
                    if (pendingResult == null) {
                        diagnostics.record(
                            AutocorrectPluginDiagnosticEvent.ReplyRejected(
                                bindingEpoch = bindingEpoch,
                                operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                                error = AutocorrectPluginDiagnosticError.UNKNOWN_REQUEST,
                            ),
                        )
                        return
                    }
                    diagnostics.operationFinished(
                        operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                        bindingEpoch = bindingEpoch,
                        sessionId = replyDecision.lease.sessionId.value,
                        requestId = requestId,
                        state = AutocorrectPluginDiagnosticState.SUCCEEDED,
                        itemCount = result.candidates.size,
                    )
                    pendingResult.complete(result)
                }
                AutocorrectPluginContract.MSG_REMOVE_RESULT -> {
                    val parsed = runCatching {
                        removalResultFromBundle(message.data)
                    }.getOrElse {
                        diagnostics.record(
                            AutocorrectPluginDiagnosticEvent.ReplyRejected(
                                bindingEpoch = bindingEpoch,
                                operation = AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE,
                                error = AutocorrectPluginDiagnosticError.MALFORMED_MESSAGE,
                            ),
                        )
                        return
                    }
                    val (requestId, removed) = parsed
                    val pending = pendingRemovals.remove(requestId)
                    if (pending == null) {
                        diagnostics.record(
                            AutocorrectPluginDiagnosticEvent.ReplyRejected(
                                bindingEpoch = bindingEpoch,
                                operation = AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE,
                                error = AutocorrectPluginDiagnosticError.UNKNOWN_REQUEST,
                            ),
                        )
                        return
                    }
                    diagnostics.operationFinished(
                        operation = AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE,
                        bindingEpoch = bindingEpoch,
                        requestId = requestId,
                        state = AutocorrectPluginDiagnosticState.SUCCEEDED,
                        itemCount = if (removed) 1 else 0,
                    )
                    pending.complete(removed)
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
                else -> {
                    diagnostics.record(
                        AutocorrectPluginDiagnosticEvent.ReplyRejected(
                            bindingEpoch = bindingEpoch,
                            operation = AutocorrectPluginDiagnosticOperation.UNKNOWN_REPLY,
                            error = AutocorrectPluginDiagnosticError.INVALID_REQUEST,
                        ),
                    )
                    super.handleMessage(message)
                }
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
        requestId: Long,
        wireContent: EditorContent,
        originContent: EditorContent,
    ): ExternalAutocorrectCandidate? {
        if (
            !isAutocorrectReplacementInContent(
                replacementStart,
                replacementEnd,
                wireContent.text.length,
            )
        ) {
            return null
        }
        return ExternalAutocorrectCandidate(
            pluginSessionId = sessionId,
            pluginRequestId = requestId,
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
                originContent = originContent,
            ),
            replacement = autocorrectReplacementForWireContent(
                replacementStart,
                replacementEnd,
                wireContent,
                originContent,
            ),
            separatorBehavior = when (separatorBehavior) {
                AutocorrectSeparatorBehavior.INSERT -> SuggestionSeparatorBehavior.INSERT
                AutocorrectSeparatorBehavior.OMIT -> SuggestionSeparatorBehavior.OMIT
                AutocorrectSeparatorBehavior.DEFAULT -> SuggestionSeparatorBehavior.DEFAULT
            },
        )
    }
}

/**
 * Provider work is bounded by the active request/session, not a wall-clock timeout. A cold local
 * model may legitimately take longer than a warm one; superseding input and session cleanup cancel
 * the deferred instead.
 */
internal suspend fun <T> awaitProviderResult(result: Deferred<T>): T? {
    return try {
        result.await()
    } catch (error: CancellationException) {
        if (!currentCoroutineContext().isActive) throw error
        null
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

private fun diagnosticOperationForMessage(what: Int) = when (what) {
    AutocorrectPluginContract.MSG_SUGGESTIONS ->
        AutocorrectPluginDiagnosticOperation.SUGGESTION
    AutocorrectPluginContract.MSG_REMOVE_RESULT ->
        AutocorrectPluginDiagnosticOperation.REMOVE_CANDIDATE
    AutocorrectPluginContract.MSG_FINISH_SESSION_RESULT ->
        AutocorrectPluginDiagnosticOperation.FINISH_SESSION
    AutocorrectPluginContract.MSG_PLUGIN_UI_RESULT ->
        AutocorrectPluginDiagnosticOperation.PLUGIN_UI
    AutocorrectPluginContract.MSG_HOST_USER_DICTIONARY_REQUEST ->
        AutocorrectPluginDiagnosticOperation.USER_DICTIONARY
    else -> AutocorrectPluginDiagnosticOperation.UNKNOWN_REPLY
}

private fun SuggestionReplyDecision.toDiagnosticError() = when (this) {
    is SuggestionReplyDecision.Accept -> AutocorrectPluginDiagnosticError.NONE
    SuggestionReplyDecision.Unknown -> AutocorrectPluginDiagnosticError.UNKNOWN_REQUEST
    is SuggestionReplyDecision.Reject -> when (reason) {
        ReplyRejectionReason.SUPERSEDED ->
            AutocorrectPluginDiagnosticError.SUPERSEDED
        ReplyRejectionReason.STALE_PROVIDER,
        ReplyRejectionReason.STALE_BINDING,
        -> AutocorrectPluginDiagnosticError.STALE_BINDING
        ReplyRejectionReason.STALE_SESSION ->
            AutocorrectPluginDiagnosticError.STALE_SESSION
        ReplyRejectionReason.STALE_GENERATION ->
            AutocorrectPluginDiagnosticError.STALE_GENERATION
        ReplyRejectionReason.DUPLICATE,
        ReplyRejectionReason.CANCELLED,
        ReplyRejectionReason.UNKNOWN_REQUEST,
        -> AutocorrectPluginDiagnosticError.UNKNOWN_REQUEST
    }
}

private fun AutocorrectSession.toHostSessionConfiguration() = SessionConfiguration(
    primaryLanguageTag = primaryLanguageTag,
    secondaryLanguageTags = secondaryLanguageTags,
    inputType = inputType,
    capsMode = capsMode,
    allowPersonalizedLearning = allowPersonalizedLearning,
    editorFlags = editorFlags,
    preferredEmojiSkinToneModifier = preferredEmojiSkinToneModifier,
)

private fun monotonicNow() = MonotonicMillis(SystemClock.elapsedRealtime())

internal fun isAutocorrectReplacementInContent(
    replacementStart: Int,
    replacementEnd: Int,
    contentLength: Int,
): Boolean = when {
    replacementStart == -1 && replacementEnd == -1 -> true
    replacementStart < 0 || replacementEnd < replacementStart -> false
    else -> replacementEnd <= contentLength
}

internal fun autocorrectReplacementForWireContent(
    replacementStart: Int,
    replacementEnd: Int,
    wireContent: EditorContent,
    originContent: EditorContent,
): SuggestionReplacement? {
    if (replacementStart < 0) return null
    val range = EditorRange(replacementStart, replacementEnd)
    return SuggestionReplacement(
        range = range.translatedBy(wireContent.offset.coerceAtLeast(0)),
        originalText = wireContent.text.substring(range.start, range.end),
        expectedSelection = originContent.selection,
    )
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
    val pluginRequestId: Long,
    val pluginCandidateId: String,
    val editorGeneration: Long,
    override val isVisible: Boolean,
    delegate: WordSuggestionCandidate,
    override val replacement: SuggestionReplacement?,
    override val separatorBehavior: SuggestionSeparatorBehavior,
) : SuggestionCandidate by delegate
