/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.util.Locale

class UnitUtilsTest :
    FunSpec({
        context("formats binary memory sizes at each boundary") {
            withData(
                -1L to "-1 bytes",
                0L to "0 bytes",
                1L to "1 byte",
                1023L to "1023 bytes",
                1024L to "1.00 KiB",
                1536L to "1.50 KiB",
                1024L * 1024L to "1.00 MiB",
                1536L * 1024L to "1.50 MiB",
                1024L * 1024L * 1024L to "1.00 GiB",
                1536L * 1024L * 1024L to "1.50 GiB",
            ) { (size, expected) ->
                UnitUtils.formatMemorySize(size) shouldBe expected
            }
        }

        test("formatting does not depend on the device locale") {
            val originalLocale = Locale.getDefault()
            try {
                Locale.setDefault(Locale.GERMANY)
                UnitUtils.formatMemorySize(1536L) shouldBe "1.50 KiB"
            } finally {
                Locale.setDefault(originalLocale)
            }
        }
    })
