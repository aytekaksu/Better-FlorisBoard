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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class EmojiSupportFilterTest : FunSpec({
    test("keeps category and variation order while pruning unsupported sets") {
        val source = linkedMapOf(
            EmojiCategory.SMILEYS_EMOTION to listOf(
                EmojiSet(listOf(emoji("base"), emoji("variant-a"), emoji("variant-b"))),
                EmojiSet(listOf(emoji("drop"))),
            ),
            EmojiCategory.PEOPLE_BODY to listOf(EmojiSet(listOf(emoji("keep")))),
            EmojiCategory.SYMBOLS to listOf(EmojiSet(listOf(emoji("drop-too")))),
        )
        val visited = mutableListOf<String>()

        val result = runBlocking {
            filterEmojiMappings(source) { candidate ->
                visited += candidate.value
                candidate.value in setOf("variant-a", "variant-b", "keep")
            }
        }

        result.keys.toList() shouldBe source.keys.toList()
        result[EmojiCategory.SMILEYS_EMOTION]?.map { set -> set.emojis.map(Emoji::value) } shouldBe
            listOf(listOf("variant-a", "variant-b"))
        result[EmojiCategory.PEOPLE_BODY]?.map { set -> set.emojis.map(Emoji::value) } shouldBe
            listOf(listOf("keep"))
        result[EmojiCategory.SYMBOLS] shouldBe emptyList()
        visited shouldBe listOf("base", "variant-a", "variant-b", "drop", "keep", "drop-too")
    }

    test("empty pending data retains every category for the palette") {
        val result = runBlocking { filterEmojiMappings(EmojiData.Fallback.byCategory) { true } }

        result.keys.toList() shouldBe EmojiCategory.entries
        result.values.all { it.isEmpty() } shouldBe true
    }

    test("cancelled scan stops before a later set and never returns a partial result") {
        val source = mapOf(
            EmojiCategory.SMILEYS_EMOTION to listOf(
                EmojiSet(listOf(emoji("first"))),
                EmojiSet(listOf(emoji("second"))),
            ),
        )
        val visited = mutableListOf<String>()
        var published: EmojiDataByCategory? = null

        runBlocking {
            val scan = launch {
                published = filterEmojiMappings(source) { candidate ->
                    visited += candidate.value
                    if (candidate.value == "first") cancel()
                    true
                }
            }
            scan.join()
            scan.isCancelled shouldBe true
        }

        visited shouldBe listOf("first")
        published shouldBe null
    }
})

private fun emoji(value: String) = Emoji(value, "", emptyList())
