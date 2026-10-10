/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.apptheme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NeutralColorSchemeTest : FunSpec({
    test("primary overload preserves explicitly seeded Neutral schemes") {
        val seed = Color(0xFF4CAF50)
        for ((isDark, isAmoled) in listOf(false to false, false to true, true to false, true to true)) {
            val previous = dynamicColorScheme(
                seedColor = seed, primary = seed, isDark = isDark,
                isAmoled = isAmoled, style = PaletteStyle.Neutral,
            )
            val direct = dynamicColorScheme(
                primary = seed, isDark = isDark, isAmoled = isAmoled,
                style = PaletteStyle.Neutral,
            )
            with(direct) { listOf(primary, onPrimary, background, surface, surfaceContainer, outline) } shouldBe
                with(previous) { listOf(primary, onPrimary, background, surface, surfaceContainer, outline) }
        }
    }
})
