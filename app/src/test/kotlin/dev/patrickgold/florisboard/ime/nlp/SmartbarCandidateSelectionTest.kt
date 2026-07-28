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

package dev.patrickgold.florisboard.ime.nlp

import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

class SmartbarCandidateSelectionTest : FunSpec({
    test("word and clipboard candidates follow editor typing state") {
        selectSmartbarCandidates(
            isWordBeingTyped = true,
            wordCandidates = listOf("type", "typing", "typed"),
            clipboardCandidates = listOf("clipboard"),
        ) shouldBe listOf("type", "typing", "typed")
        selectSmartbarCandidates(
            isWordBeingTyped = false,
            wordCandidates = listOf("next", "word"),
            clipboardCandidates = listOf("clipboard"),
        ) shouldBe listOf("clipboard")
        selectSmartbarCandidates(
            isWordBeingTyped = true,
            wordCandidates = emptyList(),
            clipboardCandidates = listOf("clipboard"),
        ) shouldBe emptyList()
        selectSmartbarCandidates(
            isWordBeingTyped = false,
            wordCandidates = listOf("next", "word"),
            clipboardCandidates = emptyList(),
        ) shouldBe listOf("next", "word")
    }

    test("word candidate origin binding preserves provider candidate identity") {
        val candidate = WordSuggestionCandidate(text = "hello")
        val origin = content("helo")
        val nextOrigin = content("new context")

        candidate.bindOriginContent(origin) shouldBeSameInstanceAs candidate
        candidate.bindOriginContent(nextOrigin) shouldBeSameInstanceAs candidate
        candidate.originContent shouldBeSameInstanceAs nextOrigin
    }

    test("emoji candidate origin binding preserves concrete callback type") {
        val candidate = EmojiSuggestionCandidate(
            emoji = Emoji("🙂", "slightly smiling face", emptyList()),
            showName = false,
        )
        val origin = content(":slight")
        val bound = candidate.bindOriginContent(origin)

        bound shouldBeSameInstanceAs candidate
        bound.shouldBeInstanceOf<EmojiSuggestionCandidate>()
        candidate.originContent shouldBeSameInstanceAs origin
    }

    test("custom and context-free candidates are never decorated") {
        val candidate = object : SuggestionCandidate by WordSuggestionCandidate(text = "custom") {}

        candidate.bindOriginContent(content("custom")) shouldBeSameInstanceAs candidate
        candidate.originContent.shouldBeNull()
    }
})

private fun content(text: String) = EditorContent(
    text = text,
    offset = 0,
    localSelection = EditorRange.cursor(text.length),
    localComposing = EditorRange.Unspecified,
    localCurrentWord = EditorRange(0, text.length),
)
