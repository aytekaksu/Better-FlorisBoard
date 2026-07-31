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

class ClipboardExternalCapabilityTest :
    FunSpec({
        test("never-exposed media is not quarantined") {
            externalCapabilityIsQuarantined(
                stampedBootCount = null,
                currentBootCount = 42,
            ) shouldBe false
        }

        test("same-boot media remains quarantined") {
            externalCapabilityIsQuarantined(
                stampedBootCount = 42,
                currentBootCount = 42,
            ) shouldBe true
        }

        test("a later known boot releases the quarantine") {
            externalCapabilityIsQuarantined(
                stampedBootCount = 42,
                currentBootCount = 43,
            ) shouldBe false
        }

        test("an unavailable stamp or current count fails closed") {
            externalCapabilityIsQuarantined(
                stampedBootCount = -1,
                currentBootCount = 43,
            ) shouldBe true
            externalCapabilityIsQuarantined(
                stampedBootCount = 42,
                currentBootCount = -1,
            ) shouldBe true
        }

        test("public root access requires an exact known boot stamp") {
            externalCapabilityIsCurrent(
                stampedBootCount = 42,
                currentBootCount = 42,
            ) shouldBe true
            listOf(
                null to 42,
                -1 to 42,
                41 to 42,
                42 to -1,
                -1 to -1,
            ).forEach { (stamped, current) ->
                externalCapabilityIsCurrent(stamped, current) shouldBe false
            }
        }

        test("legacy rows are stamped with the current boot when available") {
            normalizeExternalCapabilityBootCount(
                stampedBootCount = LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT,
                isSystemRoot = false,
                pasteRetainedUntilMs = 0L,
                currentBootCount = 42,
            ) shouldBe 42
        }

        test("legacy rows remain recoverable when the current boot is unavailable") {
            normalizeExternalCapabilityBootCount(
                stampedBootCount = LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT,
                isSystemRoot = false,
                pasteRetainedUntilMs = 0L,
                currentBootCount = -1,
            ) shouldBe LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT
            normalizeExternalCapabilityBootCount(
                stampedBootCount = -1,
                isSystemRoot = false,
                pasteRetainedUntilMs = 0L,
                currentBootCount = -1,
            ) shouldBe -1
            normalizeExternalCapabilityBootCount(
                stampedBootCount = -1,
                isSystemRoot = false,
                pasteRetainedUntilMs = 0L,
                currentBootCount = 42,
            ) shouldBe -1
            normalizeExternalCapabilityBootCount(
                stampedBootCount = -3,
                isSystemRoot = false,
                pasteRetainedUntilMs = 0L,
                currentBootCount = 42,
            ) shouldBe -1
        }

        test("new private rows remain unstamped") {
            normalizeExternalCapabilityBootCount(
                stampedBootCount = null,
                isSystemRoot = false,
                pasteRetainedUntilMs = 0L,
                currentBootCount = 42,
            ) shouldBe null
        }

        test("legacy delivery roots without a stamp are backfilled") {
            normalizeExternalCapabilityBootCount(
                stampedBootCount = null,
                isSystemRoot = true,
                pasteRetainedUntilMs = 0L,
                currentBootCount = 42,
            ) shouldBe 42
            normalizeExternalCapabilityBootCount(
                stampedBootCount = null,
                isSystemRoot = false,
                pasteRetainedUntilMs = 1L,
                currentBootCount = 42,
            ) shouldBe 42
            normalizeExternalCapabilityBootCount(
                stampedBootCount = null,
                isSystemRoot = true,
                pasteRetainedUntilMs = 0L,
                currentBootCount = -1,
            ) shouldBe LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT
        }
    })
