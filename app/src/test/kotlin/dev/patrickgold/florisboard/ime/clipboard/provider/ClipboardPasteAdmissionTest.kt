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

package dev.patrickgold.florisboard.ime.clipboard.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipboardPasteAdmissionTest :
    FunSpec({
        test("admits the last available root and exact byte limit") {
            pasteAdmissionFits(
                activeRootSizes = mapOf(1L to 40L, 2L to 50L),
                candidateId = 3L,
                candidateBytes = 10L,
                maxRoots = 3,
                maxBytes = 100L,
            ) shouldBe true
        }

        test("rejects a new root without evicting accepted roots") {
            val acceptedRoots = mapOf(1L to 60L, 2L to 40L)

            pasteAdmissionFits(
                activeRootSizes = acceptedRoots,
                candidateId = 3L,
                candidateBytes = 1L,
                maxRoots = 3,
                maxBytes = 100L,
            ) shouldBe false
            acceptedRoots shouldBe mapOf(1L to 60L, 2L to 40L)
        }

        test("counts a renewed root once") {
            pasteAdmissionFits(
                activeRootSizes = mapOf(1L to 60L, 2L to 40L),
                candidateId = 2L,
                candidateBytes = 40L,
                maxRoots = 2,
                maxBytes = 100L,
            ) shouldBe true
        }

        test("fails closed for invalid and overflowing usage") {
            pasteAdmissionFits(
                activeRootSizes = mapOf(1L to Long.MAX_VALUE),
                candidateId = 2L,
                candidateBytes = 1L,
                maxRoots = 2,
                maxBytes = Long.MAX_VALUE,
            ) shouldBe false
            pasteAdmissionFits(
                activeRootSizes = emptyMap(),
                candidateId = 1L,
                candidateBytes = 0L,
                maxRoots = 1,
                maxBytes = 1L,
            ) shouldBe false
        }
    })
