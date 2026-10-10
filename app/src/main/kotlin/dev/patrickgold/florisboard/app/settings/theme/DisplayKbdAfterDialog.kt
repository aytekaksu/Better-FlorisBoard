/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.theme

/**
 * DisplayPreviewAfterDialogs indicates if the keyboard should auto-open after closing
 * any dialog. This is useful because the dialog always hides the keyboard and one may
 * not want to always press the preview field again.
 */
enum class DisplayKbdAfterDialogs {
    ALWAYS,
    NEVER,
    REMEMBER;
}
