/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.map

fun Arb.Companion.floatMaybeConstant(
    min: Float = -Float.MAX_VALUE,
    max: Float = Float.MAX_VALUE,
    includeNaNs: Boolean = false,
) = if (min == max) Arb.constant(min) else Arb.float(min, max, includeNaNs)

fun Arb.Companion.dp(
    min: Dp = -Float.MAX_VALUE.dp,
    max: Dp = Float.MAX_VALUE.dp,
    includeNaNs: Boolean = false,
): Arb<Dp> = float(min.value..max.value, includeNaNs).map { it.dp }

