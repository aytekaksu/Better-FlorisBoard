/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
class CurrencySet(val id: String, val label: String, private val slots: List<TextKeyData>) {
    /** Read-only view used to validate extension manifests before this set is published. */
    internal val slotsForValidation: List<TextKeyData>
        get() = slots

    companion object {
        val Fallback = CurrencySet(
            id = "fallback",
            label = "Fallback",
            slots = listOf(
                TextKeyData(code = 36, label = "$"),
                TextKeyData(code = 162, label = "¢"),
                TextKeyData(code = 8364, label = "€"),
                TextKeyData(code = 163, label = "£"),
                TextKeyData(code = 165, label = "¥"),
                TextKeyData(code = 8369, label = "₱"),
            ),
        )

        fun isCurrencySlot(keyCode: Int): Boolean = when (keyCode) {
            KeyCode.CURRENCY_SLOT_1,
            KeyCode.CURRENCY_SLOT_2,
            KeyCode.CURRENCY_SLOT_3,
            KeyCode.CURRENCY_SLOT_4,
            KeyCode.CURRENCY_SLOT_5,
            KeyCode.CURRENCY_SLOT_6,
            -> true

            else -> false
        }
    }

    fun getSlot(keyCode: Int): TextKeyData? {
        val slot = abs(keyCode) - abs(KeyCode.CURRENCY_SLOT_1)
        return slots.getOrNull(slot)
    }

    override fun toString(): String = "CurrencySet { slotCount=${slots.size} }"
}
