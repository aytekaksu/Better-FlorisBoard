/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar

enum class SmartbarMotionMode {
    STANDARD,
    REDUCED,
    OFF;

    fun durationMillis(standardDurationMillis: Int): Int {
        return when (this) {
            STANDARD -> standardDurationMillis
            REDUCED -> standardDurationMillis / 2
            OFF -> 0
        }
    }
}
