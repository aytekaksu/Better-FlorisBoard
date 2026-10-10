/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.android

import android.content.res.Configuration

fun Configuration.isOrientationPortrait(): Boolean {
    return this.orientation == Configuration.ORIENTATION_PORTRAIT
}

fun Configuration.isOrientationLandscape(): Boolean {
    return this.orientation == Configuration.ORIENTATION_LANDSCAPE
}
