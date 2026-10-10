/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
