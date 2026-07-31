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

package dev.patrickgold.florisboard.app.ext

import dev.patrickgold.florisboard.app.settings.theme.PrettyPrintConfig
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponentImpl
import dev.patrickgold.florisboard.lib.ext.ExtensionMaintainer
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import dev.patrickgold.florisboard.lib.ext.decodeExtensionManifest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.lib.snygg.SnyggStylesheet

class ExtensionEditorCreationTest :
    FunSpec({
        test("an empty theme component starts with a valid stylesheet") {
            val editor = newEmptyThemeComponentEditor(
                id = "new_theme",
                label = "New theme",
                authors = listOf("Author"),
            )

            editor.build().stylesheetPath() shouldBe "stylesheets/new_theme.json"
            val stylesheet = checkNotNull(editor.stylesheetEditor)
                .build()
                .toJson(PrettyPrintConfig)
                .getOrThrow()
            SnyggStylesheet.fromJson(stylesheet, PrettyPrintConfig).getOrThrow()
        }

        test("only theme extension lists expose editor support") {
            ExtensionListScreenType.EXT_THEME.editorSerialType shouldBe ThemeExtension.SERIAL_TYPE
            ExtensionListScreenType.EXT_KEYBOARD.editorSerialType shouldBe null
            ExtensionListScreenType.EXT_LANGUAGEPACK.editorSerialType shouldBe null
        }

        test("theme editing and building are detached and preserve polymorphic serialization") {
            val original = ThemeExtension(
                meta = ExtensionMeta(
                    id = "org.example.themes",
                    version = "1.0.0",
                    title = "Original",
                    maintainers = listOf(ExtensionMaintainer("Author")),
                    license = "apache-2.0",
                ),
                dependencies = listOf("org.example.base"),
                themes = listOf(
                    ThemeExtensionComponentImpl(
                        id = "day",
                        label = "Day",
                        authors = listOf("Author"),
                        isNightTheme = false,
                    ),
                ),
            )
            val editor = original.edit()
            editor.meta = editor.meta.copy(title = "Edited")
            editor.dependencies.add("org.example.extra")
            editor.themes.single().label = "Edited day"

            val edited = editor.build()
            val savePlan = createExtensionEditorSavePlan(editor, edited)
            editor.meta = editor.meta.copy(title = "After build")
            editor.dependencies.clear()
            editor.themes.single().label = "After build"

            original.meta.title shouldBe "Original"
            original.dependencies shouldBe listOf("org.example.base")
            original.themes.single().label shouldBe "Day"
            edited.meta.title shouldBe "Edited"
            edited.dependencies shouldBe listOf("org.example.base", "org.example.extra")
            edited.themes.single().label shouldBe "Edited day"

            val decoded = decodeExtensionManifest(savePlan.serializedManifest).getOrThrow() as ThemeExtension
            decoded.meta.title shouldBe "Edited"
            decoded.dependencies shouldBe listOf("org.example.base", "org.example.extra")
            decoded.themes.single().label shouldBe "Edited day"
            createExtensionEditorSavePlan(decoded.edit(), decoded).serializedManifest shouldBe
                savePlan.serializedManifest
        }
    })
