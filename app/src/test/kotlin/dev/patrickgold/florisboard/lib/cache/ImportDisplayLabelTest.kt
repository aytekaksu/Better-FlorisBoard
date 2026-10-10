/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.cache

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ImportDisplayLabelTest :
    FunSpec({
        test("provider labels are bounded and stripped of path and control syntax") {
            val rawLabel = "  ../private\\\u0000\u202esecret.flex  " +
                "x".repeat(CacheManager.MaxImportDisplayLabelLength)

            val label = CacheManager.sanitizeImportDisplayLabel(rawLabel)

            label.length shouldBe CacheManager.MaxImportDisplayLabelLength
            label.contains('/') shouldBe false
            label.contains('\\') shouldBe false
            label.any(Char::isISOControl) shouldBe false
            label.contains('\u202e') shouldBe false
        }

        test("missing or unusable provider labels use a neutral display label") {
            CacheManager.sanitizeImportDisplayLabel(null) shouldBe "Extension file"
            CacheManager.sanitizeImportDisplayLabel(" /\u0000\\ ") shouldBe "Extension file"
        }

        test("ordinary labels remain readable") {
            CacheManager.sanitizeImportDisplayLabel("  My   themes.flex  ") shouldBe "My themes.flex"
        }
    })
