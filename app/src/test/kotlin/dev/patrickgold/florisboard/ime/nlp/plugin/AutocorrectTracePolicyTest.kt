/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
