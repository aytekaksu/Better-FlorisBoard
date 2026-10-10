/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.text.key

/**
 * Helper class for summarizing all hint preferences in one single object.
 */
data class KeyHintConfiguration(
    val symbolHintMode: KeyHintMode,
    val numberHintMode: KeyHintMode,
    val mergeHintPopups: Boolean
) {
    companion object {
        val HINTS_DISABLED = KeyHintConfiguration(KeyHintMode.DISABLED, KeyHintMode.DISABLED, false)
    }
}
