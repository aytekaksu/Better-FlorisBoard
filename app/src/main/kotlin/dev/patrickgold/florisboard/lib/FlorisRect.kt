/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.lib

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import kotlin.math.absoluteValue
import kotlin.math.min

class FlorisRect private constructor(
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
) {
    companion object {
        fun empty() = FlorisRect(0.0f, 0.0f, 0.0f, 0.0f)

        fun new(
            left: Float = 0.0f,
            top: Float = 0.0f,
            right: Float = 0.0f,
            bottom: Float = 0.0f,
        ) = FlorisRect(left, top, right, bottom)
    }

    fun applyFrom(other: FlorisRect): FlorisRect {
        left = other.left
        top = other.top
        right = other.right
        bottom = other.bottom
        return this
    }

    fun isEmpty(): Boolean {
        return left >= right || top >= bottom
    }

    fun contains(offsetX: Float, offsetY: Float): Boolean {
        return offsetX >= left && offsetX < right && offsetY >= top && offsetY < bottom
    }

    var width: Float
        get() = right - left
        set(v) { right = left + v }

    var height: Float
        get() = bottom - top
        set(v) { bottom = top + v }

    val size: Size
        get() = Size(width, height)

    val minDimension: Float
        get() = min(width.absoluteValue, height.absoluteValue)

    fun deflateBy(deltaX: Float, deltaY: Float) {
        left += deltaX
        top += deltaY
        right -= deltaX
        bottom -= deltaY
    }

    val topLeft: Offset
        get() = Offset(left, top)

    val center: Offset
        get() = Offset(left + width / 2.0f, top + height / 2.0f)

    override fun toString(): String {
        return "FlorisRect(left = $left, top = $top, right = $right, bottom = $bottom)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FlorisRect

        if (left != other.left) return false
        if (top != other.top) return false
        if (right != other.right) return false
        if (bottom != other.bottom) return false

        return true
    }

    override fun hashCode(): Int {
        var result = left.hashCode()
        result = 31 * result + top.hashCode()
        result = 31 * result + right.hashCode()
        result = 31 * result + bottom.hashCode()
        return result
    }
}

@Suppress("NOTHING_TO_INLINE")
@Stable
inline fun Offset.toIntOffset() = IntOffset(x.toInt(), y.toInt())
