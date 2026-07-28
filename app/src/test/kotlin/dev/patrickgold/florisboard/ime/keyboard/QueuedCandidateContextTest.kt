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

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class QueuedCandidateContextTest : FunSpec({
    test("context-free candidates are rejected after queued editor changes") {
        val candidate = WordSuggestionCandidate(text = "hello")
        val expected = content("helo")

        isQueuedCandidateContextCurrent(candidate, expected, expected) shouldBe true
        isQueuedCandidateContextCurrent(candidate, expected, content("helo!")) shouldBe false
    }

    test("explicit replacements retain their own live-state validation") {
        val expected = content("helo")
        val candidate = object : SuggestionCandidate by WordSuggestionCandidate(text = "hello") {
            override val replacement = SuggestionReplacement(
                range = EditorRange(0, 4),
                originalText = "helo",
                expectedSelection = expected.selection,
            )
        }

        isQueuedCandidateContextCurrent(candidate, expected, content("changed")) shouldBe true
    }

    test("a stale visible candidate cannot adopt the editor state present when tapped") {
        val origin = content("old")
        val current = content("new")
        val candidate = WordSuggestionCandidate(
            text = "candidate",
            originContent = origin,
        )

        isQueuedCandidateContextCurrent(candidate, current, current) shouldBe false
        isCandidateOriginCurrent(candidate, current) shouldBe false
        isCandidateOriginCurrent(candidate, origin) shouldBe true
    }
})

private fun content(text: String) = EditorContent(
    text = text,
    offset = 0,
    localSelection = EditorRange.cursor(text.length),
    localComposing = EditorRange.Unspecified,
    localCurrentWord = EditorRange(0, text.length),
)
