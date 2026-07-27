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
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.autocorrect.api.AutocorrectGesturePoint
import org.florisboard.autocorrect.api.AutocorrectInputMode
import org.florisboard.autocorrect.api.AutocorrectInputTrace
import org.florisboard.autocorrect.api.AutocorrectKeyGeometry
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import kotlin.math.min

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
    private var glideTypingClassifier = StatisticalGlideTypingClassifier(context)
    private var layoutKeys = emptyList<TextKey>()
    private var completionJob: Job? = null
    private var lastTime = System.currentTimeMillis()

    override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
        val trace = gestureTrace(data.positions)
        val content = editorInstance.activeContent
        completionJob?.cancel()
        completionJob = scope.launch {
            val result = autocorrectPluginManager.suggestGesture(
                subtype = subtypeManager.activeSubtype,
                content = content,
                maxCandidateCount = MAX_SUGGESTION_COUNT,
                allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                inputTrace = trace,
            )
            if (!result.handled) {
                withContext(Dispatchers.Main) {
                    completionJob = updateSuggestionsAsync(
                        MAX_SUGGESTION_COUNT,
                        true,
                        content,
                    ) {
                        glideTypingClassifier.clear()
                    }
                }
            } else if (result.candidates.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (!content.isStillActive()) {
                        glideTypingClassifier.clear()
                        return@withContext
                    }
                    nlpManager.suggestDirectly(emptyList())
                    glideTypingClassifier.clear()
                }
            } else {
                withContext(Dispatchers.Main) {
                    if (!content.isStillActive()) {
                        glideTypingClassifier.clear()
                        return@withContext
                    }
                    nlpManager.suggestDirectly(result.candidates.drop(1))
                    keyboardManager.commitGesture(result.candidates.first())
                    glideTypingClassifier.clear()
                }
            }
        }
    }

    override fun onGlideCancelled() {
        completionJob?.cancel()
        glideTypingClassifier.clear()
    }

    override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
        completionJob?.cancel()
        val normalized = GlideTypingGesture.Detector.Position(point.x, point.y)

        this.glideTypingClassifier.addGesturePoint(normalized)

        val time = System.currentTimeMillis()
        if (prefs.glide.showPreview.get() && time - lastTime > prefs.glide.previewRefreshDelay.get()) {
            updateSuggestionsAsync(1, false) {}
            lastTime = time
        }
    }

    /**
     * Change the layout of the internal gesture classifier
     */
    fun setLayout(keys: List<TextKey>) {
        if (keys.isNotEmpty()) {
            layoutKeys = keys
            glideTypingClassifier.setLayout(keys, subtypeManager.activeSubtype)
        }
    }

    private fun gestureTrace(
        positions: List<GlideTypingGesture.Detector.Position>,
    ): AutocorrectInputTrace {
        val width = layoutKeys.maxOfOrNull { it.touchBounds.right } ?: 0f
        val height = layoutKeys.maxOfOrNull { it.touchBounds.bottom } ?: 0f
        if (width <= 0f || height <= 0f || positions.isEmpty()) return AutocorrectInputTrace.Empty
        val keys = layoutKeys.asSequence().mapNotNull { key ->
            val data = key.computedData
            if (data.type != KeyType.CHARACTER) return@mapNotNull null
            AutocorrectKeyGeometry(
                text = data.asString(isForDisplay = false),
                left = key.touchBounds.left / width,
                top = key.touchBounds.top / height,
                right = key.touchBounds.right / width,
                bottom = key.touchBounds.bottom / height,
            )
        }.take(AutocorrectPluginContract.MAX_TRACE_KEY_COUNT).toList()
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
            keys = keys,
            points = emptyList(),
            gesturePoints = gesturePoints,
            mode = AutocorrectInputMode.GESTURE,
        )
    }

    /**
     * Asks gesture classifier for suggestions and then passes that on to the smartbar.
     * Also commits the most confident suggestion if [commit] is set. All happens on an async executor.
     * NB: only fetches [MAX_SUGGESTION_COUNT] suggestions.
     *
     * @param callback Called when this function completes. Takes a boolean, which indicates if suggestions
     * were successfully set.
     */
    private fun updateSuggestionsAsync(
        maxSuggestionsToShow: Int,
        commit: Boolean,
        expectedContent: EditorContent? = null,
        callback: (Boolean) -> Unit,
    ): Job? {
        if (!glideTypingClassifier.ready) {
            callback.invoke(false)
            return null
        }

        return scope.launch(Dispatchers.Default) {
            val suggestions = glideTypingClassifier.getSuggestions(MAX_SUGGESTION_COUNT, true)

            withContext(Dispatchers.Main) {
                if (expectedContent != null && !expectedContent.isStillActive()) {
                    callback.invoke(false)
                    return@withContext
                }
                val suggestionList = buildList {
                    suggestions.subList(
                        1.coerceAtMost(min(commit.compareTo(false), suggestions.size)),
                        maxSuggestionsToShow.coerceAtMost(suggestions.size)
                    ).map { keyboardManager.fixCase(it) }.forEach {
                        add(WordSuggestionCandidate(it, confidence = 1.0))
                    }
                }

                nlpManager.suggestDirectly(suggestionList)
                if (commit && suggestions.isNotEmpty()) {
                    keyboardManager.commitGesture(suggestions.first())
                }
                callback.invoke(true)
            }
        }
    }

    private fun EditorContent.isStillActive(): Boolean {
        val current = editorInstance.activeContent
        return current.text == text && current.selection == selection
    }
}
