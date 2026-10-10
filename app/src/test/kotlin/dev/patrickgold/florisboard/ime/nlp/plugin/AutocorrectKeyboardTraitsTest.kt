/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard.KeyboardState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.autocorrect.api.AutocorrectCapsMode

class AutocorrectKeyboardTraitsTest : FunSpec({
    test("keyboard traits are sampled on demand and earlier snapshots stay immutable") {
        val state = KeyboardState.new()
        var sourceReads = 0
        val currentTraits = liveAutocorrectKeyboardTraits {
            sourceReads++
            state
        }
        sourceReads shouldBe 0

        val first = currentTraits()
        first shouldBe AutocorrectKeyboardTraits(false, AutocorrectCapsMode.UNSHIFTED)

        state.isIncognitoMode = true
        state.inputShiftState = InputShiftState.CAPS_LOCK
        val second = currentTraits()

        sourceReads shouldBe 2
        first shouldBe AutocorrectKeyboardTraits(false, AutocorrectCapsMode.UNSHIFTED)
        second shouldBe AutocorrectKeyboardTraits(true, AutocorrectCapsMode.CAPS_LOCK)
    }

    test("every live shift state keeps its provider caps meaning") {
        val state = KeyboardState.new()
        val currentTraits = liveAutocorrectKeyboardTraits { state }
        val expected = mapOf(
            InputShiftState.UNSHIFTED to AutocorrectCapsMode.UNSHIFTED,
            InputShiftState.SHIFTED_MANUAL to AutocorrectCapsMode.SHIFTED_MANUAL,
            InputShiftState.SHIFTED_AUTOMATIC to AutocorrectCapsMode.SHIFTED_AUTOMATIC,
            InputShiftState.CAPS_LOCK to AutocorrectCapsMode.CAPS_LOCK,
        )
        expected.keys shouldBe InputShiftState.entries.toSet()
        expected.forEach { (shift, capsMode) ->
            state.inputShiftState = shift
            currentTraits().capsMode shouldBe capsMode
        }
    }
})
