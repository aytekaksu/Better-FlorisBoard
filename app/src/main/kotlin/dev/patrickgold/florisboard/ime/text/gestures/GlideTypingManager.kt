/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.text.gestures

import android.content.Context
import android.os.SystemClock
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.isWordInput
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.text.keyboard.AutocorrectInputLayoutSnapshot
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.text.keyboard.hitTestBounds
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.lowercase
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.autocorrect.api.AutocorrectGesturePoint
import org.florisboard.autocorrect.api.AutocorrectInputMode
import org.florisboard.autocorrect.api.AutocorrectInputTrace
import org.florisboard.autocorrect.api.AutocorrectKeyGeometry
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import java.text.Normalizer

/**
 * Handles the [GlideTypingClassifier]. Basically responsible for linking [GlideTypingGesture.Detector]
 * with [GlideTypingClassifier].
 */
class GlideTypingManager(context: Context) : GlideTypingGesture.Listener {
    companion object {
        private const val MAX_SUGGESTION_COUNT = 8
    }

    private val prefs by FlorisPreferenceStore
    private val autocorrectPluginManager by context.autocorrectPluginManager()
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val nlpManager by context.nlpManager()
    private val subtypeManager by context.subtypeManager()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val classifierMutex = Mutex()
    private val glideTypingClassifier = StatisticalGlideTypingClassifier(context)
    private var activeDetector: GlideTypingGesture.Detector? = null
    private val gesturePoints = mutableListOf<GlideTypingGesture.Detector.Position>()
    @Volatile private var layoutRevision: LayoutRevision? = null
    private var previewJob: Job? = null
    private var completionJob: Job? = null
    @Volatile private var pendingGlide: PendingGlide? = null
    private val queuedGlides = mutableSetOf<QueuedGlide>()
    @Volatile private var previewGeneration = 0L
    private var publishedPreviewGeneration: Long? = null
    private var lastTime = SystemClock.uptimeMillis()

    override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
        val completedGeneration = previewGeneration
        previewJob?.cancel()
        previewJob = null
        gesturePoints.clear()
        ++previewGeneration
        clearPublishedPreview(completedGeneration)
        val points = data.positions.map {
            GlideTypingGesture.Detector.Position(it.x, it.y, it.elapsedTimeMillis)
        }
        val revision = layoutRevision
        if (revision == null || subtypeManager.activeSubtype != revision.subtype) {
            return
        }
        val queued = QueuedGlide(
            points = points,
            trace = gestureTrace(points, revision),
            revision = revision,
        )
        queuedGlides.add(queued)
        keyboardManager.inputEventDispatcher.deferInputEvents(
            onLaterInputQueued = { pendingGlide?.cancelProviderAttempt() },
            start = start@ { onResolved ->
                if (!queuedGlides.remove(queued) || subtypeManager.activeSubtype != revision.subtype) {
                    onResolved()
                    return@start
                }
                startQueuedGlide(queued, onResolved)
            },
        )
    }

    override fun onGlideCancelled() {
        cancelActiveGesture()
    }

    @Synchronized
    internal fun attachDetector(detector: GlideTypingGesture.Detector) {
        if (activeDetector === detector) return
        activeDetector?.let {
            it.cancel()
            it.unregisterListener(this)
        }
        activeDetector = detector
        detector.registerListener(this)
    }

    @Synchronized
    internal fun detachDetector(detector: GlideTypingGesture.Detector) {
        if (activeDetector !== detector) return
        detector.cancel()
        detector.unregisterListener(this)
        activeDetector = null
    }

    @Synchronized
    private fun cancelActiveDetector() {
        activeDetector?.cancel()
    }

    private fun startQueuedGlide(queued: QueuedGlide, onResolved: () -> Unit) {
        autocorrectPluginManager.consumePredictionHints()
        val pending = PendingGlide(
            points = queued.points,
            trace = queued.trace,
            content = editorInstance.activeContent,
            revision = queued.revision,
            editorGeneration = autocorrectPluginManager.captureEditorGeneration(),
            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            isPrivateSession = keyboardManager.activeState.isIncognitoMode,
            onResolved = onResolved,
        )
        pendingGlide = pending
        completionJob = scope.launch {
            try {
                val result = coroutineScope {
                    val providerAttempt = async {
                        autocorrectPluginManager.suggestGesture(
                            subtype = pending.revision.subtype,
                            content = pending.content,
                            maxCandidateCount = MAX_SUGGESTION_COUNT,
                            allowPossiblyOffensive = pending.allowPossiblyOffensive,
                            isPrivateSession = pending.isPrivateSession,
                            inputTrace = pending.trace,
                            requestEditorGeneration = pending.editorGeneration,
                        )
                    }
                    pending.attachProviderAttempt(providerAttempt)
                    try {
                        providerAttempt.await()
                    } finally {
                        pending.detachProviderAttempt(providerAttempt)
                    }
                }
                if (pendingGlide !== pending) return@launch
                when {
                    !result.handled -> completeWithBuiltInClassifier(pending)
                    result.candidates.isEmpty() -> withContext(Dispatchers.Main) {
                        if (pendingGlide === pending && pending.isStillCurrent()) {
                            clearSuggestionsUnlessPreviewActive()
                        }
                    }
                    else -> {
                        val primaryCandidate = result.candidates.first()
                        val committed = withContext(Dispatchers.Main) {
                            val committedText =
                                keyboardManager.fixCase(primaryCandidate.text.toString())
                            pendingGlide === pending &&
                                pending.isStillCurrent() &&
                                keyboardManager.commitGesture(primaryCandidate).also {
                                    if (it) {
                                        publishedPreviewGeneration = null
                                        nlpManager.suggestDirectly(
                                            rebaseGlideAlternatives(
                                                candidates = result.candidates.drop(1),
                                                committedText = committedText,
                                                postCommitContent = editorInstance.activeContent,
                                                isCandidateAvailable =
                                                    autocorrectPluginManager::canCommitCandidate,
                                            ),
                                        )
                                    }
                                }
                        }
                        if (!committed && pendingGlide === pending && pending.isStillCurrent()) {
                            completeWithBuiltInClassifier(pending)
                        }
                    }
                }
            } catch (error: CancellationException) {
                if (currentCoroutineContext().isActive && pendingGlide === pending) {
                    completeWithBuiltInClassifier(pending)
                } else {
                    throw error
                }
            } catch (_: Exception) {
                if (pendingGlide === pending) completeWithBuiltInClassifier(pending)
            } finally {
                // Resolve the semantic-input barrier exactly once and outside provider error
                // handling, so a later receiver failure cannot be mistaken for provider failure.
                withContext(NonCancellable + Dispatchers.Main) {
                    finishPendingGlide(pending)
                }
            }
        }
    }

    fun cancelPendingInput() {
        cancelActiveDetector()
        cancelActiveGesture()
        val pendingCompletion = completionJob
        pendingGlide = null
        completionJob = null
        queuedGlides.clear()
        keyboardManager.inputEventDispatcher.invalidatePendingInputEvents()
        keyboardManager.inputEventDispatcher.cancelPressedKeys()
        pendingCompletion?.cancel()
    }

    private fun cancelActiveGesture() {
        val cancelledGeneration = previewGeneration
        previewJob?.cancel()
        previewJob = null
        gesturePoints.clear()
        ++previewGeneration
        clearPublishedPreview(cancelledGeneration)
    }

    private fun clearPublishedPreview(generation: Long) {
        if (publishedPreviewGeneration != generation) return
        publishedPreviewGeneration = null
        nlpManager.suggestDirectly(emptyList())
    }

    private suspend fun completeWithBuiltInClassifier(pending: PendingGlide) {
        val suggestions = try {
            classifyWithBuiltInClassifier(
                points = pending.points,
                gestureCompleted = true,
                expectedRevision = pending.revision,
                expectedEditorGeneration = pending.editorGeneration,
                requireCurrentLayout = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        withContext(Dispatchers.Main) {
            if (pendingGlide !== pending) return@withContext
            if (
                !suggestions.isNullOrEmpty() &&
                pending.isStillCurrent() &&
                keyboardManager.commitGesture(suggestions.first())
            ) {
                publishedPreviewGeneration = null
                publishBuiltInSuggestions(
                    suggestions = suggestions,
                    maxSuggestionsToShow = MAX_SUGGESTION_COUNT,
                    firstVisibleIndex = 1,
                )
            } else {
                clearSuggestionsUnlessPreviewActive()
            }
        }
    }

    private fun clearSuggestionsUnlessPreviewActive() {
        if (publishedPreviewGeneration == null) {
            nlpManager.suggestDirectly(emptyList())
        }
    }

    private fun finishPendingGlide(pending: PendingGlide) {
        if (pendingGlide !== pending) return
        pendingGlide = null
        completionJob = null
        pending.onResolved()
    }

    override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
        val normalized = GlideTypingGesture.Detector.Position(point.x, point.y)
        gesturePoints.add(normalized)
        val generation = previewGeneration

        val time = SystemClock.uptimeMillis()
        if (
            prefs.glide.showPreview.get() &&
            previewJob?.isActive != true &&
            time - lastTime > prefs.glide.previewRefreshDelay.get()
        ) {
            val content = editorInstance.activeContent
            val editorGeneration = autocorrectPluginManager.captureEditorGeneration()
            val points = gesturePoints.toList()
            val revision = layoutRevision
            previewJob = scope.launch {
                val suggestions = try {
                    classifyWithBuiltInClassifier(
                        points = points,
                        gestureCompleted = false,
                        expectedRevision = revision,
                        expectedEditorGeneration = editorGeneration,
                        requireCurrentLayout = true,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (suggestions != null) {
                    withContext(Dispatchers.Main) {
                        if (
                            generation == previewGeneration &&
                            revision == layoutRevision &&
                            subtypeManager.activeSubtype == revision?.subtype &&
                            autocorrectPluginManager.isCurrentEditorGeneration(editorGeneration) &&
                            content.isStillActive()
                        ) {
                            publishBuiltInSuggestions(
                                suggestions = suggestions,
                                maxSuggestionsToShow = 1,
                            )
                            publishedPreviewGeneration = generation
                        }
                    }
                }
            }
            lastTime = time
        }
    }

    /**
     * Change the layout of the internal gesture classifier
     */
    internal fun setLayout(
        keys: List<TextKey>,
        inputLayout: AutocorrectInputLayoutSnapshot,
    ): Boolean {
        val subtype = subtypeManager.activeSubtype
        val classifierKeys = ArrayList<GlideTypingKey>(keys.size)
        for (key in keys) {
            val data = key.computedData
            val bounds = key.hitTestBounds()
            if (
                key.isEnabled &&
                key.isVisible &&
                !bounds.isEmpty() &&
                data.isWordInput(KeyboardMode.CHARACTERS)
            ) {
                val output = key.glideTypingOutput(subtype) ?: continue
                classifierKeys.add(
                    GlideTypingKey(
                        id = classifierKeys.size,
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        output = output,
                    ),
                )
            }
        }
        val revision = LayoutRevision(
            subtype = subtype,
            width = inputLayout.width,
            height = inputLayout.height,
            classifierKeys = classifierKeys.toList(),
            traceKeys = inputLayout.keys,
        )
        if (revision == layoutRevision) return false
        layoutRevision = revision
        cancelActiveGesture()
        return true
    }

    private fun gestureTrace(
        positions: List<GlideTypingGesture.Detector.Position>,
        revision: LayoutRevision,
    ): AutocorrectInputTrace {
        val width = revision.width
        val height = revision.height
        if (width <= 0f || height <= 0f || positions.isEmpty()) return AutocorrectInputTrace.Empty
        val step = (
            (positions.size + AutocorrectPluginContract.MAX_GESTURE_POINT_COUNT - 1) /
                AutocorrectPluginContract.MAX_GESTURE_POINT_COUNT
            ).coerceAtLeast(1)
        val sampledIndices = (positions.indices step step)
            .take(AutocorrectPluginContract.MAX_GESTURE_POINT_COUNT - 1)
            .toMutableList()
            .apply {
                if (lastOrNull() != positions.lastIndex) add(positions.lastIndex)
            }
        val gesturePoints = sampledIndices.map { index ->
            positions[index].let { point ->
                AutocorrectGesturePoint(
                    x = point.x / width,
                    y = point.y / height,
                    elapsedTimeMillis = point.elapsedTimeMillis,
                )
            }
        }
        return AutocorrectInputTrace(
            keys = revision.traceKeys,
            points = emptyList(),
            gesturePoints = gesturePoints,
            mode = AutocorrectInputMode.GESTURE,
        )
    }

    private suspend fun classifyWithBuiltInClassifier(
        points: List<GlideTypingGesture.Detector.Position>,
        gestureCompleted: Boolean,
        expectedRevision: LayoutRevision?,
        expectedEditorGeneration: Long,
        requireCurrentLayout: Boolean,
    ): List<String>? {
        if (expectedRevision == null) return null
        if (
            requireCurrentLayout && expectedRevision != layoutRevision ||
            !autocorrectPluginManager.isCurrentEditorGeneration(expectedEditorGeneration)
        ) {
            return null
        }
        return classifierMutex.withLock {
            if (
                requireCurrentLayout && expectedRevision != layoutRevision ||
                !autocorrectPluginManager.isCurrentEditorGeneration(expectedEditorGeneration) ||
                expectedRevision.classifierKeys.isEmpty()
            ) {
                return@withLock null
            }
            glideTypingClassifier.setLayout(
                expectedRevision.classifierKeys,
                expectedRevision.subtype,
            )
            if (!glideTypingClassifier.ready) return@withLock null
            glideTypingClassifier.clear()
            try {
                points.forEach(glideTypingClassifier::addGesturePoint)
                glideTypingClassifier.getSuggestions(
                    maxSuggestionCount = MAX_SUGGESTION_COUNT,
                    gestureCompleted = gestureCompleted,
                )
            } finally {
                glideTypingClassifier.clear()
            }
        }
    }

    private fun publishBuiltInSuggestions(
        suggestions: List<String>,
        maxSuggestionsToShow: Int,
        firstVisibleIndex: Int = 0,
    ) {
        val suggestionList = buildList {
            for (index in firstVisibleIndex until minOf(maxSuggestionsToShow, suggestions.size)) {
                add(
                    WordSuggestionCandidate(
                        text = keyboardManager.fixCase(suggestions[index]),
                        confidence = 1.0,
                        originContent = editorInstance.activeContent,
                    ),
                )
            }
        }
        nlpManager.suggestDirectly(suggestionList)
    }

    private fun EditorContent.isStillActive(): Boolean {
        val current = editorInstance.activeContent
        return current.text == text && current.selection == selection
    }

    private fun PendingGlide.isStillCurrent(): Boolean {
        return subtypeManager.activeSubtype == revision.subtype &&
            autocorrectPluginManager.isCurrentEditorGeneration(editorGeneration) &&
            content.isStillActive()
    }

    private class PendingGlide(
        val points: List<GlideTypingGesture.Detector.Position>,
        val trace: AutocorrectInputTrace,
        val content: EditorContent,
        val revision: LayoutRevision,
        val editorGeneration: Long,
        val allowPossiblyOffensive: Boolean,
        val isPrivateSession: Boolean,
        val onResolved: () -> Unit,
    ) {
        private var providerAttempt: Job? = null
        private var shouldCancelProviderAttempt = false

        @Synchronized
        fun attachProviderAttempt(attempt: Job) {
            providerAttempt = attempt
            if (shouldCancelProviderAttempt) attempt.cancel()
        }

        @Synchronized
        fun detachProviderAttempt(attempt: Job) {
            if (providerAttempt === attempt) providerAttempt = null
        }

        @Synchronized
        fun cancelProviderAttempt() {
            shouldCancelProviderAttempt = true
            providerAttempt?.cancel()
        }
    }

    private class QueuedGlide(
        val points: List<GlideTypingGesture.Detector.Position>,
        val trace: AutocorrectInputTrace,
        val revision: LayoutRevision,
    )

    private data class LayoutRevision(
        val subtype: Subtype,
        val width: Float,
        val height: Float,
        val classifierKeys: List<GlideTypingKey>,
        val traceKeys: List<AutocorrectKeyGeometry>,
    )
}

internal fun rebaseGlideAlternatives(
    candidates: List<SuggestionCandidate>,
    committedText: String,
    postCommitContent: EditorContent,
    isCandidateAvailable: (SuggestionCandidate) -> Boolean,
): List<SuggestionCandidate> {
    val replacement = glideAlternativeReplacement(committedText, postCommitContent)
        ?: return emptyList()
    return candidates.map { candidate ->
        RebasedGlideCandidate(
            original = candidate,
            rebasedOriginContent = postCommitContent,
            rebasedReplacement = replacement,
            isAvailable = isCandidateAvailable,
        )
    }
}

internal fun glideAlternativeReplacement(
    committedText: String,
    content: EditorContent,
): SuggestionReplacement? {
    if (
        committedText.isEmpty() ||
        content.offset < 0 ||
        !content.localSelection.isCursorMode
    ) {
        return null
    }
    val localEnd = content.localSelection.start
    val localStart = localEnd - committedText.length
    if (
        localStart < 0 ||
        localEnd > content.text.length ||
        content.text.substring(localStart, localEnd) != committedText
    ) {
        return null
    }
    return SuggestionReplacement(
        range = EditorRange(
            start = content.offset + localStart,
            end = content.offset + localEnd,
        ),
        originalText = committedText,
        expectedSelection = content.selection,
    )
}

private class RebasedGlideCandidate(
    private val original: SuggestionCandidate,
    private val rebasedOriginContent: EditorContent,
    private val rebasedReplacement: SuggestionReplacement,
    private val isAvailable: (SuggestionCandidate) -> Boolean,
) : SuggestionCandidate by original {
    override val isEligibleForAutoCommit = false

    override val sourceProvider = original.sourceProvider?.let { provider ->
        RebasedGlideProvider(provider, original)
    }

    override val originContent: EditorContent?
        get() = if (isAvailable(original)) rebasedOriginContent else original.originContent

    override val replacement: SuggestionReplacement?
        get() = rebasedReplacement.takeIf { isAvailable(original) }
}

private class RebasedGlideProvider(
    private val delegate: SuggestionProvider,
    private val original: SuggestionCandidate,
) : SuggestionProvider by delegate {
    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        delegate.notifySuggestionAccepted(subtype, original)
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        delegate.notifySuggestionReverted(subtype, original)
    }

    override suspend fun removeSuggestion(
        subtype: Subtype,
        candidate: SuggestionCandidate,
    ): Boolean {
        return delegate.removeSuggestion(subtype, original)
    }
}

internal fun TextKey.glideTypingOutput(subtype: Subtype): String? {
    val sourceData = (data as? KeyData) ?: computedData
    return Normalizer.normalize(
        sourceData.asString(isForDisplay = false).lowercase(subtype.primaryLocale),
        Normalizer.Form.NFC,
    ).takeIf(String::isNotEmpty)
}
