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
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.keyboardExtensionRepository
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.popup.PopupMappingComponent
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LayoutCacheRefreshAndroidTest {
    @Test
    fun subtypeLayoutChoicesStayDistinctAcrossKeyboardModes(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.extensionManager().value
        manager.keyboardExtensions.init()
        val repository = context.keyboardExtensionRepository().value
        val layoutManager = LayoutManager(context)
        val prefs by FlorisPreferenceStore
        val originalNumberRow = prefs.keyboard.numberRow.get()
        val id = "local.keyboard.modes_${UUID.randomUUID().toString().replace("-", "")}"
        val modes = listOf(
            Triple(KeyboardMode.CHARACTERS, LayoutType.CHARACTERS, "chars"),
            Triple(KeyboardMode.SYMBOLS, LayoutType.SYMBOLS, "symbols"),
            Triple(KeyboardMode.SYMBOLS2, LayoutType.SYMBOLS2, "symbols2"),
            Triple(KeyboardMode.NUMERIC, LayoutType.NUMERIC, "numeric"),
            Triple(KeyboardMode.NUMERIC_ADVANCED, LayoutType.NUMERIC_ADVANCED, "advanced"),
            Triple(KeyboardMode.PHONE, LayoutType.PHONE, "phone"),
            Triple(KeyboardMode.PHONE2, LayoutType.PHONE2, "phone2"),
        )
        val row = LayoutType.NUMERIC_ROW to "row"
        val layoutIds = (modes.map { it.second to it.third } + row).toMap()
        val markers = layoutIds.keys.withIndex().associate { (index, type) -> type to (0xE100 + index) }
        val modifierName = ExtensionComponentName(id, "symbols_modifier")
        val modifierMarker = 0xE1FF
        fun name(type: LayoutType) = ExtensionComponentName(id, requireNotNull(layoutIds[type]))
        fun codes(keyboard: TextKeyboard) = keyboard.arrangement.flatMap { keyboardRow ->
            keyboardRow.mapNotNull { (it.data as? TextKeyData)?.code }
        }
        val extension = KeyboardExtension(
            meta = ExtensionMeta(
                id = id,
                version = "1.0.0",
                title = "Mode selection test",
                maintainers = listOf(ExtensionMaintainer("Test")),
                license = "apache-2.0",
            ),
            layouts = layoutIds.map { (type, componentId) ->
                type.id to listOf(LayoutArrangementComponent(
                    id = componentId,
                    label = componentId,
                    authors = listOf("Test"),
                    direction = "ltr",
                    modifier = modifierName.takeIf { type == LayoutType.SYMBOLS },
                ))
            }.toMap() + (LayoutType.SYMBOLS_MOD.id to listOf(
                LayoutArrangementComponent("symbols_modifier", "Modifier", listOf("Test"), "ltr"),
            )),
        )
        val subtype = Subtype.DEFAULT.copy(layoutMap = Subtype.DEFAULT.layoutMap.copy(
            characters = name(LayoutType.CHARACTERS),
            symbols = name(LayoutType.SYMBOLS),
            symbols2 = name(LayoutType.SYMBOLS2),
            numeric = name(LayoutType.NUMERIC),
            numericAdvanced = name(LayoutType.NUMERIC_ADVANCED),
            numericRow = name(LayoutType.NUMERIC_ROW),
            phone = name(LayoutType.PHONE),
            phone2 = name(LayoutType.PHONE2),
        ))
        val stage = File(context.cacheDir, "layout-modes-${UUID.randomUUID()}")
        val archive = File(
            context.filesDir,
            "${ExtensionManager.IME_KEYBOARD_PATH}/${ExtensionDefaults.createFlexName(id)}",
        )

        try {
            assertTrue(stage.mkdirs())
            File(stage, ExtensionDefaults.MANIFEST_FILE_NAME).writeText(ExtensionJsonConfig.encodeToString<Extension>(extension))
            for ((type, componentId) in layoutIds) {
                File(stage, "layouts/${type.id}/$componentId.json").apply {
                    parentFile!!.mkdirs()
                    writeText("""[[{"code":${markers.getValue(type)},"label":"fixture"}]]""")
                }
            }
            File(stage, "layouts/${LayoutType.SYMBOLS_MOD.id}/symbols_modifier.json").apply {
                parentFile!!.mkdirs()
                writeText("""[[{"code":$modifierMarker,"label":"modifier"},{"code":0,"label":"slot"}]]""")
            }
            manager.installNew(extension, stage)
            withTimeout(20_000) {
                repository.snapshot.first { snapshot ->
                    layoutIds.keys.all { snapshot.layouts[it]?.containsKey(name(it)) == true } &&
                        snapshot.layouts[LayoutType.SYMBOLS_MOD]?.containsKey(modifierName) == true
                }
            }

            prefs.keyboard.numberRow.set(false).getOrThrow()
            for ((mode, type, _) in modes) {
                val keyboard = withTimeout(20_000) { layoutManager.computeKeyboardAsync(mode, subtype).await() }
                assertEquals(mode, keyboard.mode)
                assertTrue("$mode did not use ${type.id}", markers.getValue(type) in codes(keyboard))
                if (mode == KeyboardMode.CHARACTERS) {
                    assertFalse(markers.getValue(LayoutType.NUMERIC_ROW) in codes(keyboard))
                }
                if (mode == KeyboardMode.SYMBOLS) {
                    assertEquals(markers.getValue(LayoutType.NUMERIC_ROW), codes(keyboard).first())
                    assertEquals(listOf(modifierMarker, markers.getValue(type)), codes(keyboard).takeLast(2))
                }
            }

            prefs.keyboard.numberRow.set(true).getOrThrow()
            val charactersWithRow = withTimeout(20_000) {
                layoutManager.computeKeyboardAsync(KeyboardMode.CHARACTERS, subtype).await()
            }
            assertEquals(markers.getValue(LayoutType.NUMERIC_ROW), codes(charactersWithRow).first())
            assertTrue(markers.getValue(LayoutType.CHARACTERS) in codes(charactersWithRow))

            for (mode in listOf(KeyboardMode.UNSPECIFIED, KeyboardMode.SMARTBAR_QUICK_ACTIONS)) {
                val keyboard = withTimeout(20_000) { layoutManager.computeKeyboardAsync(mode, subtype).await() }
                assertEquals(mode, keyboard.mode)
                assertEquals(0, keyboard.rowCount)
            }
        } finally {
            prefs.keyboard.numberRow.set(originalNumberRow).getOrThrow()
            layoutManager.onDestroy()
            manager.getExtensionById(id)?.let { manager.delete(it) }
            archive.delete()
            stage.deleteRecursively()
        }
    }

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
