/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.keyboard.invalidatesPredictionHints
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.lib.FlorisLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class PredictionCodePointVariantsTest : FunSpec({
    test("predictive hints match shifted keys using the active locale") {
        val turkish = Subtype.DEFAULT.copy(
            primaryLocale = FlorisLocale.from("tr", "TR"),
        )

        predictionCodePointVariants(setOf('i'.code, 'ı'.code), turkish) shouldContainAll
            setOf('i'.code, 'İ'.code, 'ı'.code, 'I'.code)
    }

    test("invalid provider code points are ignored") {
        predictionCodePointVariants(
            setOf(-1, Character.MAX_CODE_POINT + 1),
            Subtype.DEFAULT,
        ) shouldBe emptySet()
    }

    test("Greek final sigma predictions match the ordinary sigma key") {
        predictionCodePointVariants(setOf('ς'.code), Subtype.DEFAULT) shouldContainAll
            setOf('ς'.code, 'Σ'.code, 'σ'.code)
    }

    test("modifier keys preserve the next-key prediction lease") {
        listOf(
            TextKeyData.SHIFT,
            TextKeyData.CAPS_LOCK,
            TextKeyData.CTRL,
            TextKeyData.ALT,
            TextKeyData.FN,
        ).forEach { it.invalidatesPredictionHints() shouldBe false }
        TextKeyData.SPACE.invalidatesPredictionHints() shouldBe true
        TextKeyData.DELETE.invalidatesPredictionHints() shouldBe true
    }

    test("a stale lease cannot consume hints from a newer request") {
        val currentLease = PredictionHintLease(2L, setOf('b'.code))
        val staleLease = PredictionHintLease(1L, setOf('a'.code))

        isPredictionHintLeaseCurrent(null, latestRequestId = 2L) shouldBe true
        isPredictionHintLeaseCurrent(currentLease, latestRequestId = 2L) shouldBe true
        isPredictionHintLeaseCurrent(staleLease, latestRequestId = 2L) shouldBe false
        isPredictionHintLeaseCurrent(PredictionHintLease.Empty, latestRequestId = 2L) shouldBe false
    }
})
