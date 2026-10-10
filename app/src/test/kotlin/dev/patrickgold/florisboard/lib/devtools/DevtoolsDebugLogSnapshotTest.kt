/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
