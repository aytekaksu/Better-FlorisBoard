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

import dev.patrickgold.florisboard.ime.popup.PopupMapping
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.lib.ext.validateForImport
import dev.patrickgold.florisboard.lib.io.DefaultJsonConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import java.io.File

class KeyboardMetadataContractTest :
    FunSpec({
        test("layout type IDs preserve compatibility") {
            LayoutType.entries.forEach { LayoutType.fromId(it.id) shouldBe it }
            LayoutType.fromId("future-layout") shouldBe null
            LayoutTypeId.EXTENSION shouldBe "extension"
            LayoutType.fromId(LayoutTypeId.EXTENSION) shouldBe LayoutType.EXTENSION
        }

        test("retired keyboard mode IDs fall back without renumbering live modes") {
            KeyboardMode.entries.map(KeyboardMode::toInt) shouldBe listOf(-1, 0, 2, 3, 4, 5, 6, 7, 10)
            KeyboardMode.entries.forEach { KeyboardMode.fromInt(it.toInt()) shouldBe it }
            listOf(1, 8, 9).forEach { retiredId ->
                KeyboardMode.fromInt(retiredId) shouldBe KeyboardMode.CHARACTERS
                KeyboardState.new(retiredId.toULong()).keyboardMode shouldBe KeyboardMode.CHARACTERS
            }
        }

        test("bundled layout metadata points to packaged arrangements") {
            val assetRoot = sequenceOf("src/main/assets", "app/src/main/assets")
                .map { File(it, "ime/keyboard/org.florisboard.layouts") }.first { it.isDirectory }
            val extension = ExtensionJsonConfig.decodeFromString(
                KeyboardExtension.serializer(),
                assetRoot.resolve("extension.json").readText(),
            )
            extension.validateForImport().isValid shouldBe true
            extension.layouts.mapValues { (_, components) -> components.size } shouldBe mapOf(
                "characters" to 76,
                "charactersMod" to 15,
                "numeric" to 2,
                "numericAdvanced" to 3,
                "numericRow" to 17,
                "phone" to 1,
                "phone2" to 1,
                "symbols" to 9,
                "symbolsMod" to 4,
                "symbols2" to 6,
                "symbols2Mod" to 2,
            )
            listOf(
                Triple("characters", "swiss_italian", "layouts/characters/swiss_german.json"),
                Triple("characters", "persian3", "layouts/characters/persian.json"),
                Triple("characters", "udmurt_compact", "layouts/characters/jcuken_russian.json"),
                Triple("charactersMod", "persian3", "layouts/charactersMod/arabic.json"),
                Triple("symbols2", "persian", "layouts/symbols2/eastern.json"),
                Triple("symbols2", "western", "layouts/symbols2/eastern.json"),
            ).forEach { (typeId, id, path) ->
                extension.layouts.getValue(typeId).single { it.id == id }
                    .arrangementFile(requireNotNull(LayoutType.fromId(typeId))) shouldBe path
            }
            val declaredFiles = extension.layouts.flatMap { (typeId, components) ->
                val type = requireNotNull(LayoutType.fromId(typeId))
                components.map { it.arrangementFile(type) }
            }
            val packagedFiles = assetRoot.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(assetRoot).invariantSeparatorsPath }
                .filter { it != "extension.json" }
                .toList()
            val generatedNumericRows = setOf(
                "devanagari", "eastern_arabic", "gujarati", "gurmukhi", "kannada", "malayalam",
                "oriya", "persian", "tamil", "telugu", "warang_citi",
            ).mapTo(mutableSetOf()) { "layouts/numericRow/$it.json" }
            packagedFiles.size shouldBe packagedFiles.toSet().size
            (packagedFiles.toSet() + generatedNumericRows) shouldBe declaredFiles.toSet()
            packagedFiles.forEach { path ->
                DefaultJsonConfig.decodeFromString<LayoutArrangement>(assetRoot.resolve(path).readText())
            }
        }

        test("bundled popup metadata exposes every packaged mapping") {
            val assetRoot = sequenceOf("src/main/assets", "app/src/main/assets")
                .map { File(it, "ime/keyboard/org.florisboard.localization") }.first { it.isDirectory }
            val generatedRoot = sequenceOf("build/generated", "app/build/generated")
                .map { File(it, "popupMappingAssets/debug/ime/keyboard/org.florisboard.localization") }
                .first { it.isDirectory }
            val extension = ExtensionJsonConfig.decodeFromString(
                KeyboardExtension.serializer(),
                assetRoot.resolve("extension.json").readText(),
            )
            extension.validateForImport().isValid shouldBe true

            val declaredFiles = extension.popupMappings.map { it.mappingFile() }
            assetRoot.resolve("popupMappings").exists() shouldBe false
            val packagedFiles = generatedRoot.resolve("popupMappings").walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(generatedRoot).invariantSeparatorsPath }
                .toList()
            packagedFiles.size shouldBe 57
            packagedFiles.sorted() shouldBe declaredFiles.sorted()
            packagedFiles.forEach { path ->
                DefaultJsonConfig.decodeFromString<PopupMapping>(generatedRoot.resolve(path).readText())
            }

            val declaredIds = extension.popupMappings.mapTo(mutableSetOf()) { it.id }
            extension.subtypePresets.filter {
                it.popupMapping.extensionId == extension.meta.id
            }.mapNotNull {
                it.popupMapping.componentId.takeUnless(declaredIds::contains)
            } shouldBe emptyList()
        }
    })
