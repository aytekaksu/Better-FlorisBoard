/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.util

import android.content.res.Resources

/**
 * Pixel-to-dp conversion used by gesture handling.
 */
object ViewUtils {
    fun px2dp(px: Float): Float = px / Resources.getSystem().displayMetrics.density
}
