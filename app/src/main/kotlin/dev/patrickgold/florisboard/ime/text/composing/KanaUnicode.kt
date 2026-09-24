/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.text.composing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("kana-unicode")
class KanaUnicode : Composer {
    override val id: String = "kana-unicode"
    override val label: String = "Kana Unicode"
    override val toRead: Int = 1

    val sticky: Boolean = false

    companion object {
        private val dakuten = mapOf(
            'う' to 'ゔ',

            'か' to 'が',
            'き' to 'ぎ',
            'く' to 'ぐ',
            'け' to 'げ',
            'こ' to 'ご',

            'さ' to 'ざ',
            'し' to 'じ',
            'す' to 'ず',
            'せ' to 'ぜ',
            'そ' to 'ぞ',

            'た' to 'だ',
            'ち' to 'ぢ',
            'つ' to 'づ',
            'て' to 'で',
            'と' to 'ど',

            'は' to 'ば',
            'ひ' to 'び',
            'ふ' to 'ぶ',
            'へ' to 'べ',
            'ほ' to 'ぼ',

            'ウ' to 'ヴ',

            'カ' to 'ガ',
            'キ' to 'ギ',
            'ク' to 'グ',
            'ケ' to 'ゲ',
            'コ' to 'ゴ',

            'サ' to 'ザ',
            'シ' to 'ジ',
            'ス' to 'ズ',
            'セ' to 'ゼ',
            'ソ' to 'ゾ',

            'タ' to 'ダ',
            'チ' to 'ヂ',
            'ツ' to 'ヅ',
            'テ' to 'デ',
            'ト' to 'ド',

            'ハ' to 'バ',
            'ヒ' to 'ビ',
            'フ' to 'ブ',
            'ヘ' to 'ベ',
            'ホ' to 'ボ',

            'ワ' to 'ヷ',
            'ヰ' to 'ヸ',
            'ヱ' to 'ヹ',
            'ヲ' to 'ヺ',

            'ゝ' to 'ゞ',
            'ヽ' to 'ヾ',
        )

        private val handakuten = mapOf(
            'は' to 'ぱ',
            'ひ' to 'ぴ',
            'ふ' to 'ぷ',
            'へ' to 'ぺ',
            'ほ' to 'ぽ',

            'ハ' to 'パ',
            'ヒ' to 'ピ',
            'フ' to 'プ',
            'ヘ' to 'ペ',
            'ホ' to 'ポ',
        )

        private val smallKana = mapOf(
            'あ' to "ぁ",
            'い' to "ぃ",
            'え' to "ぇ",
            'う' to "ぅ",
            'お' to "ぉ",

            'か' to "ゕ",
            'け' to "ゖ",

            'つ' to "っ",

            'や' to "ゃ",
            'ゆ' to "ゅ",
            'よ' to "ょ",

            'わ' to "ゎ",
            'ゐ' to "𛅐",
            'ゑ' to "𛅑",
            'を' to "𛅒",

            'ア' to "ァ",
            'イ' to "ィ",
            'エ' to "ェ",
            'ウ' to "ゥ",
            'オ' to "ォ",

            'カ' to "ヵ",
            'ク' to "ㇰ",
            'ケ' to "ヶ",

            'シ' to "ㇱ",
            'ス' to "ㇲ",

            'ツ' to "ッ",
            'ト' to "ㇳ",

            'ヌ' to "ㇴ",

            'ハ' to "ㇵ",
            'ヒ' to "ㇶ",
            'フ' to "ㇷ",
            'ヘ' to "ㇸ",
            'ホ' to "ㇹ",

            'ム' to "ㇺ",

            'ヤ' to "ャ",
            'ユ' to "ュ",
            'ヨ' to "ョ",

            'ラ' to "ㇻ",
            'リ' to "ㇼ",
            'ル' to "ㇽ",
            'レ' to "ㇾ",
            'ロ' to "ㇿ",

            'ワ' to "ヮ",
            'ヰ' to "𛅤",
            'ヱ' to "𛅥",
            'ヲ' to "𛅦",

            'ン' to "𛅧",
        )

        private val reverseDakuten = dakuten.entries.associate { (base, transformed) -> transformed to base }
        private val reverseHandakuten = handakuten.entries.associate { (base, transformed) -> transformed to base }

        // Supplementary small kana span two Char values, so this Char-based composer cannot toggle them back.
        private val reverseSmallKana = smallKana.mapNotNull { (base, transformed) ->
            transformed.singleOrNull()?.let { it to base }
        }.toMap()

        private const val SmallKanaSentinel = '〓'
    }

    private fun isDakuten(char: Char) = char == '゙' || char == '゛' || char == 'ﾞ'

    private fun isHandakuten(char: Char) = char == '゚' || char == '゜' || char == 'ﾟ'

    private fun isComposingCharacter(char: Char) = char == '゙' || char == '゚'

    private fun getBaseCharacter(char: Char) =
        reverseDakuten[char] ?: reverseHandakuten[char] ?: reverseSmallKana[char] ?: char

    private fun <T : Any> handleTransform(
        lastChar: Char,
        input: Char,
        transforms: Map<Char, T>,
        reverse: Map<Char, Char>,
        appendOnMiss: Boolean,
    ): Pair<Int, String> {
        val baseChar = getBaseCharacter(lastChar)
        val transformed = if (sticky) transforms[baseChar] else reverse[lastChar] ?: transforms[baseChar]
        return when {
            transformed != null -> 1 to transformed.toString()
            isComposingCharacter(lastChar) && isComposingCharacter(input) ->
                1 to if (lastChar == input && !sticky) "" else input.toString()
            else -> 0 to if (appendOnMiss) input.toString() else ""
        }
    }

    override fun getActions(precedingText: String, toInsert: String): Pair<Int, String> {
        val input = toInsert.firstOrNull() ?: return 0 to toInsert
        if (precedingText.isEmpty()) {
            return if (input == SmallKanaSentinel || isComposingCharacter(input)) {
                0 to ""
            } else {
                0 to toInsert
            }
        }
        val lastChar = precedingText.last()
        return when {
            isDakuten(input) -> handleTransform(lastChar, input, dakuten, reverseDakuten, true)
            isHandakuten(input) -> handleTransform(lastChar, input, handakuten, reverseHandakuten, true)
            input == SmallKanaSentinel -> handleTransform(lastChar, input, smallKana, reverseSmallKana, false)
            else -> 0 to toInsert
        }
    }
}
