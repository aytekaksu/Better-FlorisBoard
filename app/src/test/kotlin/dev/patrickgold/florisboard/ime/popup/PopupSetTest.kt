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

package dev.patrickgold.florisboard.ime.popup

import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.DefaultComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyHintConfiguration
import dev.patrickgold.florisboard.ime.text.key.KeyHintMode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun key(id: Char): KeyData = TextKeyData(code = id.code, label = id.toString())

private fun popupSource(main: AbstractKeyData?, vararg relevant: AbstractKeyData) =
    PopupSet(main = main, relevant = relevant.toList())

private fun populatedPopupSet(hasSymbol: Boolean, hasNumber: Boolean) = MutablePopupSet().apply {
    merge(popupSource(key('m'), key('a'), key('b')), DefaultComputingEvaluator)
    if (hasSymbol) symbolHint = key('s')
    if (hasNumber) numberHint = key('n')
    mergeSymbolHint(popupSource(key('d'), key('c')), DefaultComputingEvaluator)
    mergeNumberHint(popupSource(key('f'), key('e')), DefaultComputingEvaluator)
}

private fun PopupKeys<KeyData>.priorityLabels() = (1..prioritizedCount).joinToString(separator = "") { this[-it].label }

private fun PopupKeys<KeyData>.otherLabels() =
    (0 until size - prioritizedCount).joinToString(separator = "") { this[it].label }

private object NullKeyData : AbstractKeyData {
    override fun compute(evaluator: ComputingEvaluator) = null

    override fun asString(isForDisplay: Boolean) = ""
}

class PopupSetTest :
    FunSpec({
        test("all hint modes and missing hints retain their intended order") {
            val priorities = listOf(
                listOf("m", "nm", "mn", "mn"),
                listOf("sm", "snm", "smn", "smn"),
                listOf("ms", "nms", "msn", "mns"),
                listOf("ms", "nms", "msn", "msn"),
            )

            listOf(false, true).forEach { hasSymbol ->
                listOf(false, true).forEach { hasNumber ->
                    listOf(false, true).forEach { mergePopups ->
                        KeyHintMode.entries.forEachIndexed { symbolIndex, symbolMode ->
                            KeyHintMode.entries.forEachIndexed { numberIndex, numberMode ->
                                val config = KeyHintConfiguration(symbolMode, numberMode, mergePopups)
                                val keys = populatedPopupSet(hasSymbol, hasNumber).getPopupKeys(config)
                                withClue(
                                    "symbol=$symbolMode/$hasSymbol number=$numberMode/$hasNumber merge=$mergePopups",
                                ) {
                                    keys.priorityLabels() shouldBe priorities[
                                        if (hasSymbol) symbolIndex else 0,
                                    ][if (hasNumber) numberIndex else 0]
                                    keys.hint?.label shouldBe when {
                                        hasSymbol && symbolMode != KeyHintMode.DISABLED -> "s"
                                        hasNumber && numberMode != KeyHintMode.DISABLED -> "n"
                                        else -> null
                                    }
                                    keys.otherLabels() shouldBe buildString {
                                        append("ab")
                                        if (mergePopups && hasSymbol && symbolMode != KeyHintMode.DISABLED) append("cd")
                                        if (mergePopups && hasNumber && numberMode != KeyHintMode.DISABLED) append("ef")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        test("accent mode promotes one relevant popup only when main is absent") {
            fun resolve(
                symbolMode: KeyHintMode,
                numberMode: KeyHintMode,
                withRelevant: Boolean = true,
            ): PopupKeys<KeyData> {
                val set = MutablePopupSet()
                if (withRelevant) set.merge(popupSource(null, key('a'), key('b')), DefaultComputingEvaluator)
                set.symbolHint = key('s')
                set.numberHint = key('n')
                return set.getPopupKeys(KeyHintConfiguration(symbolMode, numberMode, false))
            }

            listOf(
                Triple(resolve(KeyHintMode.ACCENT_PRIORITY, KeyHintMode.DISABLED), "as", "b"),
                Triple(resolve(KeyHintMode.DISABLED, KeyHintMode.ACCENT_PRIORITY), "an", "b"),
                Triple(resolve(KeyHintMode.ACCENT_PRIORITY, KeyHintMode.ACCENT_PRIORITY), "asn", "b"),
                Triple(resolve(KeyHintMode.DISABLED, KeyHintMode.DISABLED), "", "ab"),
                Triple(resolve(KeyHintMode.ACCENT_PRIORITY, KeyHintMode.ACCENT_PRIORITY, false), "sn", ""),
            ).forEach { (keys, priority, other) ->
                keys.priorityLabels() shouldBe priority
                keys.otherLabels() shouldBe other
            }
        }

        test("merges preserve order, skip null computations, and invalidate cached results") {
            val set = MutablePopupSet()
            set.merge(popupSource(key('m'), key('a'), key('b')), DefaultComputingEvaluator)
            val disabled = KeyHintConfiguration.HINTS_DISABLED
            set.getPopupKeys(disabled).let { keys ->
                keys.priorityLabels() shouldBe "m"
                keys.otherLabels() shouldBe "ab"
            }

            set.merge(popupSource(key('x'), key('y'), NullKeyData), DefaultComputingEvaluator)
            set.merge(popupSource(NullKeyData, NullKeyData), DefaultComputingEvaluator)
            set.getPopupKeys(disabled).let { keys ->
                keys.priorityLabels() shouldBe "m"
                keys.otherLabels() shouldBe "abyx"
            }

            val hinted = KeyHintConfiguration(KeyHintMode.HINT_PRIORITY, KeyHintMode.DISABLED, false)
            set.symbolHint = key('s')
            val cached = set.getPopupKeys(hinted)
            (set.getPopupKeys(hinted) === cached) shouldBe true
            set.symbolHint = key('q')
            set.getPopupKeys(hinted).priorityLabels() shouldBe "qm"

            set.clear()
            set.merge(popupSource(key('z')), DefaultComputingEvaluator)
            set.getPopupKeys(hinted).let { keys ->
                keys.priorityLabels() shouldBe "z"
                keys.otherLabels() shouldBe ""
                keys.hint shouldBe null
            }
        }

        test("popup keys map negative priorities and nonnegative remaining keys") {
            val keys = PopupKeys(null, listOf(key('p'), key('q'), key('r')), listOf(key('x'), key('y')))

            keys.size shouldBe 5
            keys.prioritizedCount shouldBe 3
            keys.isNotEmpty() shouldBe true
            listOf(-1 to 'p', -2 to 'q', -3 to 'r', 0 to 'x', 1 to 'y').forEach { (index, label) ->
                keys[index].label shouldBe label.toString()
            }
            listOf(-4, 2, Int.MIN_VALUE, Int.MAX_VALUE).forEach { index ->
                shouldThrow<IndexOutOfBoundsException> { keys[index] }
            }
            PopupKeys<KeyData>(null, emptyList(), emptyList()).isNotEmpty() shouldBe false
        }
    })
