/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
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

import android.icu.text.BreakIterator
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType

/**
 * Base contract for in-process NLP providers.
 *
 * Providers should do setup in [create], not in their constructor. The manager keeps one instance
 * per [providerId], even when that instance supports several capabilities.
 */
sealed interface NlpProvider {
    /** Stable ID used by subtype settings to select this provider. */
    val providerId: String

    /** Performs setup once per lifecycle before the first request. */
    suspend fun create() = Unit

    /** Prepares locale-specific data before a request for [subtype]. */
    suspend fun preload(subtype: Subtype) = Unit

    /** Releases resources owned by this provider. */
    suspend fun destroy() = Unit
}

/** Provides spell checking for words in a subtype's languages. */
interface SpellingProvider : NlpProvider {
    /**
     * Checks [word] and returns corrections when needed.
     *
     * Spell checks are read-only and must never train provider state. Implementations must respect
     * [maxSuggestionCount] and [allowPossiblyOffensive].
     */
    suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult
}

/** Provides current-word, next-word, and autocorrect candidates. */
interface SuggestionProvider : NlpProvider {
    /**
     * Builds candidates for the current [content].
     *
     * Implementations must respect [maxCandidateCount] and [allowPossiblyOffensive]. A private
     * session may use existing learned data but must not learn from the request.
     */
    suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate>

    /** Notifies the provider that [candidate] was accepted. */
    suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) = Unit

    /** Notifies the provider that an automatic acceptance was reverted. */
    suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) = Unit

    /** Returns whether the provider honored the request to stop suggesting [candidate]. */
    suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean = false

    /**
     * Finds the composing range at the end of [textBeforeSelection]. Providers may override this
     * for scripts whose boundaries differ from the default word iterator.
     */
    fun determineLocalComposing(
        subtype: Subtype,
        textBeforeSelection: CharSequence,
        breakIterators: BreakIteratorGroup,
        localLastCommitPosition: Int,
    ): EditorRange = breakIterators.word(subtype.primaryLocale) {
        it.setText(textBeforeSelection.toString())
        val end = it.last()
        if (it.ruleStatus != BreakIterator.WORD_NONE) {
            val start = it.previous().let { pos ->
                // Keep the leading marker inside an emoji suggestion query.
                (pos - 1).takeIf { updatedPos ->
                    textBeforeSelection.getOrNull(updatedPos) ==
                        EmojiSuggestionType.LEADING_COLON.prefix.first()
                } ?: pos
            }
            EditorRange(start, end)
        } else {
            EditorRange.Unspecified
        }
    }

    /** Whether this provider requires the suggestion strip even when suggestions are disabled. */
    val forcesSuggestionOn
        get() = false
}

/** Supplies the built-in word list and weights used by glide typing. */
interface GlideTypingLexiconProvider : NlpProvider {
    suspend fun getWords(subtype: Subtype): List<String>

    /** Returns a weight between 0.0 and 1.0, or 0.0 when [word] is unknown. */
    suspend fun getWordFrequency(subtype: Subtype, word: String): Double
}

internal suspend fun NlpProvider.glideTypingWordsOrEmpty(subtype: Subtype) =
    (this as? GlideTypingLexiconProvider)?.getWords(subtype).orEmpty()

internal suspend fun NlpProvider.glideTypingWordFrequencyOrZero(subtype: Subtype, word: String) =
    (this as? GlideTypingLexiconProvider)?.getWordFrequency(subtype, word) ?: 0.0

/** Used when a subtype references an unavailable spelling or suggestion provider. */
object FallbackNlpProvider : SpellingProvider, SuggestionProvider {
    override val providerId = "org.florisboard.nlp.providers.fallback"

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ) = SpellingResult.unspecified()

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ) = emptyList<SuggestionCandidate>()
}

internal fun NlpProvider?.asSpellingProviderOrFallback(): SpellingProvider =
    this as? SpellingProvider ?: FallbackNlpProvider

internal fun NlpProvider?.asSuggestionProviderOrFallback(): SuggestionProvider =
    this as? SuggestionProvider ?: FallbackNlpProvider

internal fun selectActiveSuggestionProvider(
    builtInProvider: SuggestionProvider,
    externalProvider: SuggestionProvider,
    externalProviderId: String,
) = if (externalProviderId.isNotBlank()) externalProvider else builtInProvider
