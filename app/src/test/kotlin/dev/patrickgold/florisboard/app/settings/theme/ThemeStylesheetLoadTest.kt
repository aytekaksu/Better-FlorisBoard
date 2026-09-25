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

package dev.patrickgold.florisboard.app.settings.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.florisboard.lib.snygg.SnyggAnnotationRule
import org.florisboard.lib.snygg.SnyggElementRule
import org.florisboard.lib.snygg.SnyggStylesheet
import java.io.RandomAccessFile
import java.nio.file.Files

class ThemeStylesheetLoadTest : FunSpec({
    val root = Files.createTempDirectory("theme-stylesheet-load")
    val path = "stylesheets/theme.json"
    val file = root.resolve(path)

    beforeEach {
        Files.createDirectories(file.parent)
    }
    afterEach {
        root.toFile().deleteRecursively()
        Files.createDirectories(root)
    }
    afterSpec {
        root.toFile().deleteRecursively()
    }

    test("valid stylesheet loads under both strict strategies and gains defines") {
        runTest {
            file.toFile().writeText("""{
                "${'$'}schema": "${SnyggStylesheet.SCHEMA_V2}",
                "smartbar": {"background": "#ff0000"}
            }""".trimIndent())

            for (strategy in listOf(
                StylesheetLoadingStrategy.TRY_LOAD_OR_ASK_ON_CONFLICT,
                StylesheetLoadingStrategy.TRY_LOAD_OR_EMPTY,
            )) {
                val result = loadThemeStylesheetEditor(root, path, strategy) as StylesheetLoadResult.Ready
                result.editor.rules.containsKey(SnyggElementRule("smartbar")) shouldBe true
                result.editor.rules.containsKey(SnyggAnnotationRule.Defines) shouldBe true
            }
        }
    }

    test("strict conflict waits for a choice, lenient retry salvages valid rules") {
        runTest {
            file.toFile().writeText("""{"smartbar": {"background": "#ff0000"}}""")

            loadThemeStylesheetEditor(
                root, path, StylesheetLoadingStrategy.TRY_LOAD_OR_ASK_ON_CONFLICT,
            ) shouldBe StylesheetLoadResult.Conflict

            val lenient = loadThemeStylesheetEditor(
                root, path, StylesheetLoadingStrategy.TRY_LOAD_OR_PARSE_LENIENT,
            ) as StylesheetLoadResult.Ready
            lenient.editor.rules.containsKey(SnyggElementRule("smartbar")) shouldBe true

            val empty = loadThemeStylesheetEditor(
                root, path, StylesheetLoadingStrategy.TRY_LOAD_OR_EMPTY,
            ) as StylesheetLoadResult.Ready
            empty.editor.rules.keys.toList() shouldBe listOf(SnyggAnnotationRule.Defines)
        }
    }

    test("missing, unsafe, linked, and oversized files start empty without a prompt") {
        runTest {
            val linked = root.resolve("stylesheets/linked.json")
            Files.createSymbolicLink(linked, root.resolve("outside.json"))
            val oversized = root.resolve("stylesheets/oversized.json")
            RandomAccessFile(oversized.toFile(), "rw").use {
                it.setLength(8L * 1_024 * 1_024 + 1)
            }

            for (candidate in listOf("stylesheets/missing.json", "../outside.json",
                "stylesheets/linked.json", "stylesheets/oversized.json")) {
                val result = loadThemeStylesheetEditor(
                    root, candidate, StylesheetLoadingStrategy.TRY_LOAD_OR_ASK_ON_CONFLICT,
                ) as StylesheetLoadResult.Ready
                result.editor.rules.keys.toList() shouldBe listOf(SnyggAnnotationRule.Defines)
            }
        }
    }
})
