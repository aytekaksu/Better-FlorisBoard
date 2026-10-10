/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.text.composing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("hangul-unicode")
class HangulUnicode : Composer {
    override val id: String = "hangul-unicode"
    override val label: String = "Hangul Unicode"
    override val toRead: Int = 1

    // These private tables are also configurable extension fields.
    // Initial consonants, ordered for syllable creation
    private val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    // Medial vowels, ordered for syllable creation
    private val medials = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    // Final consonants (including none), ordered for syllable creation
    private val finals = "_ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    private val medialComp = mapOf(
        'ㅗ' to listOfNotNull("ㅏㅐㅣ", "ㅘㅙㅚ"),
        'ㅜ' to listOfNotNull("ㅓㅔㅣ", "ㅝㅞㅟ"),
        'ㅡ' to listOfNotNull("ㅣ", "ㅢ"),
    )

    private val finalComp = mapOf(
        'ㄱ' to listOfNotNull("ㅅ", "ㄳ"),
        'ㄴ' to listOfNotNull("ㅈㅎ", "ㄵㄶ"),
        'ㄹ' to listOfNotNull("ㄱㅁㅂㅅㅌㅍㅎ", "ㄺㄻㄼㄽㄾㄿㅀ"),
        'ㅂ' to listOfNotNull("ㅅ", "ㅄ"),
    )

    private fun reverseComp(map: Map<Char, List<String>>): Map<Char, List<Char>> {
        val ret = mutableMapOf<Char, List<Char>>()
        for ((first, v) in map) {
            val (seconds, comps) = v
            for (i in seconds.indices) {
                ret[comps[i]] = listOf(first, seconds[i])
            }
        }
        return ret
    }

    private val finalCompRev = reverseComp(finalComp)
    private val medialCompRev = reverseComp(medialComp)

    private fun syllable(ini: Int, med: Int, fin: Int): Char =
        (ini * 588 + med * 28 + fin + 44032).toChar()

    private fun compound(map: Map<Char, List<String>>, first: Char, second: Char): Char? {
        val composition = map[first] ?: return null
        val index = composition[0].indexOf(second)
        return if (index >= 0) composition[1][index] else null
    }

    override fun getActions(precedingText: String, toInsert: String): Pair<Int, String> {
        val c = toInsert.firstOrNull()
        if (precedingText.isEmpty() || c == null) {
            return 0 to toInsert
        }
        val lastChar = precedingText.last()
        val lastOrd = lastChar.code

        if (lastChar in initials && c in medials) {
            return 1 to "${syllable(initials.indexOf(lastChar), medials.indexOf(c), 0)}"
        } else if (lastOrd in 44032..55203) { // syllable
            val offset = lastOrd - 44032
            val ini = offset / 588
            val med = offset % 588 / 28
            val fin = offset % 28

            // Underscore marks an absent final consonant; it is not composed input.
            if (c == '_') {
                return 0 to toInsert
            }

            // Add a final consonant.
            if (fin == 0 && c in finals) {
                return 1 to "${syllable(ini, med, finals.indexOf(c))}"
            }

            compound(finalComp, finals[fin], c)?.let { combined ->
                return 1 to "${syllable(ini, med, finals.indexOf(combined))}"
            }

            // Move a simple final, or the second half of a compound final, to the next syllable.
            val finalParts = finalCompRev[finals[fin]]
            if ((fin != 0 || finalParts != null) && c in medials) {
                val previousFinal = if (finalParts == null) 0 else finals.indexOf(finalParts[0])
                val nextInitial = initials.indexOf(finalParts?.get(1) ?: finals[fin])
                return 1 to "${syllable(ini, med, previousFinal)}${syllable(nextInitial, medials.indexOf(c), 0)}"
            }

            val medial = medialComp[medials[med]]
            if (medial != null && c in medial[0] && fin == 0) {
                return 1 to "${syllable(ini, medials.indexOf(medial[1][medial[0].indexOf(c)]), 0)}"
            }
        } else {
            val combined = compound(medialComp, lastChar, c) ?: compound(finalComp, lastChar, c)
            if (combined != null) return 1 to combined.toString()
        }

        return 0 to toInsert
    }
}
