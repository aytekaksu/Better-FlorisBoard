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

package dev.patrickgold.florisboard.ime.input

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InputShiftStateTest : FunSpec({
    test("automatic shift requires every eligibility condition") {
        val cases = listOf(
            Triple(false, false, false) to InputShiftState.UNSHIFTED,
            Triple(false, false, true) to InputShiftState.UNSHIFTED,
            Triple(false, true, false) to InputShiftState.UNSHIFTED,
            Triple(false, true, true) to InputShiftState.UNSHIFTED,
            Triple(true, false, false) to InputShiftState.UNSHIFTED,
            Triple(true, false, true) to InputShiftState.UNSHIFTED,
            Triple(true, true, false) to InputShiftState.UNSHIFTED,
            Triple(true, true, true) to InputShiftState.SHIFTED_AUTOMATIC,
        )

        for ((inputs, expected) in cases) {
            val (autoCapitalization, supportsCapitalization, hasCursorCapsMode) = inputs
            automaticShiftState(autoCapitalization, { supportsCapitalization }, { hasCursorCapsMode }) shouldBe expected
        }
    }

    test("ineligible shift does not read later dependencies") {
        var localeReads = 0
        var editorReads = 0
        val locale = { localeReads++; false }
        val editor = { editorReads++; true }

        automaticShiftState(false, locale, editor) shouldBe InputShiftState.UNSHIFTED
        localeReads shouldBe 0
        editorReads shouldBe 0

        automaticShiftState(true, locale, editor) shouldBe InputShiftState.UNSHIFTED
        localeReads shouldBe 1
        editorReads shouldBe 0
    }
})
