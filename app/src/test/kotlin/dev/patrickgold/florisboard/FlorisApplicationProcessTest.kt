/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream

class FlorisApplicationProcessTest :
    FunSpec({
        test("only the exact clipboard import process skips full initialization") {
            val packageName = "dev.patrickgold.florisboard"

            shouldInitializeFlorisApplication(packageName, packageName) shouldBe true
            shouldInitializeFlorisApplication(
                packageName,
                "$packageName:clipboard_import",
            ) shouldBe false
            shouldInitializeFlorisApplication(
                packageName,
                "$packageName:clipboard_import:child",
            ) shouldBe true
            shouldInitializeFlorisApplication(packageName, "$packageName:other") shouldBe true
            shouldInitializeFlorisApplication(packageName, "other.app:clipboard_import") shouldBe true
            shouldInitializeFlorisApplication(packageName, null) shouldBe true
            shouldInitializeFlorisApplication(packageName, "") shouldBe true
            shouldInitializeFlorisApplication("", ":clipboard_import") shouldBe true
        }

        test("legacy cmdline parsing reads one bounded null-terminated name") {
            val bytes = "dev.patrickgold.florisboard:clipboard_import\u0000ignored"
                .toByteArray()

            readBoundedProcessName(ByteArrayInputStream(bytes)) shouldBe
                "dev.patrickgold.florisboard:clipboard_import"
        }

        test("legacy cmdline parsing fails open on incomplete or oversized input") {
            readBoundedProcessName(
                ByteArrayInputStream("dev.patrickgold.florisboard".toByteArray()),
            ).shouldBeNull()
            readBoundedProcessName(
                ByteArrayInputStream(
                    (buildString { repeat(1_024) { append('a') } } + "\u0000")
                        .toByteArray(),
                ),
            ).shouldBeNull()
        }
    })
