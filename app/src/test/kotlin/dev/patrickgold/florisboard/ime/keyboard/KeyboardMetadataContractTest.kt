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

import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.lib.ext.validateForImport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
            val declaredFiles = extension.layouts.flatMap { (typeId, components) ->
                val type = requireNotNull(LayoutType.fromId(typeId))
                components.map { it.arrangementFile(type) }
            }
            val packagedFiles = assetRoot.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(assetRoot).invariantSeparatorsPath }
                .filter { it != "extension.json" }
                .toList()
            packagedFiles.sorted() shouldBe declaredFiles.sorted()
        }
    })
