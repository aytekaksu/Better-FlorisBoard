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

package dev.patrickgold.florisboard.lib.util

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class LaunchUtilsTest : FunSpec({
    context("safePluginHttpsUrlOrNull") {
        withData(
            "https://example.com/path" to "https://example.com/path",
            "HTTPS://example.com:443/path?query=value" to
                "https://example.com:443/path?query=value",
        ) { (url, expected) ->
            url.safePluginHttpsUrlOrNull() shouldBe expected
        }

        test("rejects invalid URLs") {
            listOf(
                "",
                "http://example.com",
                "https://user@example.com",
                "https://user%40name@example.com",
                "https:///missing-host",
                "https:example.com",
                "//example.com",
                "https://example.com:0",
                "https://example.com:65536",
                "https://example.com:not-a-port",
                "file:///data/local/tmp/file",
                "javascript:alert(1)",
                "not a URL",
            ).forEach { url ->
                withClue("URL: ${url.ifEmpty { "<empty>" }}") {
                    url.safePluginHttpsUrlOrNull() shouldBe null
                }
            }
        }
    }
})
