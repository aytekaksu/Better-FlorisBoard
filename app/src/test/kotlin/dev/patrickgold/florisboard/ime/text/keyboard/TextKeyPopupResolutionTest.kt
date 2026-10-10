/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.DefaultComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.KeyboardState
import dev.patrickgold.florisboard.ime.popup.PopupKeys
import dev.patrickgold.florisboard.ime.popup.PopupMapping
import dev.patrickgold.florisboard.ime.popup.PopupSet
import dev.patrickgold.florisboard.ime.text.key.KeyHintConfiguration
import dev.patrickgold.florisboard.ime.text.key.KeyHintMode
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun popupKey(label: Char): TextKeyData = TextKeyData(code = label.code, label = label.toString())

private fun popups(main: Char? = null, vararg relevant: Char): PopupSet<AbstractKeyData> = PopupSet(
    main = main?.let(::popupKey),
    relevant = relevant.map(::popupKey),
)

private fun baseMapping(vararg entries: Pair<KeyVariation, Char>): PopupMapping =
    entries.associate { (variation, label) -> variation to mapOf("a" to popups(label)) }

private fun resolveKey(
    variation: KeyVariation,
    preferred: PopupMapping? = null,
    fallback: PopupMapping? = null,
    data: TextKeyData = popupKey('a'),
    mode: KeyboardMode = KeyboardMode.CHARACTERS,
    symbolHint: KeyData? = null,
    numberHint: KeyData? = null,
): TextKey {
    val key = TextKey(data).apply {
        computedSymbolHint = symbolHint
        computedNumberHint = numberHint
    }
    val keyboard = TextKeyboard(arrayOf(arrayOf(key)), mode, preferred, fallback)
    val state = KeyboardState.new().apply { keyVariation = variation }
    val evaluator = object : ComputingEvaluator by DefaultComputingEvaluator {
        override val keyboard = keyboard
        override val state = state
    }
    key.compute(evaluator)
    return key
}

private fun PopupKeys<KeyData>.priorityLabels() =
    (1..prioritizedCount).joinToString("") { this[-it].label }

private fun PopupKeys<KeyData>.otherLabels() =
    (0 until size - prioritizedCount).joinToString("") { this[it].label }

class TextKeyPopupResolutionTest : FunSpec({
    test("each key variation uses only its ordered fallback chain") {
        val chains = mapOf(
            KeyVariation.PASSWORD to listOf(KeyVariation.PASSWORD, KeyVariation.NORMAL, KeyVariation.ALL),
            KeyVariation.NORMAL to listOf(KeyVariation.NORMAL, KeyVariation.ALL),
            KeyVariation.EMAIL_ADDRESS to listOf(KeyVariation.EMAIL_ADDRESS, KeyVariation.URI, KeyVariation.ALL),
            KeyVariation.URI to listOf(KeyVariation.URI, KeyVariation.ALL),
            KeyVariation.ALL to listOf(KeyVariation.ALL),
        )
        val markers = mapOf(
            KeyVariation.PASSWORD to 'p',
            KeyVariation.NORMAL to 'n',
            KeyVariation.EMAIL_ADDRESS to 'e',
            KeyVariation.URI to 'u',
            KeyVariation.ALL to 'a',
        )

        chains.forEach { (selected, chain) ->
            chain.indices.forEach { fallbackIndex ->
                val missing = chain.take(fallbackIndex).toSet()
                val mapping = baseMapping(*KeyVariation.entries.filterNot { it in missing }
                    .map { it to markers.getValue(it) }.toTypedArray())
                withClue("selected=$selected fallback=${chain[fallbackIndex]}") {
                    resolveKey(selected, preferred = mapping).computedPopups.main?.label shouldBe
                        markers.getValue(chain[fallbackIndex]).toString()
                }
            }
        }
    }

    test("subtype entries beat defaults at the same step, but defaults beat later subtype fallbacks") {
        KeyVariation.entries.forEach { selected ->
            withClue("selected=$selected") {
                resolveKey(
                    selected,
                    preferred = baseMapping(selected to 's'),
                    fallback = baseMapping(selected to 'd'),
                ).computedPopups.main?.label shouldBe "s"
            }
        }

        val chains = listOf(
            KeyVariation.PASSWORD to listOf(KeyVariation.PASSWORD, KeyVariation.NORMAL, KeyVariation.ALL),
            KeyVariation.NORMAL to listOf(KeyVariation.NORMAL, KeyVariation.ALL),
            KeyVariation.EMAIL_ADDRESS to listOf(KeyVariation.EMAIL_ADDRESS, KeyVariation.URI, KeyVariation.ALL),
            KeyVariation.URI to listOf(KeyVariation.URI, KeyVariation.ALL),
        )
        chains.forEach { (selected, chain) ->
            chain.zipWithNext().forEach { (earlier, later) ->
                withClue("selected=$selected default=$earlier subtype=$later") {
                    resolveKey(
                        selected,
                        preferred = baseMapping(later to 's'),
                        fallback = baseMapping(earlier to 'd'),
                    ).computedPopups.main?.label shouldBe "d"
                }
            }
        }
    }

    test("a present but empty subtype popup set stops default and ALL fallback") {
        val preferred: PopupMapping = mapOf(KeyVariation.NORMAL to mapOf("a" to popups()))
        val key = resolveKey(
            KeyVariation.NORMAL,
            preferred = preferred,
            fallback = baseMapping(KeyVariation.NORMAL to 'd', KeyVariation.ALL to 'a'),
        )

        key.computedPopups.main shouldBe null
        key.computedPopups.relevant shouldBe emptyList()
    }

    test("group-specific popup merges the key's ALL mapping before the selected group mapping") {
        val groups = mapOf(
            KeyData.GROUP_ENTER to "~enter",
            KeyData.GROUP_LEFT to "~left",
            KeyData.GROUP_RIGHT to "~right",
            KeyData.GROUP_KANA to "~kana",
        )
        groups.forEach { (groupId, groupLabel) ->
            val mapping: PopupMapping = mapOf(
                KeyVariation.ALL to mapOf("a" to popups('k', 'v')),
                KeyVariation.NORMAL to mapOf(groupLabel to popups('g', 'r')),
            )
            val key = resolveKey(
                KeyVariation.NORMAL,
                preferred = mapping,
                data = TextKeyData(code = 'A'.code, label = "A", groupId = groupId),
            )
            withClue("group=$groupLabel") {
                key.computedPopups.main?.label shouldBe "k"
                key.computedPopups.relevant.map { it.label } shouldBe listOf("v", "r", "g")
            }
        }
    }

    test("symbol hint popups precede number hint popups after base and variation popups") {
        val mapping: PopupMapping = mapOf(
            KeyVariation.ALL to mapOf(
                "a" to popups('d', 'e'),
                "S" to popups('h', 'i'),
                "N" to popups('l', 'm'),
            ),
        )
        val key = resolveKey(
            KeyVariation.ALL,
            preferred = mapping,
            data = TextKeyData(code = 'a'.code, label = "a", popup = popups('b', 'c')),
            symbolHint = TextKeyData(code = 's'.code, label = "S", popup = popups('f', 'g')),
            numberHint = TextKeyData(code = 'n'.code, label = "N", popup = popups('j', 'k')),
        )
        val configuration = KeyHintConfiguration(KeyHintMode.HINT_PRIORITY, KeyHintMode.HINT_PRIORITY, true)
        val resolved = key.computedPopups.getPopupKeys(configuration)

        key.computedPopups.symbolHint?.label shouldBe "S"
        key.computedPopups.numberHint?.label shouldBe "N"
        resolved.priorityLabels() shouldBe "SNb"
        resolved.otherLabels() shouldBe "cedgfihkjml"
    }
})
