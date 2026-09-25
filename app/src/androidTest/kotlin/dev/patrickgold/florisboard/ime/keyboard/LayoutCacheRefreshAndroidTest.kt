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

package dev.patrickgold.florisboard.ime.keyboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.keyboardExtensionRepository
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.popup.PopupMappingComponent
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.ext.ExtensionDefaults
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.lib.ext.ExtensionMaintainer
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LayoutCacheRefreshAndroidTest {
    @Test
    fun unchangedManifestReplacementReloadsLayoutAndPopupMapping(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.extensionManager().value
        manager.keyboardExtensions.init() // Wait until the archive observer is installed.
        val repository = context.keyboardExtensionRepository().value
        val layoutManager = LayoutManager(context)
        val id = "local.keyboard.cache_${UUID.randomUUID().toString().replace("-", "")}"
        val name = ExtensionComponentName(id, "sample")
        val extension = KeyboardExtension(
            meta = ExtensionMeta(
                id = id,
                version = "1.0.0",
                title = "Cache refresh test",
                maintainers = listOf(ExtensionMaintainer("Test")),
                license = "apache-2.0",
            ),
            layouts = mapOf(
                "numeric" to listOf(LayoutArrangementComponent("sample", "Sample", listOf("Test"), "ltr")),
            ),
            popupMappings = listOf(PopupMappingComponent("sample", "Sample", listOf("Test"))),
        )
        val manifest = ExtensionJsonConfig.encodeToString<Extension>(extension)
        val firstStage = File(context.cacheDir, "layout-cache-first-${UUID.randomUUID()}")
        val secondStage = File(context.cacheDir, "layout-cache-second-${UUID.randomUUID()}")
        val archive = File(
            context.filesDir,
            "${ExtensionManager.IME_KEYBOARD_PATH}/${ExtensionDefaults.createFlexName(id)}",
        )
        val subtype = Subtype.DEFAULT.copy(
            popupMapping = name,
            layoutMap = Subtype.DEFAULT.layoutMap.copy(numeric = name),
        )

        try {
            writeStage(firstStage, manifest, layoutCode = '1'.code, popupCode = 'a'.code)
            manager.installNew(extension, firstStage)
            val installed = withTimeout(20_000) {
                manager.keyboardExtensions.refreshed.first { state ->
                    state.extensions.any { it.meta.id == id }
                }
            }
            withTimeout(20_000) {
                repository.snapshot.first {
                    it.generation >= installed.generation && it.layouts[LayoutType.NUMERIC]?.containsKey(name) == true
                }
            }
            val first = layoutManager.computeKeyboardAsync(KeyboardMode.NUMERIC, subtype).await()
            assertEquals('1'.code, (first.arrangement[0][0].data as TextKeyData).code)
            assertEquals(
                'a'.code,
                (first.extendedPopupMapping?.get(KeyVariation.ALL)?.get("x")?.main as TextKeyData).code,
            )

            writeStage(secondStage, manifest, layoutCode = '2'.code, popupCode = 'b'.code)
            extension.workingDir = secondStage
            manager.import(extension)
            val oldExtension = installed.extensions.first { it.meta.id == id }
            val replaced = withTimeout(20_000) {
                manager.keyboardExtensions.refreshed.first { state ->
                    state.extensions.any {
                        it.meta.id == id && it.sourceArchiveFingerprint != oldExtension.sourceArchiveFingerprint
                    }
                }
            }
            val newExtension = replaced.extensions.first { it.meta.id == id }
            assertEquals(oldExtension, newExtension) // The manifest's data-class equality did not change.
            assertNotEquals(oldExtension.sourceArchiveFingerprint, newExtension.sourceArchiveFingerprint)
            assertEquals(newExtension.sourceArchiveFingerprint, manager.getExtensionById(id)?.sourceArchiveFingerprint)
            withTimeout(20_000) {
                repository.snapshot.first { it.generation >= replaced.generation }
            }

            val second = layoutManager.computeKeyboardAsync(KeyboardMode.NUMERIC, subtype).await()
            assertEquals('2'.code, (second.arrangement[0][0].data as TextKeyData).code)
            assertEquals(
                'b'.code,
                (second.extendedPopupMapping?.get(KeyVariation.ALL)?.get("x")?.main as TextKeyData).code,
            )
            manager.delete(manager.getExtensionById(id)!!)
            withTimeout(20_000) {
                manager.keyboardExtensions.refreshed.first { state -> state.extensions.none { it.meta.id == id } }
            }
        } finally {
            layoutManager.onDestroy()
            archive.delete()
            firstStage.deleteRecursively()
            secondStage.deleteRecursively()
        }
    }

    private fun writeStage(directory: File, manifest: String, layoutCode: Int, popupCode: Int) {
        assertTrue(directory.mkdirs())
        File(directory, ExtensionDefaults.MANIFEST_FILE_NAME).writeText(manifest)
        File(directory, "layouts/numeric/sample.json").apply {
            parentFile!!.mkdirs()
            writeText("""[[{"code":$layoutCode,"label":"sample"}]]""")
        }
        File(directory, "popupMappings/sample.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"all":{"x":{"main":{"code":$popupCode,"label":"sample"}}}}""")
        }
    }
}
