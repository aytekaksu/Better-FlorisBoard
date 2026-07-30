/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.editor

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Class which holds the same information as an [EditorInfo.inputType] int but more accessible and
 * readable.
 *
 * @see EditorInfo.inputType for mask table
 */
@JvmInline
value class InputAttributes private constructor(val raw: Int) {
    val type: Type
        get() = Type.fromInt(raw and InputType.TYPE_MASK_CLASS)

    val variation: Variation
        get() = when (type) {
            Type.DATETIME -> when (raw and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> Variation.DATE
                InputType.TYPE_DATETIME_VARIATION_NORMAL -> Variation.NORMAL
                InputType.TYPE_DATETIME_VARIATION_TIME -> Variation.TIME
                else -> Variation.NORMAL
            }
            Type.NUMBER -> when (raw and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_NUMBER_VARIATION_NORMAL -> Variation.NORMAL
                InputType.TYPE_NUMBER_VARIATION_PASSWORD -> Variation.PASSWORD
                else -> Variation.NORMAL
            }
            Type.TEXT -> when (raw and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> Variation.EMAIL_ADDRESS
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> Variation.EMAIL_SUBJECT
                InputType.TYPE_TEXT_VARIATION_FILTER -> Variation.FILTER
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> Variation.LONG_MESSAGE
                InputType.TYPE_TEXT_VARIATION_NORMAL -> Variation.NORMAL
                InputType.TYPE_TEXT_VARIATION_PASSWORD -> Variation.PASSWORD
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> Variation.PERSON_NAME
                InputType.TYPE_TEXT_VARIATION_PHONETIC -> Variation.PHONETIC
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> Variation.POSTAL_ADDRESS
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> Variation.SHORT_MESSAGE
                InputType.TYPE_TEXT_VARIATION_URI -> Variation.URI
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> Variation.VISIBLE_PASSWORD
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> Variation.WEB_EDIT_TEXT
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> Variation.WEB_EMAIL_ADDRESS
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> Variation.WEB_PASSWORD
                else -> Variation.NORMAL
            }
            else -> Variation.NORMAL
        }

    val isPassword: Boolean
        get() = when (variation) {
            Variation.PASSWORD,
            Variation.VISIBLE_PASSWORD,
            Variation.WEB_PASSWORD -> true
            else -> false
        }

    val flagTextMultiLine: Boolean
        get() = type == Type.TEXT && (raw and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)

    companion object {
        fun wrap(inputType: Int) = InputAttributes(inputType)
    }

    enum class Type(private val value: Int) {
        NULL(EditorInfo.TYPE_NULL),
        DATETIME(EditorInfo.TYPE_CLASS_DATETIME),
        NUMBER(EditorInfo.TYPE_CLASS_NUMBER),
        PHONE(EditorInfo.TYPE_CLASS_PHONE),
        TEXT(EditorInfo.TYPE_CLASS_TEXT);

        companion object {
            fun fromInt(int: Int) = entries.firstOrNull { it.value == int } ?: NULL
        }
    }

    enum class Variation {
        NORMAL,
        DATE,
        EMAIL_ADDRESS,
        EMAIL_SUBJECT,
        FILTER,
        LONG_MESSAGE,
        PASSWORD,
        PERSON_NAME,
        PHONETIC,
        POSTAL_ADDRESS,
        SHORT_MESSAGE,
        TIME,
        URI,
        VISIBLE_PASSWORD,
        WEB_EDIT_TEXT,
        WEB_EMAIL_ADDRESS,
        WEB_PASSWORD,
    }

    enum class CapsMode(private val value: Int) {
        NONE(0),
        ALL(1),
        WORDS(2),
        SENTENCES(3);

        companion object {
            fun fromFlags(flags: Int): CapsMode {
                return when {
                    flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> ALL
                    flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 -> WORDS
                    flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 -> SENTENCES
                    else -> NONE
                }
            }
        }

        fun toInt() = value
    }
}
