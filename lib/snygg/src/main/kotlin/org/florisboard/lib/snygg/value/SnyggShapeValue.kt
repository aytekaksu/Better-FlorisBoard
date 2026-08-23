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

package org.florisboard.lib.snygg.value

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val CORNER_SIZE_TOP_START = "cornerSizeTopStart"
private const val CORNER_SIZE_TOP_END = "cornerSizeTopEnd"
private const val CORNER_SIZE_BOTTOM_END = "cornerSizeBottomEnd"
private const val CORNER_SIZE_BOTTOM_START = "cornerSizeBottomStart"

private const val RECTANGLE = "rectangle"
private const val CIRCLE = "circle"
private const val CUT_CORNER = "cut-corner"
private const val ROUNDED_CORNER = "rounded-corner"

private const val DP_UNIT = "dp"
private val CORNER_SIZE_IDS = listOf(
    CORNER_SIZE_TOP_START,
    CORNER_SIZE_TOP_END,
    CORNER_SIZE_BOTTOM_END,
    CORNER_SIZE_BOTTOM_START,
)

private fun emptyShapeSpec(name: String) = SnyggValueSpec {
    function(name) { nothing() }
}

private fun dpCornerShapeSpec(name: String) = SnyggValueSpec {
    function(name) {
        commaList {
            CORNER_SIZE_IDS.forEach { +float(id = it, unit = DP_UNIT) }
        }
    }
}

private fun percentCornerShapeSpec(name: String) = SnyggValueSpec {
    function(name) {
        commaList {
            CORNER_SIZE_IDS.forEach { +percentageInt(id = it) }
        }
    }
}

private inline fun <reified T : SnyggValue> SnyggValueEncoder.encodeShape(
    value: SnyggValue,
    arguments: T.() -> SnyggIdToValueMap = { snyggIdToValueMapOf() },
) = runCatching<String> {
    require(value is T)
    spec.pack(value.arguments())
}

private fun SnyggValueEncoder.decodeShape(value: String, construct: SnyggIdToValueMap.() -> SnyggValue) =
    runCatching<SnyggValue> {
        val arguments = snyggIdToValueMapOf()
        spec.parse(value, arguments)
        arguments.construct()
    }

sealed interface SnyggShapeValue : SnyggValue {
    val shape: Shape
}

sealed interface SnyggDpShapeValue : SnyggShapeValue {
    override val shape: Shape
    val topStart: Dp
    val topEnd: Dp
    val bottomEnd: Dp
    val bottomStart: Dp
}

sealed interface SnyggPercentShapeValue : SnyggShapeValue {
    override val shape: Shape
    val topStart: Int
    val topEnd: Int
    val bottomEnd: Int
    val bottomStart: Int
}

private fun SnyggDpShapeValue.encodeArguments() = snyggIdToValueMapOf(
    CORNER_SIZE_TOP_START to topStart.value,
    CORNER_SIZE_TOP_END to topEnd.value,
    CORNER_SIZE_BOTTOM_END to bottomEnd.value,
    CORNER_SIZE_BOTTOM_START to bottomStart.value,
)

private fun SnyggPercentShapeValue.encodeArguments() = snyggIdToValueMapOf(
    CORNER_SIZE_TOP_START to topStart,
    CORNER_SIZE_TOP_END to topEnd,
    CORNER_SIZE_BOTTOM_END to bottomEnd,
    CORNER_SIZE_BOTTOM_START to bottomStart,
)

private fun SnyggValueEncoder.decodeDpShape(value: String, construct: (Dp, Dp, Dp, Dp) -> SnyggValue) =
    decodeShape(value) {
        construct(
            getFloat(CORNER_SIZE_TOP_START).dp,
            getFloat(CORNER_SIZE_TOP_END).dp,
            getFloat(CORNER_SIZE_BOTTOM_END).dp,
            getFloat(CORNER_SIZE_BOTTOM_START).dp,
        )
    }

private fun SnyggValueEncoder.decodePercentShape(value: String, construct: (Int, Int, Int, Int) -> SnyggValue) =
    decodeShape(value) {
        construct(
            getInt(CORNER_SIZE_TOP_START),
            getInt(CORNER_SIZE_TOP_END),
            getInt(CORNER_SIZE_BOTTOM_END),
            getInt(CORNER_SIZE_BOTTOM_START),
        )
    }

data class SnyggRectangleShapeValue(override val shape: Shape = RectangleShape) : SnyggShapeValue {
    companion object : SnyggValueEncoder {
        override val spec = emptyShapeSpec(RECTANGLE)

        override fun defaultValue() = SnyggRectangleShapeValue()

        override fun serialize(v: SnyggValue) = encodeShape<SnyggRectangleShapeValue>(v)

        override fun deserialize(v: String) = decodeShape(v) { SnyggRectangleShapeValue() }
    }

    override fun encoder() = Companion
}

data class SnyggCircleShapeValue(override val shape: Shape = CircleShape) : SnyggShapeValue {
    companion object : SnyggValueEncoder {
        override val spec = emptyShapeSpec(CIRCLE)

        override fun defaultValue() = SnyggCircleShapeValue()

        override fun serialize(v: SnyggValue) = encodeShape<SnyggCircleShapeValue>(v)

        override fun deserialize(v: String) = decodeShape(v) { SnyggCircleShapeValue() }
    }

    override fun encoder() = Companion
}

data class SnyggCutCornerDpShapeValue(
    override val topStart: Dp,
    override val topEnd: Dp,
    override val bottomEnd: Dp,
    override val bottomStart: Dp,
    override val shape: CutCornerShape = CutCornerShape(topStart, topEnd, bottomEnd, bottomStart),
) : SnyggDpShapeValue {
    companion object : SnyggValueEncoder {
        override val spec = dpCornerShapeSpec(CUT_CORNER)

        override fun defaultValue() = SnyggCutCornerDpShapeValue(0.dp, 0.dp, 0.dp, 0.dp)

        override fun serialize(v: SnyggValue) = encodeShape<SnyggCutCornerDpShapeValue>(v) { encodeArguments() }

        override fun deserialize(v: String) = decodeDpShape(v) { topStart, topEnd, bottomEnd, bottomStart ->
            SnyggCutCornerDpShapeValue(topStart, topEnd, bottomEnd, bottomStart)
        }
    }

    override fun encoder() = Companion
}

data class SnyggCutCornerPercentShapeValue(
    override val topStart: Int,
    override val topEnd: Int,
    override val bottomEnd: Int,
    override val bottomStart: Int,
    override val shape: CutCornerShape = CutCornerShape(topStart, topEnd, bottomEnd, bottomStart),
) : SnyggPercentShapeValue {
    companion object : SnyggValueEncoder {
        override val spec = percentCornerShapeSpec(CUT_CORNER)

        override fun defaultValue() = SnyggCutCornerPercentShapeValue(0, 0, 0, 0)

        override fun serialize(v: SnyggValue) = encodeShape<SnyggCutCornerPercentShapeValue>(v) { encodeArguments() }

        override fun deserialize(v: String) = decodePercentShape(v) { topStart, topEnd, bottomEnd, bottomStart ->
            SnyggCutCornerPercentShapeValue(topStart, topEnd, bottomEnd, bottomStart)
        }
    }

    override fun encoder() = Companion
}

data class SnyggRoundedCornerDpShapeValue(
    override val topStart: Dp,
    override val topEnd: Dp,
    override val bottomEnd: Dp,
    override val bottomStart: Dp,
    override val shape: RoundedCornerShape = RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart),
) : SnyggDpShapeValue {
    companion object : SnyggValueEncoder {
        override val spec = dpCornerShapeSpec(ROUNDED_CORNER)

        override fun defaultValue() = SnyggRoundedCornerDpShapeValue(0.dp, 0.dp, 0.dp, 0.dp)

        override fun serialize(v: SnyggValue) = encodeShape<SnyggRoundedCornerDpShapeValue>(v) { encodeArguments() }

        override fun deserialize(v: String) = decodeDpShape(v) { topStart, topEnd, bottomEnd, bottomStart ->
            SnyggRoundedCornerDpShapeValue(topStart, topEnd, bottomEnd, bottomStart)
        }
    }

    override fun encoder() = Companion
}

data class SnyggRoundedCornerPercentShapeValue(
    override val topStart: Int,
    override val topEnd: Int,
    override val bottomEnd: Int,
    override val bottomStart: Int,
    override val shape: RoundedCornerShape = RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart),
) : SnyggPercentShapeValue {
    companion object : SnyggValueEncoder {
        override val spec = percentCornerShapeSpec(ROUNDED_CORNER)

        override fun defaultValue() = SnyggRoundedCornerPercentShapeValue(0, 0, 0, 0)

        override fun serialize(v: SnyggValue) = encodeShape<SnyggRoundedCornerPercentShapeValue>(v) {
            encodeArguments()
        }

        override fun deserialize(v: String) = decodePercentShape(v) { topStart, topEnd, bottomEnd, bottomStart ->
            SnyggRoundedCornerPercentShapeValue(topStart, topEnd, bottomEnd, bottomStart)
        }
    }

    override fun encoder() = Companion
}
