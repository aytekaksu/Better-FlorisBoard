/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
