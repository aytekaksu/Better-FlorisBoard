/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib

import android.icu.lang.UCharacter
import android.icu.lang.UCharacterCategory

/**
 * Character codes and comments source:
 *  https://www.w3.org/International/questions/qa-bidi-unicode-controls#basedirection
 */
object UnicodeCtrlChar {
    /** Sets base direction to LTR and isolates the embedded content from the surrounding text */
    const val LeftToRightIsolate = "\u2066"

    /** Closes a previously opened isolated text block */
    const val PopDirectionalIsolate = "\u2069"
}

object Unicode {
    fun isNonSpacingMark(code: Int): Boolean {
        return UCharacter.getType(code).toByte() == UCharacterCategory.NON_SPACING_MARK
    }
}
