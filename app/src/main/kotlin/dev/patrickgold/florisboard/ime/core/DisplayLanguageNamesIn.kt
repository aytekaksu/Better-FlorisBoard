/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.core

/**
 * DisplayLocalesIn indicates how language names should be visually presented to the user.
 */
enum class DisplayLanguageNamesIn {
    /** Language names are displayed in the locale which is set for the whole device. */
    SYSTEM_LOCALE,
    /** Language names are displayed in the locale referred by itself. */
    NATIVE_LOCALE;
}
