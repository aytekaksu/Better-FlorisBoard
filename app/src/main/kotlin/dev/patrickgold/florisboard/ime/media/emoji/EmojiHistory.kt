/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
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
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmojiHistory(
    val pinned: List<@Serializable(with = Emoji.ValueOnlySerializer::class) Emoji>,
    val recent: List<@Serializable(with = Emoji.ValueOnlySerializer::class) Emoji>,
) {
    fun edit(): Editor {
        return Editor(pinned.toMutableList(), recent.toMutableList())
    }

    data class Editor(
        val pinned: MutableList<Emoji>,
        val recent: MutableList<Emoji>,
    ) {
        fun build(): EmojiHistory {
            return EmojiHistory(pinned.toList(), recent.toList())
        }
    }

    enum class UpdateStrategy(val isAutomatic: Boolean, val isPrepend: Boolean) {
        AUTO_SORT_PREPEND(isAutomatic = true, isPrepend = true),
        AUTO_SORT_APPEND(isAutomatic = true, isPrepend = false),
        MANUAL_SORT_PREPEND(isAutomatic = false, isPrepend = true),
        MANUAL_SORT_APPEND(isAutomatic = false, isPrepend = false);
    }

    object Serializer : PreferenceSerializer<EmojiHistory> {
        override fun serialize(value: EmojiHistory): String {
            return Json.encodeToString(value)
        }

        override fun deserialize(value: String): EmojiHistory {
            try {
                return Json.decodeFromString(value)
            } catch (e: Exception) {
                flogError { "Failed to deserialize emoji history: error=${e.javaClass.simpleName}" }
                return Empty
            }
        }
    }

    companion object {
        val Empty = EmojiHistory(emptyList(), emptyList())

        @Suppress("ConstPropertyName")
        const val MaxSizeUnlimited: Int = 0
    }
}

object EmojiHistoryHelper {
    private val emojiGuard = Mutex(locked = false)

    private suspend fun updateHistory(
        prefs: FlorisPreferenceModel,
        skipMutation: Boolean = false,
        transform: (EmojiHistory.Editor) -> EmojiHistory,
    ): Unit = emojiGuard.withLock {
        if (!prefs.emoji.historyEnabled.get() || skipMutation) return@withLock
        prefs.emoji.historyData.set(transform(prefs.emoji.historyData.get().edit()))
    }

    suspend fun markEmojiUsed(prefs: FlorisPreferenceModel, emoji: Emoji): Unit = updateHistory(prefs) { dataMut ->
        val pinnedUS = prefs.emoji.historyPinnedUpdateStrategy.get()
        val recentUS = prefs.emoji.historyRecentUpdateStrategy.get()
        val pinnedMaxSize = prefs.emoji.historyPinnedMaxSize.get().let { maxSize ->
            if (maxSize == EmojiHistory.MaxSizeUnlimited) Int.MAX_VALUE else maxSize
        }
        val recentMaxSize = prefs.emoji.historyRecentMaxSize.get().let { maxSize ->
            if (maxSize == EmojiHistory.MaxSizeUnlimited) Int.MAX_VALUE else maxSize
        }

        val pinnedIndex = dataMut.pinned.indexOf(emoji)
        val isPinned = pinnedIndex != -1
        val target = if (isPinned) dataMut.pinned else dataMut.recent
        val index = if (isPinned) pinnedIndex else target.indexOf(emoji)
        val strategy = if (isPinned) pinnedUS else recentUS
        if (index == -1 || strategy.isAutomatic) {
            if (index != -1) target.removeAt(index)
            target.addWithStrategy(strategy, emoji)
        }

        EmojiHistory(
            pinned = dataMut.pinned.takeWithStrategy(pinnedUS, pinnedMaxSize),
            recent = dataMut.recent.takeWithStrategy(recentUS, recentMaxSize),
        )
    }

    suspend fun pinEmoji(prefs: FlorisPreferenceModel, emoji: Emoji): Unit = updateHistory(prefs) { dataMut ->
        val pinnedUS = prefs.emoji.historyPinnedUpdateStrategy.get()
        dataMut.recent.transferTo(dataMut.pinned, pinnedUS, emoji)
        dataMut.build()
    }

    suspend fun unpinEmoji(prefs: FlorisPreferenceModel, emoji: Emoji): Unit = updateHistory(prefs) { dataMut ->
        val recentUS = prefs.emoji.historyRecentUpdateStrategy.get()
        dataMut.pinned.transferTo(dataMut.recent, recentUS, emoji)
        dataMut.build()
    }

    suspend fun moveEmoji(prefs: FlorisPreferenceModel, emoji: Emoji, offset: Int): Unit =
        updateHistory(prefs, skipMutation = offset == 0) { dataMut ->
            val pinnedIndex = dataMut.pinned.indexOf(emoji)
            val target = if (pinnedIndex != -1) dataMut.pinned else dataMut.recent
            val index = if (pinnedIndex != -1) pinnedIndex else target.indexOf(emoji)
            if (index != -1) target.move(index, offset)
            dataMut.build()
        }

    suspend fun removeEmoji(prefs: FlorisPreferenceModel, emoji: Emoji): Unit = updateHistory(prefs) { dataMut ->
        if (!dataMut.pinned.remove(emoji)) dataMut.recent.remove(emoji)
        dataMut.build()
    }

    suspend fun deleteHistory(prefs: FlorisPreferenceModel): Unit = updateHistory(prefs) { dataMut ->
        EmojiHistory(pinned = dataMut.pinned, recent = emptyList())
    }

    suspend fun deletePinned(prefs: FlorisPreferenceModel): Unit = updateHistory(prefs) { dataMut ->
        EmojiHistory(pinned = emptyList(), recent = dataMut.recent)
    }

    private fun MutableList<Emoji>.transferTo(
        destination: MutableList<Emoji>,
        strategy: EmojiHistory.UpdateStrategy,
        emoji: Emoji,
    ) {
        if (remove(emoji)) destination.addWithStrategy(strategy, emoji)
    }

    private fun MutableList<Emoji>.addWithStrategy(strategy: EmojiHistory.UpdateStrategy, emoji: Emoji) {
        if (strategy.isPrepend) {
            add(0, emoji)
        } else {
            add(emoji)
        }
    }

    private fun MutableList<Emoji>.takeWithStrategy(
        strategy: EmojiHistory.UpdateStrategy,
        n: Int,
    ): List<Emoji> {
        return if (strategy.isPrepend) {
            take(n)
        } else {
            takeLast(n)
        }
    }

    private fun MutableList<Emoji>.move(itemIndex: Int, offset: Int) {
        val newIndex = (itemIndex + offset).coerceIn(0..<size)
        val item = removeAt(itemIndex)
        if (newIndex == size) {
            add(item)
        } else {
            add(newIndex, item)
        }
    }
}
