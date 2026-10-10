/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.value

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.florisboard.lib.snygg.value.SnyggFontValue.Companion.FontNameRegex

sealed interface SnyggFontValue : SnyggValue {
    companion object {
        internal val FontNameRegex = """`(?<fontName>[a-zA-Z0-9\s_-]+)`""".toRegex()
    }
}

data class SnyggGenericFontFamilyValue(val fontFamily: FontFamily) : SnyggFontValue {
    companion object : SnyggEnumLikeValueEncoder<FontFamily>(
        serializationId = "genericFontFamily",
        serializationMapping = mapOf(
            "system" to FontFamily.Default,
            "sans-serif" to FontFamily.SansSerif,
            "serif" to FontFamily.Serif,
            "monospace" to FontFamily.Monospace,
            "cursive" to FontFamily.Cursive,
        ),
        default = FontFamily.Default,
        construct = { SnyggGenericFontFamilyValue(it) },
        destruct = { (it as SnyggGenericFontFamilyValue).fontFamily },
    )
    override fun encoder() = Companion
}

data class SnyggCustomFontFamilyValue(val fontName: String) : SnyggFontValue {
    companion object : SnyggValueEncoder {
        private const val EnclosedFontNameId = "enclosedFontName"

        override val spec = SnyggValueSpec {
            string(id = EnclosedFontNameId, regex = FontNameRegex)
        }

        override fun defaultValue() = SnyggCustomFontFamilyValue("")

        override fun serialize(v: SnyggValue) = encodeValue<SnyggCustomFontFamilyValue>(v) {
            snyggIdToValueMapOf(EnclosedFontNameId to "`${fontName}`")
        }

        override fun deserialize(v: String) = decodeValue(v) {
            val enclosedFontName = getString(EnclosedFontNameId)
            val fontName = enclosedFontName.substring(1, enclosedFontName.length - 1)
            SnyggCustomFontFamilyValue(fontName)
        }
    }

    override fun encoder() = Companion
}

data class SnyggFontStyleValue(val fontStyle: FontStyle) : SnyggFontValue {
    companion object : SnyggEnumLikeValueEncoder<FontStyle>(
        serializationId = "textAlign",
        serializationMapping = mapOf(
            "normal" to FontStyle.Normal,
            "italic" to FontStyle.Italic,
        ),
        default = FontStyle.Normal,
        construct = { SnyggFontStyleValue(it) },
        destruct = { (it as SnyggFontStyleValue).fontStyle },
    )
    override fun encoder() = Companion
}

data class SnyggFontWeightValue(val fontWeight: FontWeight) : SnyggFontValue {
    companion object : SnyggEnumLikeValueEncoder<FontWeight>(
        serializationId = "textAlign",
        serializationMapping = mapOf(
            "thin" to FontWeight(100),
            "extra-light" to FontWeight(200),
            "light" to FontWeight(300),
            "normal" to FontWeight(400),
            "medium" to FontWeight(500),
            "semi-bold" to FontWeight(600),
            "bold" to FontWeight(700),
            "extra-bold" to FontWeight(800),
            "black" to FontWeight(900),
            "100" to FontWeight(100),
            "200" to FontWeight(200),
            "300" to FontWeight(300),
            "400" to FontWeight(400),
            "500" to FontWeight(500),
            "600" to FontWeight(600),
            "700" to FontWeight(700),
            "800" to FontWeight(800),
            "900" to FontWeight(900),
        ),
        default = FontWeight.Normal,
        construct = { SnyggFontWeightValue(it) },
        destruct = { (it as SnyggFontWeightValue).fontWeight },
    )
    override fun encoder() = Companion
}
