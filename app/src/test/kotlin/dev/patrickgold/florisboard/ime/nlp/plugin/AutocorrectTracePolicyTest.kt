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

package dev.patrickgold.florisboard.ime.nlp.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.autocorrect.api.AutocorrectTouchPoint

class AutocorrectTracePolicyTest :
    FunSpec({
        test("matches bounded key fragments without joining them") {
            traceTextMatches(
                points = listOf(point("th"), point("e"), point("😀")),
                expectedText = "the😀",
            ) shouldBe true
        }

        test("rejects a different prefix suffix or fragment boundary") {
            traceTextMatches(listOf(point("a")), "") shouldBe false
            traceTextMatches(listOf(point("a")), "ab") shouldBe false
            traceTextMatches(listOf(point("b")), "a") shouldBe false
            traceTextMatches(listOf(point("ab"), point("c")), "abx") shouldBe false
        }

        test("empty trace matches only empty text") {
            traceTextMatches(emptyList(), "") shouldBe true
            traceTextMatches(emptyList(), "a") shouldBe false
        }
    })

private fun point(text: String) = AutocorrectTouchPoint(
    text = text,
    x = 0.5f,
    y = 0.5f,
)
