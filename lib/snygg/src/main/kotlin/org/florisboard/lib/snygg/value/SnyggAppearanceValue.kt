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

import androidx.compose.ui.graphics.Color
import org.florisboard.lib.color.ColorPalette
import kotlin.math.roundToInt

sealed interface SnyggAppearanceValue : SnyggValue

object RgbaColor {
    const val HexId = "hex"
    val Hex6Matcher = """#[a-fA-F0-9]{6}""".toRegex()
    val Hex8Matcher = """#[a-fA-F0-9]{8}""".toRegex()

    const val TransparentId = "transparent"
    val TransparentMatcher = """transparent""".toRegex()

    const val RedId = "r"
    const val GreenId = "g"
    const val BlueId = "b"
    const val AlphaId = "a"

    const val ColorRangeMin = 0
    const val ColorRangeMax = 255
    val ColorRange = ColorRangeMin..ColorRangeMax
    val ColorRangePattern = """25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9]""".toRegex()

    const val AlphaRangeMin = 0f
    const val AlphaRangeMax = 1f
    val AlphaRange = AlphaRangeMin..AlphaRangeMax
    val AlphaRangePattern = """1(?:[.]0)?|0(?:[.][0-9]*)?|[.][0-9]+""".toRegex()
}

private fun rgbColorSpec(withAlpha: Boolean) = SnyggValueSpec {
    function(name = if (withAlpha) "rgba" else "rgb") {
        commaList {
            +int(id = RgbaColor.RedId, numberPattern = RgbaColor.ColorRangePattern)
            +int(id = RgbaColor.GreenId, numberPattern = RgbaColor.ColorRangePattern)
            +int(id = RgbaColor.BlueId, numberPattern = RgbaColor.ColorRangePattern)
            if (withAlpha) {
                +float(id = RgbaColor.AlphaId, numberPattern = RgbaColor.AlphaRangePattern)
            }
        }
    }
}

data class SnyggStaticColorValue(val color: Color) : SnyggAppearanceValue {
    companion object : SnyggValueEncoder {
        override val spec = rgbColorSpec(withAlpha = true)

        override val alternativeSpecs = listOf(
            rgbColorSpec(withAlpha = false),
            SnyggValueSpec {
                string(id = RgbaColor.TransparentId, regex = RgbaColor.TransparentMatcher)
            },
            SnyggValueSpec {
                string(id = RgbaColor.HexId, regex = RgbaColor.Hex6Matcher)
            },
            SnyggValueSpec {
                string(id = RgbaColor.HexId, regex = RgbaColor.Hex8Matcher)
            },
        )

        private val acceptedSpecs = listOf(spec) + alternativeSpecs

        override fun defaultValue() = SnyggStaticColorValue(Color.Black)

        override fun serialize(v: SnyggValue) = runCatching<String> {
            require(v is SnyggStaticColorValue)
            val map = snyggIdToValueMapOf(
                RgbaColor.RedId to (v.color.red * RgbaColor.ColorRangeMax).roundToInt(),
                RgbaColor.GreenId to (v.color.green * RgbaColor.ColorRangeMax).roundToInt(),
                RgbaColor.BlueId to (v.color.blue * RgbaColor.ColorRangeMax).roundToInt(),
                RgbaColor.AlphaId to v.color.alpha,
            )
            return@runCatching spec.pack(map)
        }

        override fun deserialize(v: String) = runCatching<SnyggValue> {
            val map = snyggIdToValueMapOf()
            val matchingSpec = acceptedSpecs.firstOrNull { it.parsePattern.matches(v) }
                ?: error("No matching color spec found")
            matchingSpec.parse(v, map)
            val color = when {
                RgbaColor.TransparentId in map -> Color.Transparent
                RgbaColor.HexId in map -> {
                    val hexStr = map.getString(RgbaColor.HexId)
                    Color(
                        hexStr.substring(1..2).toInt(16),
                        hexStr.substring(3..4).toInt(16),
                        hexStr.substring(5..6).toInt(16),
                        if (hexStr.length == 9) hexStr.substring(7..8).toInt(16) else 255,
                    )
                }
                else -> Color(
                    map.getInt(RgbaColor.RedId),
                    map.getInt(RgbaColor.GreenId),
                    map.getInt(RgbaColor.BlueId),
                ).copy(alpha = map[RgbaColor.AlphaId]?.toFloat() ?: 1.0f)
            }
            SnyggStaticColorValue(color)
        }
    }

    override fun encoder() = Companion
}

/// Dynamic Color Value

private const val ColorNameId = "name"
private val ColorName = ColorPalette.colorNames.joinToString("|").toRegex()

private fun dynamicColorSpec(functionName: String) = SnyggValueSpec {
    function(name = functionName) {
        commaList {
            +string(id = ColorNameId, regex = ColorName)
        }
    }
}

sealed interface SnyggDynamicColorValue : SnyggAppearanceValue {
    val colorName: String
}

data class SnyggDynamicLightColorValue(override val colorName: String) : SnyggDynamicColorValue {
    companion object : SnyggValueEncoder {
        override val spec = dynamicColorSpec("dynamic-light-color")

        override fun defaultValue() = SnyggDynamicLightColorValue(ColorPalette.Primary.id)

        override fun serialize(v: SnyggValue) = encodeValue<SnyggDynamicLightColorValue>(v) {
            snyggIdToValueMapOf(ColorNameId to colorName)
        }

        override fun deserialize(v: String) = decodeValue(v) { SnyggDynamicLightColorValue(getString(ColorNameId)) }
    }

    override fun encoder() = Companion
}

data class SnyggDynamicDarkColorValue(override val colorName: String) : SnyggDynamicColorValue {
    companion object : SnyggValueEncoder {
        override val spec = dynamicColorSpec("dynamic-dark-color")

        override fun defaultValue() = SnyggDynamicDarkColorValue(ColorPalette.Primary.id)

        override fun serialize(v: SnyggValue) = encodeValue<SnyggDynamicDarkColorValue>(v) {
            snyggIdToValueMapOf(ColorNameId to colorName)
        }

        override fun deserialize(v: String) = decodeValue(v) { SnyggDynamicDarkColorValue(getString(ColorNameId)) }
    }

    override fun encoder() = Companion
}
