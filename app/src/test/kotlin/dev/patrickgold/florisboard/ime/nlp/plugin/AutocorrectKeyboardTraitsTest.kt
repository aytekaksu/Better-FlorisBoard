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
