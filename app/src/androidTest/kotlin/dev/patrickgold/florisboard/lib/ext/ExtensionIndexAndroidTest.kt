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

package dev.patrickgold.florisboard.lib.ext

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.lib.io.FlorisRef
import dev.patrickgold.florisboard.lib.io.ZipUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExtensionIndexAndroidTest {
    @Test
    fun corruptArchiveDoesNotHideHealthySiblingAndCanRecover(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.extensionManager().value
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val healthyId = "local.themes.healthy_$suffix"
        val repairedId = "local.themes.repaired_$suffix"
        val themeDir = File(context.filesDir, ExtensionManager.IME_THEME_PATH)
        val healthyFile = File(themeDir, ExtensionDefaults.createFlexName(healthyId))
        val repairedFile = File(themeDir, ExtensionDefaults.createFlexName(repairedId))
        val staging = File(context.cacheDir, "extension-index-$suffix")

        fun install(id: String) {
            File(staging, ExtensionDefaults.MANIFEST_FILE_NAME).writeText(
                ExtensionJsonConfig.encodeToString(
                    ThemeExtension.serializer(),
                    ThemeExtension(
                        meta = ExtensionMeta(
                            id = id,
                            version = "1.0.0",
                            title = "Index fixture",
                            maintainers = listOf(ExtensionMaintainer("Test")),
                            license = "apache-2.0",
                        ),
                        themes = emptyList(),
                    ),
                ),
            )
            ZipUtils.zip(
                context,
                staging,
                FlorisRef.internal(ExtensionManager.IME_THEME_PATH)
                    .subRef(ExtensionDefaults.createFlexName(id)),
            ).getOrThrow()
        }

        try {
            assertTrue(themeDir.mkdirs() || themeDir.isDirectory)
            assertTrue(staging.mkdirs())
            install(healthyId)
            repairedFile.writeText("invalid archive")

            manager.themes.init()
            assertTrue(manager.themes.value.any { it.meta.id == healthyId })
            assertFalse(manager.themes.value.any { it.meta.id == repairedId })

            install(repairedId)
            manager.themes.init()
            assertTrue(manager.themes.value.any { it.meta.id == healthyId })
            assertTrue(manager.themes.value.any { it.meta.id == repairedId })
        } finally {
            healthyFile.delete()
            repairedFile.delete()
            staging.deleteRecursively()
            manager.themes.init()
        }
    }
}
