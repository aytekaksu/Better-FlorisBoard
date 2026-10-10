/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime

enum class ImeUiMode(val value: Int) {
    TEXT(0),
    MEDIA(1),
    CLIPBOARD(2);

    companion object {
        fun fromInt(int: Int) = entries.firstOrNull { it.value == int } ?: TEXT
    }

    fun toInt(): Int = value
}
