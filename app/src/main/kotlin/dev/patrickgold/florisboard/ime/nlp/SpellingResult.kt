/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp

import android.view.textservice.SuggestionsInfo
import org.florisboard.lib.android.AndroidVersion

/**
 * Inline value class wrapping the Android spelling [SuggestionsInfo] class with helpers.
 *
 * @property suggestionsInfo The base [SuggestionsInfo] object, which can be returned to the spell checker service.
 */
@JvmInline
value class SpellingResult(val suggestionsInfo: SuggestionsInfo) {
    /**
     * Returns true if this spelling result is unspecified - meaning there either way no spelling provider available at
     * all or no provider could process and spell the original string. Visually in the target editor field this result
     * is treated exactly like [isValidWord].
     */
    val isUnspecified: Boolean
        get() = suggestionsInfo.suggestionsAttributes == UNSPECIFIED

    /**
     * Returns true if the original string is a valid word. Visually in the target editor field this means that no
     * underlining is done, neither does a popup window appear once the user clicks on the word.
     */
    val isValidWord: Boolean
        get() = suggestionsInfo.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0

    /**
     * Returns true if the original string is a typo. Visually in the target editor field this means that it will be
     * underlined in red and upon clicking the word a popup window appears with the provided corrections.
     */
    val isTypo: Boolean
        get() = suggestionsInfo.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0

    /**
     * Returns true if the original string is a grammar error (Android 12+ only). Visually in the target editor field
     * this means that it will be underlined in blue and upon clicking the word a popup window appears with the
     * provided corrections.
     */
    val isGrammarError: Boolean
        get() = AndroidVersion.ATLEAST_API31_S &&
            suggestionsInfo.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR != 0

    internal val diagnosticState: SpellingDiagnosticState
        get() = when {
            isGrammarError -> SpellingDiagnosticState.GRAMMAR_ERROR
            isTypo -> SpellingDiagnosticState.TYPO
            isValidWord -> SpellingDiagnosticState.VALID_WORD
            else -> SpellingDiagnosticState.UNSPECIFIED
        }

    companion object {
        private const val UNSPECIFIED: Int = 0
        private val EMPTY_STRING_ARRAY: Array<out String> = arrayOf()

        /**
         * Constructs a new unspecified result. See [isUnspecified] for details.
         *
         * @return A spelling result wrapping a [SuggestionsInfo] object, which can be returned to a spell checker
         *  caller in the service.
         */
        fun unspecified() = SpellingResult(SuggestionsInfo(UNSPECIFIED, EMPTY_STRING_ARRAY))

        /**
         * Constructs a new result for a valid input. See [isValidWord] for details.
         *
         * @return A spelling result wrapping a [SuggestionsInfo] object, which can be returned to a spell checker
         *  caller in the service.
         */
        fun validWord(): SpellingResult {
            return SpellingResult(SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, EMPTY_STRING_ARRAY))
        }

        /**
         * Constructs a new result for a typo input. See [isTypo] for details.
         *
         * @param suggestions The array of suggested corrections. May be empty if the word is identified as a grammar
         *  error but fails to generate correction suggestions.
         * @param isHighConfidenceResult If true, a special flag will be set in the base object indicating this result
         *  has recommended suggestions.
         *
         * @return A spelling result wrapping a [SuggestionsInfo] object, which can be returned to a spell checker
         *  caller in the service.
         */
        fun typo(suggestions: Array<out String>, isHighConfidenceResult: Boolean = false): SpellingResult {
            val attributes = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
                (if (isHighConfidenceResult) SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS else 0)
            return SpellingResult(SuggestionsInfo(attributes, suggestions))
        }

        /**
         * Constructs a new result for a grammar error input. See [isGrammarError] for details. If invoked on Android
         * 11 or older, this method returns a typo.
         *
         * @param suggestions The array of suggested corrections. May be empty if the word is identified as a grammar
         *  error but fails to generate correction suggestions.
         * @param isHighConfidenceResult If true, a special flag will be set in the base object indicating this result
         *  has recommended suggestions.
         *
         * @return A spelling result wrapping a [SuggestionsInfo] object, which can be returned to a spell checker
         *  caller in the service.
         */
        fun grammarError(suggestions: Array<out String>, isHighConfidenceResult: Boolean = false): SpellingResult {
            val attributes = if (AndroidVersion.ATLEAST_API31_S) {
                SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR
            } else {
                SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            } or (if (isHighConfidenceResult) SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS else 0)
            return SpellingResult(SuggestionsInfo(attributes, suggestions))
        }
    }
}

/**
 * Content-free spelling outcomes retained for the developer overlay.
 */
internal enum class SpellingDiagnosticState {
    UNSPECIFIED,
    VALID_WORD,
    TYPO,
    GRAMMAR_ERROR,
}

internal data class SpellingDiagnosticRecord(
    /**
     * An opaque, process-local ordering value. It is not derived from time or input.
     */
    val sequence: Long,
    val state: SpellingDiagnosticState,
    val suggestionCount: Int,
)

internal data class SpellingDiagnosticSnapshot(
    val records: List<SpellingDiagnosticRecord>,
    val droppedRecordCount: Long,
)

/**
 * A bounded, thread-safe history that cannot retain words or suggested replacements.
 */
internal class SpellingDiagnostics(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val records = ArrayDeque<SpellingDiagnosticRecord>(capacity)
    private var nextSequence = 1L
    private var droppedRecordCount = 0L

    init {
        require(capacity > 0) { "Diagnostic capacity must be positive" }
    }

    @Synchronized
    fun record(state: SpellingDiagnosticState, suggestionCount: Int) {
        if (records.size == capacity) {
            records.removeFirst()
            droppedRecordCount++
        }
        records.addLast(
            SpellingDiagnosticRecord(
                sequence = nextSequence++,
                state = state,
                suggestionCount = suggestionCount.coerceAtLeast(0),
            ),
        )
    }

    @Synchronized
    fun clear() {
        records.clear()
        droppedRecordCount = 0L
    }

    @Synchronized
    fun snapshot() = SpellingDiagnosticSnapshot(
        records = records.toList(),
        droppedRecordCount = droppedRecordCount,
    )

    companion object {
        const val DEFAULT_CAPACITY = 10
    }
}
