/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment

fun EnterTransition.Companion.verticalTween(
    duration: Int,
    expandFrom: Alignment.Vertical = Alignment.Bottom,
): EnterTransition {
    return fadeIn(tween(duration)) + expandVertically(tween(duration), expandFrom)
}

fun ExitTransition.Companion.verticalTween(
    duration: Int,
    shrinkTowards: Alignment.Vertical = Alignment.Bottom,
): ExitTransition {
    return fadeOut(tween(duration)) + shrinkVertically(tween(duration), shrinkTowards)
}

fun EnterTransition.Companion.horizontalTween(
    duration: Int,
    expandFrom: Alignment.Horizontal = Alignment.End,
): EnterTransition {
    return fadeIn(tween(duration)) + expandHorizontally(tween(duration), expandFrom)
}

fun ExitTransition.Companion.horizontalTween(
    duration: Int,
    shrinkTowards: Alignment.Horizontal = Alignment.End,
): ExitTransition {
    return fadeOut(tween(duration)) + shrinkHorizontally(tween(duration), shrinkTowards)
}
