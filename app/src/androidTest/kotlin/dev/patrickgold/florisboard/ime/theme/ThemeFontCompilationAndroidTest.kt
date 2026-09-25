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

package dev.patrickgold.florisboard.ime.theme

import android.os.Looper
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.florisboard.lib.snygg.SnyggStylesheet
import org.florisboard.lib.snygg.value.SnyggAssetResolver
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ThemeFontCompilationAndroidTest {
    @Test
    fun fileFontCompilesOffMainThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "theme-font-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        try {
            val fontFile = File(directory, "font.ttf")
            instrumentation.context.assets.open("Roboto-Light.ttf").use { input ->
                fontFile.outputStream().use { output -> input.copyTo(output) }
            }
            val resolved = AtomicBoolean(false)
            val resolver = object : SnyggAssetResolver {
                override fun resolveAbsolutePath(uri: String): Result<String> {
                    assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                    resolved.set(true)
                    return Result.success(fontFile.absolutePath)
                }
            }
            val fontResolver = createFontFamilyResolver(instrumentation.targetContext)

            runBlocking {
                withContext(Dispatchers.Main) {
                    compileThemeOffMain(fontStylesheet(), resolver, fontResolver)
                }
            }

            assertTrue(resolved.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun fontStylesheet() = SnyggStylesheet.v2 {
        font("Test font") {
            add {
                src = uri("flex:/font.ttf")
            }
        }
    }
}
