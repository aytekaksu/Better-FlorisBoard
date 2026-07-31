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
