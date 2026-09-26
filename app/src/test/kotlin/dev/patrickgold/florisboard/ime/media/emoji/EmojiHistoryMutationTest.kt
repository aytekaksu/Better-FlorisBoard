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

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class EmojiHistoryMutationTest : FunSpec({
    test("used emoji keeps manual order and follows automatic sort direction") {
        val a = emoji("a")
        val b = emoji("b")
        val c = emoji("c")
        val expected = mapOf(
            EmojiHistory.UpdateStrategy.AUTO_SORT_PREPEND to listOf(b, a, c),
            EmojiHistory.UpdateStrategy.AUTO_SORT_APPEND to listOf(a, c, b),
            EmojiHistory.UpdateStrategy.MANUAL_SORT_PREPEND to listOf(a, b, c),
            EmojiHistory.UpdateStrategy.MANUAL_SORT_APPEND to listOf(a, b, c),
        )
        for (strategy in EmojiHistory.UpdateStrategy.entries) {
            for (isPinned in listOf(true, false)) {
                runTest {
                    val fixture = HistoryFixture()
                    val initial = listOf(a, b, c)
                    fixture.prefs.emoji.historyData.set(
                        if (isPinned) EmojiHistory(initial, emptyList()) else EmojiHistory(emptyList(), initial)
                    )
                    fixture.prefs.emoji.historyPinnedUpdateStrategy.set(strategy)
                    fixture.prefs.emoji.historyRecentUpdateStrategy.set(strategy)

                    EmojiHistoryHelper.markEmojiUsed(fixture.prefs, b)

                    val history = fixture.prefs.emoji.historyData.get()
                    (if (isPinned) history.pinned else history.recent) shouldBe expected.getValue(strategy)
                }
            }
        }
    }

    test("usage respects size limits and gives pinned duplicates priority") {
        runTest {
            val fixture = HistoryFixture()
            val a = emoji("a")
            val b = emoji("b")
            val c = emoji("c")
            val d = emoji("d")
            fixture.prefs.emoji.historyData.set(EmojiHistory(listOf(a, b, c), listOf(a, b)))
            fixture.prefs.emoji.historyPinnedUpdateStrategy.set(EmojiHistory.UpdateStrategy.AUTO_SORT_APPEND)
            fixture.prefs.emoji.historyRecentUpdateStrategy.set(EmojiHistory.UpdateStrategy.AUTO_SORT_PREPEND)
            fixture.prefs.emoji.historyPinnedMaxSize.set(2)
            fixture.prefs.emoji.historyRecentMaxSize.set(2)

            EmojiHistoryHelper.markEmojiUsed(fixture.prefs, d)
            fixture.history() shouldBe EmojiHistory(listOf(b, c), listOf(d, a))

            fixture.prefs.emoji.historyPinnedMaxSize.set(0)
            fixture.prefs.emoji.historyRecentMaxSize.set(0)
            fixture.prefs.emoji.historyData.set(EmojiHistory(listOf(b, c), listOf(b, d, a)))

            EmojiHistoryHelper.markEmojiUsed(fixture.prefs, b)
            fixture.history() shouldBe EmojiHistory(listOf(c, b), listOf(b, d, a))
            EmojiHistoryHelper.markEmojiUsed(fixture.prefs, emoji("new"))
            fixture.history() shouldBe EmojiHistory(listOf(c, b), listOf(emoji("new"), b, d, a))
        }
    }

    test("pin, unpin, move, remove, and delete keep their existing list behavior") {
        runTest {
            val fixture = HistoryFixture()
            val a = emoji("a")
            val b = emoji("b")
            val c = emoji("c")
            val d = emoji("d")
            fixture.prefs.emoji.historyData.set(EmojiHistory(listOf(a, b), listOf(c, d)))

            EmojiHistoryHelper.pinEmoji(fixture.prefs, c)
            fixture.history() shouldBe EmojiHistory(listOf(c, a, b), listOf(d))
            EmojiHistoryHelper.unpinEmoji(fixture.prefs, b)
            fixture.history() shouldBe EmojiHistory(listOf(c, a), listOf(b, d))
            EmojiHistoryHelper.moveEmoji(fixture.prefs, a, -1)
            fixture.history() shouldBe EmojiHistory(listOf(a, c), listOf(b, d))
            EmojiHistoryHelper.removeEmoji(fixture.prefs, c)
            fixture.history() shouldBe EmojiHistory(listOf(a), listOf(b, d))
            EmojiHistoryHelper.deleteHistory(fixture.prefs)
            fixture.history() shouldBe EmojiHistory(listOf(a), emptyList())
            EmojiHistoryHelper.deletePinned(fixture.prefs)
            fixture.history() shouldBe EmojiHistory.Empty
        }
    }

    test("disabled history and absent or zero-offset actions do not change stored history") {
        runTest {
            val fixture = HistoryFixture()
            val a = emoji("a")
            val b = emoji("b")
            val absent = emoji("absent")
            val initial = EmojiHistory(listOf(a), listOf(b))
            fixture.prefs.emoji.historyData.set(initial)
            fixture.prefs.emoji.historyEnabled.set(false)

            EmojiHistoryHelper.markEmojiUsed(fixture.prefs, absent)
            EmojiHistoryHelper.pinEmoji(fixture.prefs, b)
            EmojiHistoryHelper.unpinEmoji(fixture.prefs, a)
            EmojiHistoryHelper.moveEmoji(fixture.prefs, a, 1)
            EmojiHistoryHelper.removeEmoji(fixture.prefs, a)
            EmojiHistoryHelper.deleteHistory(fixture.prefs)
            EmojiHistoryHelper.deletePinned(fixture.prefs)
            fixture.history() shouldBe initial

            fixture.prefs.emoji.historyEnabled.set(true)
            EmojiHistoryHelper.moveEmoji(fixture.prefs, a, 0)
            EmojiHistoryHelper.pinEmoji(fixture.prefs, absent)
            EmojiHistoryHelper.removeEmoji(fixture.prefs, absent)
            fixture.history() shouldBe initial
        }
    }
})

private class HistoryFixture {
    private val dataStore = jetprefDataStoreOf(FlorisPreferenceModel::class)
    val prefs by dataStore

    fun history() = prefs.emoji.historyData.get()
}

private fun emoji(id: String) = Emoji("fixture-$id", "", emptyList())
