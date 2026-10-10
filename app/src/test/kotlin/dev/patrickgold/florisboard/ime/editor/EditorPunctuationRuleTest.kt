/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.editor

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.PunctuationRule
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EditorPunctuationRuleTest : FunSpec({
    val id = ExtensionComponentName("org.example.keyboard", "custom")
    val subtype = Subtype.DEFAULT.copy(punctuationRule = id)
    val customRule = PunctuationRule.Fallback.copy(id = "custom")

    test("editor uses the active subtype's punctuation rule") {
        resolvePunctuationRule(subtype, mapOf(id to customRule)) shouldBe customRule
    }

    test("editor falls back when the subtype's punctuation rule is missing") {
        resolvePunctuationRule(subtype, emptyMap()) shouldBe PunctuationRule.Fallback
    }
})
