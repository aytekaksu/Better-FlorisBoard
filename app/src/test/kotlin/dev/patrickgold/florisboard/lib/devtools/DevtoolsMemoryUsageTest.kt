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
