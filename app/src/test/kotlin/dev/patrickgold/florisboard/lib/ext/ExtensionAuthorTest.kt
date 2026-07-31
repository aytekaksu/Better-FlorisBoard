/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.lib.ext

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ExtensionAuthorTest :
    FunSpec({
        val validAuthorPairs = listOf(
            "Jane Doe" to ExtensionMaintainer(name = "Jane Doe"),
            "jane123" to ExtensionMaintainer(name = "jane123"),
            "__jane__" to ExtensionMaintainer(name = "__jane__"),
            "jane.doe" to ExtensionMaintainer(name = "jane.doe"),
            "\uD801\uDC00 User" to ExtensionMaintainer(name = "\uD801\uDC00 User"),
            "Jane Doe <jane.doe@gmail.com>" to
                ExtensionMaintainer(name = "Jane Doe", email = "jane.doe@gmail.com"),
            "Jane Doe (jane-doe.com)" to ExtensionMaintainer(name = "Jane Doe", url = "jane-doe.com"),
            "Jane Doe <jane.doe@gmail.com> (jane-doe.com)" to
                ExtensionMaintainer(
                    name = "Jane Doe",
                    email = "jane.doe@gmail.com",
                    url = "jane-doe.com",
                ),
        )

        context("ExtensionAuthor.from()") {
            context("with valid, well-formatted input") {
                withData(validAuthorPairs) { (authorStr, authorObj) ->
                    ExtensionMaintainer.from(authorStr) shouldBe authorObj
                }
            }

            context("with valid, ill-formatted input") {
                withData(
                    "  Jane Doe " to ExtensionMaintainer(name = "Jane Doe"),
                    " jane123" to ExtensionMaintainer(name = "jane123"),
                    "\tJane Doe\r\n" to ExtensionMaintainer(name = "Jane Doe"),
                    "Jane Doe<jane.doe@gmail.com>(jane-doe.com)" to
                        ExtensionMaintainer(
                            name = "Jane Doe",
                            email = "jane.doe@gmail.com",
                            url = "jane-doe.com",
                        ),
                    "  Jane Doe    <jane.doe@gmail.com>     " to
                        ExtensionMaintainer(name = "Jane Doe", email = "jane.doe@gmail.com"),
                    "Jane Doe <jane(comment)@example.com>" to
                        ExtensionMaintainer(name = "Jane Doe", email = "jane(comment)@example.com"),
                    "Jane Doe (<https://example.com>)" to
                        ExtensionMaintainer(name = "Jane Doe", url = "<https://example.com>"),
                ) { (authorStr, authorObj) ->
                    ExtensionMaintainer.from(authorStr) shouldBe authorObj
                }
            }

            context("With invalid input") {
                withData(
                    nameFn = { "`$it`" },
                    "",
                    " ",
                    "<jane.doe@gmail.com>",
                    " <jane.doe@gmail.com>",
                    "<jane.doe@gmail.com> (jane-doe.com)",
                    "Jane Doe <<jane.doe@gmail.com>> ((jane-doe.com))",
                    "Jane Doe <jane.doe@gmail.com) (jane-doe.com)",
                    "Jane\u00A0Doe",
                    "\u0661jane",
                ) { authorStr ->
                    ExtensionMaintainer.from(authorStr) shouldBe null
                }
            }

            test("rejects a manifest-sized malformed suffix without regex backtracking") {
                val manifestStringLimit = 32_768
                val validName = "A".repeat(manifestStringLimit)
                ExtensionMaintainer.from(validName) shouldBe ExtensionMaintainer(validName)
                ExtensionMaintainer.from("${validName.dropLast(1)}<") shouldBe null
            }
        }

        context("Test ExtensionAuthor.toString()") {
            withData(validAuthorPairs) { (authorStr, authorObj) ->
                authorObj.toString() shouldBe authorStr
            }
        }

        test("serializer keeps the maintainer string wire format") {
            val maintainer = ExtensionMaintainer("Jane Doe", "jane@example.com", "example.com")
            Json.encodeToString(maintainer) shouldBe "\"Jane Doe <jane@example.com> (example.com)\""
            Json.decodeFromString<ExtensionMaintainer>(
                "\"Jane Doe <jane@example.com> (example.com)\"",
            ) shouldBe maintainer
        }
    })
