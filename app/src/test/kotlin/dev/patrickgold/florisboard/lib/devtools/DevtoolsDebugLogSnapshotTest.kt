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

package dev.patrickgold.florisboard.lib.devtools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DevtoolsDebugLogSnapshotTest : FunSpec({
    val header = "======= SYSTEM INFO =======\nTime                : fixed\n"
    val diagnostics = "======= APP DIAGNOSTICS =======\nW/Reporter: state=idle\n"

    test("plain and GitHub reports render the same captured sections exactly") {
        val snapshot = Devtools.DebugLogSnapshot(header, diagnostics)

        Devtools.renderDebugLog(snapshot) shouldBe "$header\n$diagnostics"
        Devtools.renderDebugLogForGithub(snapshot) shouldBe listOf(
            "<details>",
            "<summary>Diagnostic report header</summary>",
            "",
            "```",
            "======= SYSTEM INFO =======",
            "Time                : fixed",
            "",
            "```",
            "</details>",
            "",
            "<details>",
            "<summary>App diagnostics</summary>",
            "",
            "```",
            "======= APP DIAGNOSTICS =======",
            "W/Reporter: state=idle",
            "",
            "```",
            "</details>",
        ).joinToString("\n", postfix = "\n")
    }

    test("header-only reports preserve the plain and GitHub output shapes") {
        val snapshot = Devtools.DebugLogSnapshot(header, diagnostics = null)

        Devtools.renderDebugLog(snapshot) shouldBe header
        Devtools.renderDebugLogForGithub(snapshot) shouldBe listOf(
            "<details>",
            "<summary>Diagnostic report header</summary>",
            "",
            "```",
            "======= SYSTEM INFO =======",
            "Time                : fixed",
            "",
            "```",
            "</details>",
        ).joinToString("\n", postfix = "\n")
    }
})
