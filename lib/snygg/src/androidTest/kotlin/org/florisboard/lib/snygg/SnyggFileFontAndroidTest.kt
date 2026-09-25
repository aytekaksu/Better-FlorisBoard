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

package org.florisboard.lib.snygg

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.snygg.value.SnyggAssetResolver
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SnyggFileFontAndroidTest {
    @Test
    fun validFileLoadsAndDamagedFileFallsBack() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "snygg-font-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        try {
            val valid = File(directory, "valid.ttf")
            instrumentation.context.assets.open("Roboto-Light.ttf").use { input ->
                valid.outputStream().use { output -> input.copyTo(output) }
            }
            val damaged = File(directory, "damaged.ttf").apply { writeText("not a font") }
            val resolver = createFontFamilyResolver(instrumentation.targetContext)

            val theme = compile(valid, damaged).preloadFonts(resolver)
            assertTrue(theme.fontFamilies["Valid font"] != null)
            assertTrue(theme.fontFamilies["Valid font"] != FontFamily.Default)
            val fallback = theme.fontFamilies["Damaged font"]
            assertTrue(fallback == null || fallback == FontFamily.Default)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun compile(valid: File, damaged: File): SnyggTheme {
        val stylesheet = SnyggStylesheet.v2 {
            font("Valid font") {
                add {
                    src = uri("flex:/valid.ttf")
                }
            }
            font("Damaged font") {
                add {
                    src = uri("flex:/damaged.ttf")
                }
            }
        }
        val assets = object : SnyggAssetResolver {
            override fun resolveAbsolutePath(uri: String) = when (uri) {
                "flex:/valid.ttf" -> Result.success(valid.absolutePath)
                "flex:/damaged.ttf" -> Result.success(damaged.absolutePath)
                else -> Result.failure(IllegalArgumentException("Unknown font"))
            }
        }
        return SnyggTheme.compileFrom(stylesheet, assets)
    }
}
