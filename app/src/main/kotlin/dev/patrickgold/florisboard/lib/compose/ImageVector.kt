/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.compose

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

fun Context.vectorResource(@DrawableRes id: Int): ImageVector? {
    val theme = this.theme
    return try {
        ImageVector.vectorResource(theme = theme, resId = id, res = this.resources)
    } catch (_: Exception) {
        null
    }
}
