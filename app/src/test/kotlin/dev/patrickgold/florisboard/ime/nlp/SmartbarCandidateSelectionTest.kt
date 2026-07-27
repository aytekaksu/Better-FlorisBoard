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

import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SmartbarCandidateSelectionTest : FunSpec({
    test("word candidates replace a fresh clipboard suggestion while typing") {
        selectSmartbarCandidates(
            isWordBeingTyped = true,
            wordCandidates = listOf("type", "typing", "typed"),
            clipboardCandidates = listOf("clipboard"),
        ) shouldBe listOf("type", "typing", "typed")
    }

    test("clipboard suggestion remains preferred in a blank editor") {
        selectSmartbarCandidates(
            isWordBeingTyped = false,
            wordCandidates = listOf("next", "word"),
            clipboardCandidates = listOf("clipboard"),
        ) shouldBe listOf("clipboard")
    }

    test("clipboard suggestion stays hidden when the active word has no candidates") {
        selectSmartbarCandidates(
            isWordBeingTyped = true,
            wordCandidates = emptyList(),
            clipboardCandidates = listOf("clipboard"),
        ) shouldBe emptyList()
    }

    test("word candidates remain available without a clipboard suggestion") {
        selectSmartbarCandidates(
            isWordBeingTyped = false,
            wordCandidates = listOf("next", "word"),
            clipboardCandidates = emptyList(),
        ) shouldBe listOf("next", "word")
    }

    test("built-in emoji suggestions retain their presentation semantics") {
        EmojiSuggestionCandidate(
            emoji = Emoji("🙂", "slightly smiling face", emptyList()),
            showName = false,
        ).kind shouldBe SuggestionCandidateKind.EMOJI
    }
})
