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
    })
