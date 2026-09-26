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

package dev.patrickgold.florisboard.ime.media.emoji

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class EmojiSuggestionRankingTest : FunSpec({
    test("scores names and keywords while keeping source order for ties") {
        val emojis = listOf(
            Emoji("low", "caterpillar", emptyList()),
            Emoji("tie-first", "cat", emptyList()),
            Emoji("keyword", "dog", listOf("CAT")),
            Emoji("top", "cats", listOf("cat")),
            Emoji("tie-second", "CAT", emptyList()),
            Emoji("miss", "dog", emptyList()),
        )

        runBlocking { rankEmojiSuggestions(emojis, "cat", 5) }.map(Emoji::value) shouldBe
            listOf("top", "tie-first", "tie-second", "keyword", "low")
        runBlocking { rankEmojiSuggestions(emojis, "cat", 2) }.map(Emoji::value) shouldBe
            listOf("top", "tie-first")
    }

    test("limits before discarding nonpositive scores") {
        val emojis = listOf(
            Emoji("empty-name", "", emptyList()),
            Emoji("keyword", "dog", listOf("anything")),
        )

        // An empty query gives an empty name a NaN score, which sorts first but is filtered out.
        runBlocking { rankEmojiSuggestions(emojis, "", 1) } shouldBe emptyList()
        runBlocking { rankEmojiSuggestions(emojis, "", 2) }.map(Emoji::value) shouldBe listOf("keyword")
    }

    test("returns no candidates for misses or a zero limit") {
        val emojis = listOf(Emoji("one", "dog", listOf("puppy")))

        runBlocking { rankEmojiSuggestions(emojis, "cat", 5) } shouldBe emptyList()
        runBlocking { rankEmojiSuggestions(emojis, "dog", 0) } shouldBe emptyList()
    }

    test("cancelled scoring stops before reading another emoji and publishes nothing") {
        val visited = mutableListOf<Int>()
        var published: List<Emoji>? = null

        runBlocking {
            lateinit var scan: Job
            val emojis = object : AbstractList<Emoji>() {
                override val size = 3

                override fun get(index: Int): Emoji {
                    visited += index
                    if (index == 0) scan.cancel()
                    return Emoji(index.toString(), "cat", emptyList())
                }
            }
            scan = launch { published = rankEmojiSuggestions(emojis, "cat", 3) }
            scan.join()
            scan.isCancelled shouldBe true
        }

        visited shouldBe listOf(0)
        published shouldBe null
    }
})
