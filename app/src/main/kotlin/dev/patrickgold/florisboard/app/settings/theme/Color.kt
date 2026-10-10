/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.theme

import androidx.compose.ui.graphics.Color
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer

object ColorPreferenceSerializer : PreferenceSerializer<Color> {
    override fun deserialize(value: String): Color {
        return Color(value.hexToULong())
    }

    override fun serialize(value: Color): String = value.value.toHexString()
}
