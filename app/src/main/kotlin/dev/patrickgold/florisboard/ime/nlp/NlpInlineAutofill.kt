/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import android.os.Build
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionInfo
import android.widget.inline.InlineContentView
import androidx.annotation.RequiresApi
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class NlpInlineAutofillSuggestion(
    val info: InlineSuggestionInfo,
    val view: InlineContentView?,
)

object NlpInlineAutofill {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val requestGuard = Any()
    private var currentSequenceId = 0
    private var inflationJob: Job? = null

    val suggestions: StateFlow<List<NlpInlineAutofillSuggestion>>
        field = MutableStateFlow(emptyList())

    var suggestionsChipHeightPx: Int = 0

    @RequiresApi(Build.VERSION_CODES.R)
    fun showInlineSuggestions(context: Context, rawSuggestions: List<InlineSuggestion>): Boolean {
        if (rawSuggestions.isEmpty()) {
            clearInlineSuggestions()
            return false
        }

        val (sequenceId, job) = synchronized(requestGuard) {
            val sequenceId = ++currentSequenceId
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, suggestionsChipHeightPx)
                val inflatedSuggestions = awaitInlineSuggestionInflations(rawSuggestions) {
                    rawSuggestion, complete ->
                    rawSuggestion.inflate(context, size, context.mainExecutor) { view ->
                        complete(NlpInlineAutofillSuggestion(rawSuggestion.info, view))
                    }
                }.sortedByDescending { it.info.isPinned }

                flogInfo { "showInlineSuggestions: [${sequenceId}] successfully inflated " +
                    "${inflatedSuggestions.count { it.view != null }} out of ${inflatedSuggestions.size} suggestions" }
                if (publishIfCurrent(sequenceId) { suggestions.value = inflatedSuggestions }) {
                    flogInfo { "showInlineSuggestions: [${sequenceId}] setting suggestions" }
                } else {
                    flogWarning { "showInlineSuggestions: [${sequenceId}] seqId != current, skip setting suggestions" }
                }
            }
            flogInfo { "showInlineSuggestions: [${sequenceId}] start inflating suggestions" }
            inflationJob?.cancel()
            inflationJob = job
            sequenceId to job
        }
        job.invokeOnCompletion {
            synchronized(requestGuard) {
                if (inflationJob === job) inflationJob = null
            }
        }
        job.start()

        return true
    }

    fun clearInlineSuggestions() {
        synchronized(requestGuard) {
            val sequenceId = ++currentSequenceId
            inflationJob?.cancel()
            inflationJob = null
            flogInfo { "clearInlineSuggestions: [${sequenceId}] clearing suggestions" }
            suggestions.value = emptyList()
        }
    }

    private inline fun publishIfCurrent(sequenceId: Int, publish: () -> Unit): Boolean {
        return synchronized(requestGuard) {
            if (currentSequenceId != sequenceId) return@synchronized false
            publish()
            true
        }
    }
}

internal suspend fun <I, O> awaitInlineSuggestionInflations(
    inputs: List<I>,
    start: (I, (O) -> Unit) -> Unit,
): List<O> = coroutineScope {
    inputs.map { input ->
        async(start = CoroutineStart.UNDISPATCHED) {
            val result = CompletableDeferred<O>()
            start(input) { output -> result.complete(output) }
            result.await()
        }
    }.awaitAll()
}
