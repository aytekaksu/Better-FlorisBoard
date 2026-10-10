/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.color

import androidx.compose.runtime.saveable.Saver
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MaterialYouFlags(
    @EncodeDefault
    val paletteStyle: PaletteStyle = PaletteStyle.Neutral,
    @EncodeDefault
    val contrastLevel: Contrast = Contrast.Default,
    @EncodeDefault
    val specVersion: ColorSpec.SpecVersion = ColorSpec.SpecVersion.Default,
)

val MaterialYouFlagsSaver = Saver<MaterialYouFlags, String>(
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString(it) }
)
