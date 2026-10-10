/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import androidx.annotation.FloatRange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope.Companion.DefaultBlendMode
import androidx.compose.ui.graphics.drawscope.Stroke

fun DrawScope.drawBorder(
    color: Color,
    stroke: Stroke,
    topLeft: Offset = Offset.Zero,
    size: Size = this.size.offsetSize(topLeft),
    @FloatRange(from = 0.0, to = 1.0) alpha: Float = 1.0f,
    colorFilter: ColorFilter? = null,
    blendMode: BlendMode = DefaultBlendMode,
) {
    drawRect(
        color = color,
        topLeft = topLeft + Offset(stroke.width / 2, stroke.width / 2),
        size = size.copy(size.width - stroke.width, size.height - stroke.width),
        alpha = alpha,
        style = stroke,
        colorFilter = colorFilter,
        blendMode = blendMode,
    )
}

private fun Size.offsetSize(offset: Offset): Size =
    Size(this.width - offset.x, this.height - offset.y)
