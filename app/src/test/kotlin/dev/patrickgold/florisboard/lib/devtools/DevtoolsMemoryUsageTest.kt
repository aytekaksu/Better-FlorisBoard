/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.devtools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

class DevtoolsMemoryUsageTest : FunSpec({
    test("memory reports keep stable formatting and hide failure messages") {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            Devtools.formatMemoryUsage { 1536L to 4096L } shouldBe "1.50 KiB (37.50% used, 4.00 KiB max)"
            Devtools.formatMemoryUsage { throw IllegalStateException("private detail") } shouldBe
                "Failed to retrieve memory usage: IllegalStateException"
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
})
