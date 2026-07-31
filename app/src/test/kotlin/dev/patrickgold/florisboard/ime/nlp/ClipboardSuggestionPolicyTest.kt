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

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class ClipboardSuggestionPolicyTest :
    FunSpec({
        test("bounds display text without changing the pasted clipboard item") {
            val fullText = "x".repeat(50_000)
            val item = ClipboardItem.text(fullText)

            val displayText = boundedClipboardSuggestionText(item.stringRepresentation())

            displayText.length shouldBe MAX_CLIPBOARD_SUGGESTION_DISPLAY_CHARS
            displayText.last() shouldBe '…'
            item.stringRepresentation() shouldBe fullText
        }

        test("bounds extraction input and honors the candidate budget") {
            val text = buildString {
                append("first@example.com https://example.org +381641234567 ")
                append("x".repeat(50_000))
                append(" hidden@example.com")
            }

            val matches = findClipboardSuggestionMatches(text, maxMatches = 2)

            matches shouldHaveSize 2
            matches.map(ClipboardSuggestionMatch::value) shouldBe
                listOf("first@example.com", "https://example.org")
            matches.all { it.range.last < MAX_CLIPBOARD_SUGGESTION_SCAN_CHARS } shouldBe true
            matches.first().toString() shouldBe "ClipboardSuggestionMatch(value=<redacted>)"
            findClipboardSuggestionMatches(text, maxMatches = 0) shouldBe emptyList()
        }

        test("candidate budget includes the full source item") {
            val source = ClipboardItem.text(
                "first@example.com https://example.org +381641234567",
            )

            buildClipboardSuggestionItems(source, 0) shouldBe emptyList()
            buildClipboardSuggestionItems(source, -1) shouldBe emptyList()
            buildClipboardSuggestionItems(source, 1).single() shouldBeSameInstanceAs source
            buildClipboardSuggestionItems(source, 3).let { candidates ->
                candidates shouldHaveSize 3
                candidates.first() shouldBeSameInstanceAs source
                candidates.map(ClipboardItem::stringRepresentation) shouldContainExactly
                    listOf(
                        source.stringRepresentation(),
                        "first@example.com",
                        "https://example.org",
                    )
            }
        }

        test("whole matches duplicates and overlaps do not create extra candidates") {
            findClipboardSuggestionMatches("first@example.com", maxMatches = 16) shouldBe
                emptyList()

            val matches = findClipboardSuggestionMatches(
                "same@example.com same@example.com https://example.org",
                maxMatches = 16,
            )
            matches.map(ClipboardSuggestionMatch::value) shouldContainExactly
                listOf("same@example.com", "https://example.org")
        }

        test("suppression follows an exact clipboard copy rather than its id or text") {
            val suppressed = ClipboardItem.text("same").copy(id = 42)
            val historyDisabledRecopy = ClipboardItem.text("same")
            val deduplicatedRecopy = suppressed.copy(creationTimestampMs = suppressed.creationTimestampMs + 1)

            isNewClipboardSuggestionCopy(suppressed, null) shouldBe true
            isNewClipboardSuggestionCopy(suppressed, suppressed) shouldBe false
            isNewClipboardSuggestionCopy(historyDisabledRecopy, suppressed) shouldBe true
            isNewClipboardSuggestionCopy(deduplicatedRecopy, suppressed) shouldBe true
        }
    })
