/*
 * Copyright (C) 2026 The FlorisBoard Contributors
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
