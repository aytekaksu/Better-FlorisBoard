/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.value

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

sealed interface SnyggTextValue : SnyggValue

data class SnyggTextAlignValue(val textAlign: TextAlign) : SnyggTextValue {
    companion object : SnyggEnumLikeValueEncoder<TextAlign>(
        serializationId = "textAlign",
        serializationMapping = mapOf(
            "left" to TextAlign.Left,
            "right" to TextAlign.Right,
            "center" to TextAlign.Center,
            "justify" to TextAlign.Justify,
            "start" to TextAlign.Start,
            "end" to TextAlign.End,
        ),
        default = TextAlign.Left,
        construct = { SnyggTextAlignValue(it) },
        destruct = { (it as SnyggTextAlignValue).textAlign },
    )
    override fun encoder() = Companion
}

data class SnyggTextDecorationLineValue(val textDecoration: TextDecoration) : SnyggTextValue {
    companion object : SnyggEnumLikeValueEncoder<TextDecoration>(
        serializationId = "textAlign",
        serializationMapping = mapOf(
            "none" to TextDecoration.None,
            "underline" to TextDecoration.Underline,
            "line-through" to TextDecoration.LineThrough,
        ),
        default = TextDecoration.None,
        construct = { SnyggTextDecorationLineValue(it) },
        destruct = { (it as SnyggTextDecorationLineValue).textDecoration },
    )
    override fun encoder() = Companion
}

data class SnyggTextMaxLinesValue(val maxLines: Int) : SnyggTextValue {
    companion object : SnyggValueEncoder {
        private const val TextMaxLinesId = "textMaxLines"
        private const val NoneKey = "none"
        private const val NoneValue = Int.MAX_VALUE

        override val spec = SnyggValueSpec {
            string(id = TextMaxLinesId, regex = """$NoneKey|[1-9][0-9]*""".toRegex())
        }

        override fun defaultValue() = SnyggTextMaxLinesValue(NoneValue)

        override fun serialize(v: SnyggValue) = encodeValue<SnyggTextMaxLinesValue>(v) {
            require(maxLines >= 1)
            val encoded = if (maxLines == NoneValue) NoneKey else maxLines.toString()
            snyggIdToValueMapOf(TextMaxLinesId to encoded)
        }

        override fun deserialize(v: String) = decodeValue(v) {
            val clampValue = getString(TextMaxLinesId)
            val maxLines = if (clampValue == NoneKey) NoneValue else clampValue.toInt()
            SnyggTextMaxLinesValue(maxLines)
        }
    }

    override fun encoder() = Companion
}

data class SnyggTextOverflowValue(val textOverflow: TextOverflow) : SnyggTextValue {
    companion object : SnyggEnumLikeValueEncoder<TextOverflow>(
        serializationId = "textAlign",
        serializationMapping = mapOf(
            "clip" to TextOverflow.Clip,
            "ellipsis" to TextOverflow.Ellipsis,
            "visible" to TextOverflow.Visible,
        ),
        default = TextOverflow.Clip,
        construct = { SnyggTextOverflowValue(it) },
        destruct = { (it as SnyggTextOverflowValue).textOverflow },
    )
    override fun encoder() = Companion
}
