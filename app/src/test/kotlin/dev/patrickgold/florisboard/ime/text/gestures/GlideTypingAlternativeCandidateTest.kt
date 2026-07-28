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

package dev.patrickgold.florisboard.ime.text.gestures

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.nlp.FallbackNlpProvider
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class GlideTypingAlternativeCandidateTest : FunSpec({
    test("external alternatives replace the committed glide and keep provider callbacks") {
        val beforeCommit = content("alpha ", offset = 100, cursor = 6)
        val afterCommit = content("alpha beta", offset = 100, cursor = 10)
        var accepted: SuggestionCandidate? = null
        var removed: SuggestionCandidate? = null
        val provider = object : SuggestionProvider by FallbackNlpProvider {
            override suspend fun notifySuggestionAccepted(
                subtype: Subtype,
                candidate: SuggestionCandidate,
            ) {
                accepted = candidate
            }

            override suspend fun removeSuggestion(
                subtype: Subtype,
                candidate: SuggestionCandidate,
            ): Boolean {
                removed = candidate
                return true
            }
        }
        val original = WordSuggestionCandidate(
            text = "better",
            isEligibleForAutoCommit = true,
            sourceProvider = provider,
            originContent = beforeCommit,
        )
        var available = true

        val rebased = rebaseGlideAlternatives(
            candidates = listOf(original),
            committedText = "beta",
            postCommitContent = afterCommit,
            isCandidateAvailable = { available },
        ).single()

        rebased.originContent shouldBe afterCommit
        rebased.replacement shouldBe SuggestionReplacement(
            range = EditorRange(106, 110),
            originalText = "beta",
            expectedSelection = EditorRange.cursor(110),
        )
        rebased.isEligibleForAutoCommit shouldBe false

        rebased.sourceProvider!!.notifySuggestionAccepted(Subtype.DEFAULT, rebased)
        rebased.sourceProvider!!.removeSuggestion(Subtype.DEFAULT, rebased) shouldBe true
        (accepted === original) shouldBe true
        (removed === original) shouldBe true

        available = false
        rebased.replacement shouldBe null
        rebased.originContent shouldBe beforeCommit
    }

    test("alternatives are hidden if the committed text cannot be proven in editor content") {
        val candidate = WordSuggestionCandidate(text = "alternative")
        rebaseGlideAlternatives(
            candidates = listOf(candidate),
            committedText = "beta",
            postCommitContent = content("alpha changed", offset = 0, cursor = 13),
            isCandidateAvailable = { true },
        ).shouldBeEmpty()
        rebaseGlideAlternatives(
            candidates = listOf(candidate),
            committedText = "beta",
            postCommitContent = EditorContent.Unspecified,
            isCandidateAvailable = { true },
        ).shouldBeEmpty()
    }
})

private fun content(text: String, offset: Int, cursor: Int) = EditorContent(
    text = text,
    offset = offset,
    localSelection = EditorRange.cursor(cursor),
    localComposing = EditorRange.Unspecified,
    localCurrentWord = EditorRange.Unspecified,
)
