/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

class SmartbarCandidateSelectionTest : FunSpec({
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
