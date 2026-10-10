/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
