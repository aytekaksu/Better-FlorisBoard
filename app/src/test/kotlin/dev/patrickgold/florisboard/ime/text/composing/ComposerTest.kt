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

package dev.patrickgold.florisboard.ime.text.composing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

class ComposerTest :
    FunSpec({
        test("an empty rule set behaves like the appender") {
            val composer = WithRules("empty", "Empty", emptyMap())

            composer.toRead shouldBe 0
            composer.getActions("before", "x") shouldBe (0 to "x")
        }

        test("the longest matching rule wins regardless of key case") {
            val composer = WithRules(
                "rules",
                "Rules",
                linkedMapOf("A" to "short", "Ba" to "long"),
            )

            composer.getActions("b", "A") shouldBe (1 to "long")
        }

        test("only an uppercase initial capitalizes the replacement") {
            val composer = WithRules(
                "case",
                "Case",
                mapOf("ab" to "value", "1b" to "value"),
            )

            composer.getActions("A", "b") shouldBe (1 to "VALUE")
            composer.getActions("a", "b") shouldBe (1 to "value")
            composer.getActions("1", "b") shouldBe (1 to "value")
        }

        test("replacement casing is independent of the device locale") {
            val composer = WithRules("case", "Case", mapOf("ab" to "i"))
            val originalLocale = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("tr"))
                composer.getActions("A", "b") shouldBe (1 to "I")
            } finally {
                Locale.setDefault(originalLocale)
            }
        }

        test("empty rule keys are rejected") {
            shouldThrow<IllegalArgumentException> {
                WithRules("invalid", "Invalid", mapOf("" to "value"))
            }
        }
    })
