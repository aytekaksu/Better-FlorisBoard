/*
 * Copyright (C) 2020-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

enum class KeyboardMode(val value: Int) {
    UNSPECIFIED(-1),
    CHARACTERS(0),
    SYMBOLS(2),
    SYMBOLS2(3),
    NUMERIC(4),
    NUMERIC_ADVANCED(5),
    PHONE(6),
    PHONE2(7),
    SMARTBAR_QUICK_ACTIONS(10);

    companion object {
        fun fromInt(int: Int) = entries.firstOrNull { it.value == int } ?: CHARACTERS
    }

    override fun toString() = name.lowercase()

    fun toInt() = value
}
