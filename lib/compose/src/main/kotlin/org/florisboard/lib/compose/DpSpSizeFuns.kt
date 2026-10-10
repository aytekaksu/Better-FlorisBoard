/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

context(density: Density)
fun IntRect.toDpRect(): DpRect {
    return with(density) {
        DpRect(left.toDp(), top.toDp(), right.toDp(), bottom.toDp())
    }
}

context(density: Density)
fun Offset.toDp(): DpOffset {
    return with(density) {
        DpOffset(x.toDp(), y.toDp())
    }
}

object DpSizeSerializer : KSerializer<Dp> {
    override val descriptor = PrimitiveSerialDescriptor("DpSize", PrimitiveKind.FLOAT)

    override fun serialize(encoder: Encoder, value: Dp) {
        encoder.encodeFloat(value.value)
    }

    override fun deserialize(decoder: Decoder): Dp {
        return decoder.decodeFloat().dp
    }
}
