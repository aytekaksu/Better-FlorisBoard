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
import org.florisboard.autocorrect.api.AutocorrectCapsMode

/** Sample again for each hint lease or wire request; shift and privacy can change mid-session. */
internal data class AutocorrectKeyboardTraits(
    val isPrivateSession: Boolean,
    val capsMode: AutocorrectCapsMode,
)

internal fun liveAutocorrectKeyboardTraits(
    keyboardState: () -> KeyboardState,
): () -> AutocorrectKeyboardTraits = {
    val state = keyboardState().snapshot()
    AutocorrectKeyboardTraits(
        isPrivateSession = state.isIncognitoMode,
        capsMode = when (state.inputShiftState) {
            InputShiftState.UNSHIFTED -> AutocorrectCapsMode.UNSHIFTED
            InputShiftState.SHIFTED_MANUAL -> AutocorrectCapsMode.SHIFTED_MANUAL
            InputShiftState.SHIFTED_AUTOMATIC -> AutocorrectCapsMode.SHIFTED_AUTOMATIC
            InputShiftState.CAPS_LOCK -> AutocorrectCapsMode.CAPS_LOCK
        },
    )
}
