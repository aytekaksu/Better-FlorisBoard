/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

private const val MAX_ICON_SIDE = 512

@Composable
fun FlorisCanvasIcon(
    @DrawableRes iconId: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val drawable = remember(iconId, context, resources, configuration) {
        requireNotNull(ResourcesCompat.getDrawable(resources, iconId, context.theme))
    }
    FlorisCanvasIcon(
        drawable = drawable,
        modifier = modifier,
        contentDescription = contentDescription,
    )
}

@Composable
fun FlorisCanvasIcon(
    drawable: Drawable,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val bitmap = remember(drawable, configuration, density) {
        rasterizeDrawable(drawable, with(density) { 48.dp.roundToPx() })
    }
    Image(
        modifier = modifier,
        bitmap = bitmap,
        contentDescription = contentDescription,
    )
}

/** Bounded bitmap conversion for UI icons, including drawables supplied by other apps. */
fun rasterizeDrawable(drawable: Drawable, fallbackSizePx: Int): ImageBitmap {
    val fallbackSize = fallbackSizePx.coerceIn(1, MAX_ICON_SIDE)
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: fallbackSize
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: fallbackSize
    val scale = minOf(1f, MAX_ICON_SIDE.toFloat() / maxOf(width, height))
    val bitmap = createBitmap(
        width = (width * scale).roundToInt().coerceIn(1, MAX_ICON_SIDE),
        height = (height * scale).roundToInt().coerceIn(1, MAX_ICON_SIDE),
    )
    val previousBounds = Rect(drawable.bounds)
    try {
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
    } finally {
        drawable.setBounds(previousBounds)
    }
    return bitmap.asImageBitmap()
}
