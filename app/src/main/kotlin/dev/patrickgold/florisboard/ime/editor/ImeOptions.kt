/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.editor

import android.view.inputmethod.EditorInfo

/**
 * Class which holds the same information as an [EditorInfo.imeOptions] int but more accessible and
 * readable.
 *
 * @see EditorInfo.imeOptions for mask table
 */
@JvmInline
value class ImeOptions private constructor(val raw: Int) {
    val action: Action
        get() = Action.fromInt(raw and EditorInfo.IME_MASK_ACTION)

    val flagNoEnterAction: Boolean
        get() = raw and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

    val flagNoPersonalizedLearning: Boolean
        get() = raw and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    companion object {
        fun wrap(imeOptions: Int) = ImeOptions(imeOptions)
    }

    enum class Action(private val value: Int) {
        UNSPECIFIED(EditorInfo.IME_ACTION_UNSPECIFIED),
        DONE(EditorInfo.IME_ACTION_DONE),
        GO(EditorInfo.IME_ACTION_GO),
        NEXT(EditorInfo.IME_ACTION_NEXT),
        NONE(EditorInfo.IME_ACTION_NONE),
        PREVIOUS(EditorInfo.IME_ACTION_PREVIOUS),
        SEARCH(EditorInfo.IME_ACTION_SEARCH),
        SEND(EditorInfo.IME_ACTION_SEND);

        companion object {
            fun fromInt(int: Int) = entries.firstOrNull { it.value == int } ?: NONE
        }

        fun toInt() = value
    }
}
