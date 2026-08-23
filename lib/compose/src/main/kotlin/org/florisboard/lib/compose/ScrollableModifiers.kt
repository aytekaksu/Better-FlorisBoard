/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.florisboard.lib.compose

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val DefaultScrollbarSize = 4.dp
private const val SCROLLBAR_FADE_DURATION_MILLIS = 950
private const val SCROLLBAR_INITIAL_DISPLAY_MILLIS = 1_850L

// IgnoreInVeryFastOut (basically)
private val ScrollbarAnimationEasing = CubicBezierEasing(1f, 0f, 0.82f, -0.13f)

internal data class ScrollbarGeometry(val offset: Float, val length: Float)

internal fun calculateScrollbarGeometry(
    trackLength: Float,
    contentSize: Int,
    viewportSize: Int,
    scrollOffset: Int,
): ScrollbarGeometry? {
    if (!trackLength.isFinite() || trackLength <= 0f) return null
    if (contentSize == Int.MAX_VALUE || viewportSize == Int.MAX_VALUE || scrollOffset == Int.MAX_VALUE) return null
    if (viewportSize <= 0 || contentSize <= viewportSize) return null
    val scrollRange = contentSize - viewportSize
    val clampedOffset = scrollOffset.coerceIn(0, scrollRange)
    val length = trackLength * (viewportSize.toFloat() / contentSize.toFloat())
    val offset = (trackLength - length) * (clampedOffset.toFloat() / scrollRange.toFloat())
    return ScrollbarGeometry(offset, length).takeIf {
        offset.isFinite() && length.isFinite() && length > 0f
    }
}

internal fun ScrollbarGeometry.physicalOffset(
    trackLength: Float,
    orientation: Orientation,
    layoutDirection: LayoutDirection,
): Float = if (orientation == Orientation.Horizontal && layoutDirection == LayoutDirection.Rtl) {
    trackLength - offset - length
} else {
    offset
}

fun Modifier.florisVerticalScroll(
    state: ScrollState? = null,
    showScrollbar: Boolean = true,
    scrollbarWidth: Dp = DefaultScrollbarSize,
) = composed {
    val scrollState = state ?: rememberScrollState()
    if (showScrollbar) {
        florisScrollbar(scrollState, scrollbarWidth).verticalScroll(scrollState)
    } else {
        verticalScroll(scrollState)
    }
}

fun Modifier.florisHorizontalScroll(
    state: ScrollState? = null,
    showScrollbar: Boolean = true,
    scrollbarHeight: Dp = DefaultScrollbarSize,
) = composed {
    val scrollState = state ?: rememberScrollState()
    if (showScrollbar) {
        florisScrollbar(
            state = scrollState,
            scrollbarSize = scrollbarHeight,
            orientation = Orientation.Horizontal,
        ).horizontalScroll(scrollState)
    } else {
        horizontalScroll(scrollState)
    }
}

fun Modifier.florisScrollbar(
    state: ScrollableState,
    scrollbarSize: Dp = DefaultScrollbarSize,
    color: Color = Color.Unspecified,
    orientation: Orientation = Orientation.Vertical,
): Modifier = composed {
    var isInitial by remember(state) { mutableStateOf(true) }
    val targetAlpha = if (state.isScrollInProgress || isInitial) 1f else 0f
    val duration = if (state.isScrollInProgress || isInitial) 0 else SCROLLBAR_FADE_DURATION_MILLIS
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration, easing = ScrollbarAnimationEasing),
    )
    val scrollbarColor = color.takeOrElse { MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f) }

    LaunchedEffect(state) {
        delay(SCROLLBAR_INITIAL_DISPLAY_MILLIS)
        isInitial = false
    }

    drawWithContent {
        drawContent()
        if (alpha <= 0f) return@drawWithContent
        val indicator = state.scrollIndicatorState ?: return@drawWithContent
        val contentSize = indicator.contentSize
        val viewportSize = indicator.viewportSize
        val scrollOffset = indicator.scrollOffset
        val trackLength = if (orientation == Orientation.Vertical) size.height else size.width
        val geometry = calculateScrollbarGeometry(
            trackLength = trackLength,
            contentSize = contentSize,
            viewportSize = viewportSize,
            scrollOffset = scrollOffset,
        ) ?: return@drawWithContent
        val offset = geometry.physicalOffset(trackLength, orientation, layoutDirection)
        val thickness = scrollbarSize.toPx()
        val topLeft = if (orientation == Orientation.Vertical) {
            Offset(size.width - thickness, offset)
        } else {
            Offset(offset, size.height - thickness)
        }
        val dimensions = if (orientation == Orientation.Vertical) {
            Size(thickness, geometry.length)
        } else {
            Size(geometry.length, thickness)
        }
        drawRect(
            color = scrollbarColor,
            topLeft = topLeft,
            size = dimensions,
            alpha = alpha,
        )
    }
}
