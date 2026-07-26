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

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidateKind
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.SuggestionSeparatorBehavior
import org.florisboard.autocorrect.api.AutocorrectCandidate
import org.florisboard.autocorrect.api.AutocorrectCandidateKind
import org.florisboard.autocorrect.api.AutocorrectSeparatorBehavior

class ExternalAutocorrectProvider(
    private val manager: AutocorrectPluginManager,
) : SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.external-autocorrect"
    }

    override val providerId = ProviderId

    override suspend fun create() = Unit

    override suspend fun preload(subtype: Subtype) = Unit

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val suggestions = manager.suggest(
            content = content,
            maxCandidateCount = maxCandidateCount,
            allowPossiblyOffensive = allowPossiblyOffensive,
            isPrivateSession = isPrivateSession,
        )
        return suggestions.candidates.map { candidate ->
            candidate.toSuggestionCandidate(suggestions.sessionId, content)
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        if (candidate is ExternalAutocorrectCandidate) {
            manager.notifyAccepted(candidate.pluginSessionId, candidate.pluginCandidateId)
        }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        if (candidate is ExternalAutocorrectCandidate) {
            manager.notifyReverted(candidate.pluginSessionId, candidate.pluginCandidateId)
        }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return candidate is ExternalAutocorrectCandidate &&
            manager.removeSuggestion(candidate.pluginSessionId, candidate.pluginCandidateId)
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> = emptyList()

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double = 0.0

    override suspend fun destroy() {
        manager.finishSession()
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
            text = text,
            secondaryText = secondaryText,
            confidence = confidence,
            isEligibleForAutoCommit = autoCommit,
            isEligibleForUserRemoval = removable,
            replacementRange = localReplacement?.translatedBy(content.offset.coerceAtLeast(0)),
            replacementOriginalText = localReplacement?.let { content.text.substring(it.start, it.end) },
            replacementExpectedSelection = localReplacement?.let { content.selection },
            separatorBehavior = when (separatorBehavior) {
                AutocorrectSeparatorBehavior.INSERT -> SuggestionSeparatorBehavior.INSERT
                AutocorrectSeparatorBehavior.OMIT -> SuggestionSeparatorBehavior.OMIT
                AutocorrectSeparatorBehavior.DEFAULT -> SuggestionSeparatorBehavior.DEFAULT
            },
            kind = when (kind) {
                AutocorrectCandidateKind.TYPED -> SuggestionCandidateKind.TYPED
                AutocorrectCandidateKind.CORRECTION -> SuggestionCandidateKind.CORRECTION
                AutocorrectCandidateKind.COMPLETION -> SuggestionCandidateKind.COMPLETION
                AutocorrectCandidateKind.NEXT_WORD -> SuggestionCandidateKind.NEXT_WORD
            },
            sourceProvider = this@ExternalAutocorrectProvider,
        )
    }
}

data class ExternalAutocorrectCandidate(
    val pluginSessionId: Long,
    val pluginCandidateId: String,
    override val text: CharSequence,
    override val secondaryText: CharSequence?,
    override val confidence: Double,
    override val isEligibleForAutoCommit: Boolean,
    override val isEligibleForUserRemoval: Boolean,
    override val replacementRange: EditorRange?,
    override val replacementOriginalText: String?,
    override val replacementExpectedSelection: EditorRange?,
    override val separatorBehavior: SuggestionSeparatorBehavior,
    override val kind: SuggestionCandidateKind,
    override val sourceProvider: SuggestionProvider?,
) : SuggestionCandidate {
    override val icon = null
}
