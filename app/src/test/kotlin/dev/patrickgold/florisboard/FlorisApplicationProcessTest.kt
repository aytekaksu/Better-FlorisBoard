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
