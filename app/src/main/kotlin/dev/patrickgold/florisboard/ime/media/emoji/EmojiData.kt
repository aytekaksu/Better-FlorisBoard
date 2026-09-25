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

import android.content.Context
import dev.patrickgold.florisboard.lib.FlorisLocale
import io.github.reactivecircus.cache4k.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.*

private typealias EmojiDataByCategoryImpl = EnumMap<EmojiCategory, MutableList<EmojiSet>>
private typealias EmojiDataBySkinToneImpl = EnumMap<EmojiSkinTone, MutableList<Emoji>>
private const val ROOT_EMOJI_ASSET = "ime/media/emoji/root.txt"
private const val ENGLISH_EMOJI_ASSET = "ime/media/emoji/en.txt"
typealias EmojiDataByCategory = Map<EmojiCategory, List<EmojiSet>>
typealias EmojiDataBySkinTone = Map<EmojiSkinTone, List<Emoji>>

internal suspend fun <T> runEmojiAssetIo(block: () -> T): T = runInterruptible(Dispatchers.IO, block)

internal fun rootEmojiAssetLine(line: String): String =
    if (';' in line) "${line.substringBefore(';')};;" else line

data class EmojiData(
    val byCategory: EmojiDataByCategory,
    val bySkinTone: EmojiDataBySkinTone,
) {
    companion object {
        private val cache = Cache.Builder<String, EmojiData>().build()
        val Fallback = empty()

        private fun newByCategory(): EmojiDataByCategoryImpl {
            return EmojiDataByCategoryImpl(EmojiCategory::class.java).also { map ->
                for (category in EmojiCategory.entries) {
                    map[category] = mutableListOf()
                }
            }
        }

        private fun newBySkinTone(): EmojiDataBySkinToneImpl {
            return EmojiDataBySkinToneImpl(EmojiSkinTone::class.java).also { map ->
                for (skinTone in EmojiSkinTone.entries) {
                    map[skinTone] = mutableListOf()
                }
            }
        }

        fun empty(): EmojiData {
            return EmojiData(newByCategory(), newBySkinTone())
        }

        suspend fun get(context: Context, path: String): EmojiData = withContext(Dispatchers.IO) {
            getCached(context, path)
        }

        suspend fun get(context: Context, locale: FlorisLocale): EmojiData = withContext(Dispatchers.IO) {
            val path = runEmojiAssetIo { resolveEmojiAssetPath(context, locale) } ?: return@withContext empty()
            getCached(context, path)
        }

        private suspend fun getCached(context: Context, path: String): EmojiData = cache.get(path) {
            runEmojiAssetIo { loadEmojiDataMap(context, path) }
        }

        private fun loadEmojiDataMap(context: Context, path: String): EmojiData {
            val isRoot = path == ROOT_EMOJI_ASSET
            val sourcePath = if (isRoot) ENGLISH_EMOJI_ASSET else path
            val byCategory = newByCategory()
            val bySkinTone = newBySkinTone()

            var ec: EmojiCategory? = null
            var emojiEditorList: MutableList<Emoji>? = null

            fun commitEmojiEditorList() {
                emojiEditorList?.let { byCategory[ec]!!.add(EmojiSet(it)) }
                emojiEditorList = null
            }

            context.assets.open(sourcePath).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (rawLine in lines) {
                    val line = if (isRoot) rootEmojiAssetLine(rawLine) else rawLine
                    if (line.startsWith("#")) {
                        // Comment line
                    } else if (line.startsWith("[")) {
                        commitEmojiEditorList()
                        ec = EmojiCategory.entries.find { it.id == line.slice(1 until (line.length - 1)) }
                    } else if (line.trim().isEmpty() || ec == null) {
                        // Empty line
                        continue
                    } else {
                        if (!line.startsWith("\t")) {
                            commitEmojiEditorList()
                        }
                        // Assume it is a data line
                        val data = line.split(";")
                        if (data.size == 3) {
                            val base = emojiEditorList?.first()
                            val emoji = Emoji(
                                value = data[0].trim(),
                                name = base?.name ?: data[1].trim(),
                                keywords = data[2].split("|").map { it.trim() },
                            )
                            if (emojiEditorList != null) {
                                emojiEditorList!!.add(emoji)
                            } else {
                                emojiEditorList = mutableListOf(emoji)
                            }
                        }
                    }
                }
                commitEmojiEditorList()
            }

            for (category in byCategory.keys) {
                for (emojiSet in byCategory[category]!!) {
                    if (emojiSet.emojis.size == 1) {
                        // No variations provided, we fallback to using the base for all skin tones
                        val base = emojiSet.emojis.first()
                        for (skinTone in EmojiSkinTone.entries) {
                            bySkinTone[skinTone]!!.add(base)
                        }
                        continue
                    }
                    for (emoji in emojiSet.emojis) {
                        bySkinTone[emoji.skinTone]!!.add(emoji)
                    }
                }
            }

            return EmojiData(byCategory, bySkinTone)
        }

        /** Finds a bundled locale file, preferring variant, then country, then language. */
        private fun resolveEmojiAssetPath(context: Context, locale: FlorisLocale): String? {
            val emojiAssets = context.assets.list("ime/media/emoji/")!!.toList()
            val makePath = { file: String -> "ime/media/emoji/$file" }
            val language = locale.language.lowercase()
            val country = locale.country.takeIf { it.isNotBlank() }
            val variant = locale.variant.takeIf { it.isNotBlank() }
            if (variant != null && country != null) {
                "${language}_${country}_${variant}.txt".takeIf { emojiAssets.contains(it) }?.let {
                    return makePath(it)
                }
            }
            if (country != null) {
                "${language}_${country}.txt".takeIf { emojiAssets.contains(it) }?.let { return makePath(it) }
            }
            "${language}.txt".takeIf { emojiAssets.contains(it) }?.let {
                return makePath(it)
            }
            return null
        }
    }
}
