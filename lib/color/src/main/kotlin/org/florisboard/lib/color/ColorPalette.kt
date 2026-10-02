/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package org.florisboard.lib.color

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

// A concrete Color return keeps this lookup unboxed.
private fun interface PaletteColor {
    fun read(scheme: ColorScheme): Color
}

enum class ColorPalette(val id: String, private val color: PaletteColor) {
    Primary("primary", { it.primary }),
    OnPrimary("onPrimary", { it.onPrimary }),
    PrimaryContainer("primaryContainer", { it.primaryContainer }),
    OnPrimaryContainer("onPrimaryContainer", { it.onPrimaryContainer }),
    InversePrimary("inversePrimary", { it.inversePrimary }),
    Secondary("secondary", { it.secondary }),
    OnSecondary("onSecondary", { it.onSecondary }),
    SecondaryContainer("secondaryContainer", { it.secondaryContainer }),
    OnSecondaryContainer("onSecondaryContainer", { it.onSecondaryContainer }),
    Tertiary("tertiary", { it.tertiary }),
    OnTertiary("onTertiary", { it.onTertiary }),
    TertiaryContainer("tertiaryContainer", { it.tertiaryContainer }),
    OnTertiaryContainer("onTertiaryContainer", { it.onTertiaryContainer }),
    Background("background", { it.background }),
    OnBackground("onBackground", { it.onBackground }),
    // Surface is intentionally absent; themes use the specific Surface* roles.
    OnSurface("onSurface", { it.onSurface }),
    SurfaceVariant("surfaceVariant", { it.surfaceVariant }),
    OnSurfaceVariant("onSurfaceVariant", { it.onSurfaceVariant }),
    SurfaceTint("surfaceTint", { it.surfaceTint }),
    InverseSurface("inverseSurface", { it.inverseSurface }),
    InverseOnSurface("inverseOnSurface", { it.inverseOnSurface }),
    Error("error", { it.error }),
    OnError("onError", { it.onError }),
    ErrorContainer("errorContainer", { it.errorContainer }),
    OnErrorContainer("onErrorContainer", { it.onErrorContainer }),
    Outline("outline", { it.outline }),
    OutlineVariant("outlineVariant", { it.outlineVariant }),
    Scrim("scrim", { it.scrim }),
    SurfaceBright("surfaceBright", { it.surfaceBright }),
    SurfaceDim("surfaceDim", { it.surfaceDim }),
    SurfaceContainer("surfaceContainer", { it.surfaceContainer }),
    SurfaceContainerHigh("surfaceContainerHigh", { it.surfaceContainerHigh }),
    SurfaceContainerHighest("surfaceContainerHighest", { it.surfaceContainerHighest }),
    SurfaceContainerLow("surfaceContainerLow", { it.surfaceContainerLow }),
    SurfaceContainerLowest("surfaceContainerLowest", { it.surfaceContainerLowest });

    companion object {
        val colorNames = entries.map { it.id }
        private val byId = entries.associateBy { it.id }

        internal fun resolve(scheme: ColorScheme, id: String): Color {
            val palette = byId[id] ?: return scheme.primary
            return palette.color.read(scheme)
        }
    }
}

fun ColorScheme.getColor(id: String): Color = ColorPalette.resolve(this, id)
