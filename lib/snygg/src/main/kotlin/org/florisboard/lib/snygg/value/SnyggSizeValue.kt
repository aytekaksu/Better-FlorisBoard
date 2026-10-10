/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.value

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val Size = "size"
private const val DpUnit = "dp"
private const val SpUnit = "sp"

sealed interface SnyggSizeValue : SnyggValue

data class SnyggDpSizeValue(val dp: Dp) : SnyggSizeValue {
    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            float(id = Size, unit = DpUnit, numberPattern = """(?:0|[1-9][0-9]*)(?:[.][0-9]*)?|[.][0-9]+""".toRegex())
        }

        override fun defaultValue() = SnyggDpSizeValue(0.dp)

        override fun serialize(v: SnyggValue) = encodeValue<SnyggDpSizeValue>(v) {
            snyggIdToValueMapOf(Size to dp.value)
        }

        override fun deserialize(v: String) = decodeValue(v) { SnyggDpSizeValue(getFloat(Size).dp) }
    }

    override fun encoder() = Companion
}

data class SnyggSpSizeValue(val sp: TextUnit) : SnyggSizeValue {
    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            float(id = Size, unit = SpUnit, numberPattern = """[1-9][0-9]*(?:[.][0-9]*)?""".toRegex())
        }

        override fun defaultValue() = SnyggSpSizeValue(24.sp)

        override fun serialize(v: SnyggValue) = encodeValue<SnyggSpSizeValue>(v) {
            snyggIdToValueMapOf(Size to sp.value)
        }

        override fun deserialize(v: String) = decodeValue(v) { SnyggSpSizeValue(getFloat(Size).sp) }
    }

    override fun encoder() = Companion
}
