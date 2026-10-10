/*
 * Copyright (C) 2020-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.theme

/**
 * Enum class which specifies all theme modes available. Used in the Settings
 * to properly manage different use cases when the day or night theme should
 * be active.
 */
enum class ThemeMode {
    ALWAYS_DAY,
    ALWAYS_NIGHT,
    FOLLOW_SYSTEM,
    FOLLOW_TIME;
}
