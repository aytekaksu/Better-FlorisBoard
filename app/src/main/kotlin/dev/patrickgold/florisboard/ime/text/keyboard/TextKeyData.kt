/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.text.keyboard

import android.icu.lang.UCharacter
import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.contentFreeString
import dev.patrickgold.florisboard.ime.popup.PopupSet
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.Unicode
import dev.patrickgold.florisboard.lib.lowercase
import dev.patrickgold.florisboard.lib.uppercase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Data class which describes a single key and its attributes.
 *
 * @property type The type of the key. Some actions require both [code] and [type] to match in order
 *  to be successfully executed. Defaults to [KeyType.CHARACTER].
 * @property code The UTF-8 encoded code of the character. The code defined here is used as the
 *  data passed to the system. Defaults to 0.
 * @property label The string used to display the key in the UI. Is not used for the actual data
 *  passed to the system. Should normally be the exact same as the [code]. Defaults to an empty
 *  string.
 */
@Serializable
@SerialName("text_key")
data class TextKeyData(
    override val type: KeyType = KeyType.CHARACTER,
    override val code: Int = KeyCode.UNSPECIFIED,
    override val label: String = "",
    override val groupId: Int = KeyData.GROUP_DEFAULT,
    override val popup: PopupSet<AbstractKeyData>? = null
) : KeyData {
    override fun compute(evaluator: ComputingEvaluator): KeyData? {
        return if (evaluator.isSlot(this)) {
            evaluator.slotData(this)?.let { data ->
                TextKeyData(type, data.code, data.label, groupId, popup)
            }
        } else {
            this
        }
    }

    override fun asString(isForDisplay: Boolean): String {
        return asString(this, isForDisplay)
    }

    override fun toString(): String {
        return contentFreeString()
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        private val internalKeys = mutableMapOf<Int, TextKeyData>()

        fun getCodeInfoAsTextKeyData(code: Int): TextKeyData? {
            return if (code <= 0) {
                internalKeys[code]
            } else {
                TextKeyData(
                    type = KeyType.CHARACTER,
                    code = code,
                    label = buildString {
                        try {
                            appendCodePoint(code)
                        } catch (_: Throwable) {
                        }
                    },
                )
            }
        }

        private fun predefinedKey(
            type: KeyType,
            code: Int,
            label: String,
            groupId: Int = KeyData.GROUP_DEFAULT,
            popup: PopupSet<AbstractKeyData>? = null,
            includeInLookup: Boolean = true,
        ): TextKeyData {
            val key = TextKeyData(type, code, label, groupId, popup)
            if (includeInLookup) {
                check(internalKeys.put(code, key) == null) { "Duplicate predefined key code" }
            }
            return key
        }

        val UNSPECIFIED = predefinedKey(KeyType.UNSPECIFIED, KeyCode.UNSPECIFIED, "unspecified")
        val SPACE = predefinedKey(KeyType.CHARACTER, KeyCode.SPACE, "space")
        val CTRL = predefinedKey(KeyType.MODIFIER, KeyCode.CTRL, "ctrl")
        val CTRL_LOCK = predefinedKey(KeyType.MODIFIER, KeyCode.CTRL_LOCK, "ctrl_lock")
        val ALT = predefinedKey(KeyType.MODIFIER, KeyCode.ALT, "alt")
        val ALT_LOCK = predefinedKey(KeyType.MODIFIER, KeyCode.ALT_LOCK, "alt_lock")
        val FN = predefinedKey(KeyType.MODIFIER, KeyCode.FN, "fn")
        val FN_LOCK = predefinedKey(KeyType.MODIFIER, KeyCode.FN_LOCK, "fn_lock")
        val DELETE = predefinedKey(KeyType.ENTER_EDITING, KeyCode.DELETE, "delete")
        val DELETE_WORD = predefinedKey(KeyType.ENTER_EDITING, KeyCode.DELETE_WORD, "delete_word")
        val FORWARD_DELETE = predefinedKey(KeyType.ENTER_EDITING, KeyCode.FORWARD_DELETE, "forward_delete")
        val FORWARD_DELETE_WORD =
            predefinedKey(KeyType.ENTER_EDITING, KeyCode.FORWARD_DELETE_WORD, "forward_delete_word")
        val SHIFT = predefinedKey(KeyType.MODIFIER, KeyCode.SHIFT, "shift")
        val CAPS_LOCK = predefinedKey(KeyType.MODIFIER, KeyCode.CAPS_LOCK, "caps_lock")

        val ARROW_LEFT = predefinedKey(KeyType.NAVIGATION, KeyCode.ARROW_LEFT, "arrow_left")
        val ARROW_RIGHT = predefinedKey(KeyType.NAVIGATION, KeyCode.ARROW_RIGHT, "arrow_right")
        val ARROW_UP = predefinedKey(KeyType.NAVIGATION, KeyCode.ARROW_UP, "arrow_up")
        val ARROW_DOWN = predefinedKey(KeyType.NAVIGATION, KeyCode.ARROW_DOWN, "arrow_down")
        val MOVE_START_OF_PAGE =
            predefinedKey(KeyType.NAVIGATION, KeyCode.MOVE_START_OF_PAGE, "move_start_of_page")
        val MOVE_END_OF_PAGE =
            predefinedKey(KeyType.NAVIGATION, KeyCode.MOVE_END_OF_PAGE, "move_end_of_page")
        val MOVE_START_OF_LINE =
            predefinedKey(KeyType.NAVIGATION, KeyCode.MOVE_START_OF_LINE, "move_start_of_line")
        val MOVE_END_OF_LINE =
            predefinedKey(KeyType.NAVIGATION, KeyCode.MOVE_END_OF_LINE, "move_end_of_line")

        val CLIPBOARD_COPY = predefinedKey(KeyType.SYSTEM_GUI, KeyCode.CLIPBOARD_COPY, "clipboard_copy")
        val CLIPBOARD_CUT = predefinedKey(KeyType.SYSTEM_GUI, KeyCode.CLIPBOARD_CUT, "clipboard_cut")
        val CLIPBOARD_PASTE = predefinedKey(KeyType.SYSTEM_GUI, KeyCode.CLIPBOARD_PASTE, "clipboard_paste")
        val CLIPBOARD_SELECT =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.CLIPBOARD_SELECT, "clipboard_select")
        val CLIPBOARD_SELECT_ALL =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.CLIPBOARD_SELECT_ALL, "clipboard_select_all")
        val CLIPBOARD_CLEAR_HISTORY =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.CLIPBOARD_CLEAR_HISTORY, "clipboard_clear_history")
        val CLIPBOARD_CLEAR_FULL_HISTORY =
            predefinedKey(
                KeyType.SYSTEM_GUI,
                KeyCode.CLIPBOARD_CLEAR_FULL_HISTORY,
                "clipboard_clear_full_history",
            )
        val CLIPBOARD_CLEAR_PRIMARY_CLIP =
            predefinedKey(
                KeyType.SYSTEM_GUI,
                KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP,
                "clipboard_clear_primary_clip",
            )

        val TOGGLE_FLOATING_WINDOW =
            predefinedKey(
                KeyType.SYSTEM_GUI,
                KeyCode.TOGGLE_FLOATING_WINDOW,
                "toggle_floating_window",
                includeInLookup = false,
            )
        val TOGGLE_COMPACT_LAYOUT =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.TOGGLE_COMPACT_LAYOUT, "toggle_compact_layout")
        val COMPACT_LAYOUT_TO_LEFT =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.COMPACT_LAYOUT_TO_LEFT, "compact_layout_to_left")
        val COMPACT_LAYOUT_TO_RIGHT =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.COMPACT_LAYOUT_TO_RIGHT, "compact_layout_to_right")
        val TOGGLE_RESIZE_MODE =
            predefinedKey(
                KeyType.SYSTEM_GUI,
                KeyCode.TOGGLE_RESIZE_MODE,
                "toggle_resize_mode",
                includeInLookup = false,
            )

        val UNDO = predefinedKey(KeyType.SYSTEM_GUI, KeyCode.UNDO, "undo")
        val REDO = predefinedKey(KeyType.SYSTEM_GUI, KeyCode.REDO, "redo")

        val VIEW_CHARACTERS =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.VIEW_CHARACTERS, "view_characters")
        val VIEW_SYMBOLS = predefinedKey(KeyType.SYSTEM_GUI, KeyCode.VIEW_SYMBOLS, "view_symbols")
        val VIEW_SYMBOLS2 = predefinedKey(KeyType.SYSTEM_GUI, KeyCode.VIEW_SYMBOLS2, "view_symbols2")
        val VIEW_NUMERIC_ADVANCED =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.VIEW_NUMERIC_ADVANCED, "view_numeric_advanced")

        val IME_UI_MODE_TEXT =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.IME_UI_MODE_TEXT, "ime_ui_mode_text")
        val IME_UI_MODE_MEDIA =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.IME_UI_MODE_MEDIA, "ime_ui_mode_media")
        val IME_UI_MODE_CLIPBOARD =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.IME_UI_MODE_CLIPBOARD, "ime_ui_mode_clipboard")

        val SYSTEM_INPUT_METHOD_PICKER =
            predefinedKey(
                KeyType.FUNCTION,
                KeyCode.SYSTEM_INPUT_METHOD_PICKER,
                "system_input_method_picker",
            )
        val SHOW_SUBTYPE_PICKER =
            predefinedKey(
                KeyType.FUNCTION,
                KeyCode.SHOW_SUBTYPE_PICKER,
                "subtype_picker",
                includeInLookup = false,
            )
        val SYSTEM_PREV_INPUT_METHOD =
            predefinedKey(
                KeyType.FUNCTION,
                KeyCode.SYSTEM_PREV_INPUT_METHOD,
                "system_prev_input_method",
            )
        val SYSTEM_NEXT_INPUT_METHOD =
            predefinedKey(
                KeyType.FUNCTION,
                KeyCode.SYSTEM_NEXT_INPUT_METHOD,
                "system_next_input_method",
            )
        val IME_SUBTYPE_PICKER =
            predefinedKey(KeyType.FUNCTION, KeyCode.IME_SUBTYPE_PICKER, "ime_subtype_picker")
        val IME_PREV_SUBTYPE =
            predefinedKey(KeyType.FUNCTION, KeyCode.IME_PREV_SUBTYPE, "ime_prev_subtype")
        val IME_NEXT_SUBTYPE =
            predefinedKey(KeyType.FUNCTION, KeyCode.IME_NEXT_SUBTYPE, "ime_next_subtype")
        val LANGUAGE_SWITCH =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.LANGUAGE_SWITCH, "language_switch")

        val IME_SHOW_UI = predefinedKey(KeyType.FUNCTION, KeyCode.IME_SHOW_UI, "ime_show_ui")
        val IME_HIDE_UI = predefinedKey(KeyType.FUNCTION, KeyCode.IME_HIDE_UI, "ime_hide_ui")

        val SETTINGS = predefinedKey(KeyType.CHARACTER, KeyCode.SETTINGS, "settings")
        val VOICE_INPUT = predefinedKey(KeyType.UNSPECIFIED, KeyCode.VOICE_INPUT, "voice_input")

        val TOGGLE_SMARTBAR_VISIBILITY =
            predefinedKey(
                KeyType.SYSTEM_GUI,
                KeyCode.TOGGLE_SMARTBAR_VISIBILITY,
                "toggle_smartbar_visibility",
            )
        val TOGGLE_ACTIONS_OVERFLOW =
            predefinedKey(
                KeyType.SYSTEM_GUI,
                KeyCode.TOGGLE_ACTIONS_OVERFLOW,
                "toggle_actions_overflow",
            )
        val TOGGLE_ACTIONS_EDITOR =
            predefinedKey(KeyType.SYSTEM_GUI, KeyCode.TOGGLE_ACTIONS_EDITOR, "toggle_actions_editor")
        val TOGGLE_INCOGNITO_MODE =
            predefinedKey(KeyType.FUNCTION, KeyCode.TOGGLE_INCOGNITO_MODE, "toggle_incognito_mode")
        val TOGGLE_AUTOCORRECT =
            predefinedKey(KeyType.FUNCTION, KeyCode.TOGGLE_AUTOCORRECT, "toggle_autocorrect")
        val AUTOCORRECT_PLUGIN_UI =
            predefinedKey(
                KeyType.SYSTEM_GUI,
                KeyCode.AUTOCORRECT_PLUGIN_UI,
                "autocorrect_plugin_ui",
            )
    }
}

@Serializable
@SerialName("auto_text_key")
class AutoTextKeyData(
    override val type: KeyType = KeyType.CHARACTER,
    override val code: Int = KeyCode.UNSPECIFIED,
    override val label: String = "",
    override val groupId: Int = KeyData.GROUP_DEFAULT,
    override val popup: PopupSet<AbstractKeyData>? = null
) : KeyData {
    @Transient private val state = AutoLetterState()

    override fun compute(evaluator: ComputingEvaluator): KeyData? {
        return if (evaluator.isSlot(this)) {
            evaluator.slotData(this)?.let { data ->
                TextKeyData(type, data.code, data.label, groupId, popup)
            }
        } else {
            state.recomputeIfNecessary(evaluator.subtype.primaryLocale)
            if (evaluator.state.isUppercase) { state.upper } else { state.lower }
        }
    }

    override fun asString(isForDisplay: Boolean): String {
        return asString(this, isForDisplay)
    }

    override fun toString(): String {
        return contentFreeString()
    }

    private inner class AutoLetterState {
        private var locale: FlorisLocale = FlorisLocale.ROOT
        var lower: TextKeyData = TextKeyData.UNSPECIFIED
            private set
        var upper: TextKeyData = TextKeyData.UNSPECIFIED
            private set

        fun recomputeIfNecessary(locale: FlorisLocale) {
            if (this.locale == locale) return
            this.locale = locale
            lower = TextKeyData(
                type,
                UCharacter.toString(code).lowercase(locale).codePointAt(0),
                label.lowercase(locale),
                groupId,
                popup,
            )
            upper = TextKeyData(
                type,
                UCharacter.toString(code).uppercase(locale).codePointAt(0),
                label.uppercase(locale),
                groupId,
                popup,
            )
        }
    }
}

@Serializable
@SerialName("multi_text_key")
class MultiTextKeyData(
    override val type: KeyType = KeyType.CHARACTER,
    val codePoints: IntArray = intArrayOf(),
    override val label: String = "",
    override val groupId: Int = KeyData.GROUP_DEFAULT,
    override val popup: PopupSet<AbstractKeyData>? = null
) : KeyData {
    @Transient override val code: Int = KeyCode.MULTIPLE_CODE_POINTS

    override fun compute(evaluator: ComputingEvaluator): KeyData {
        return this
    }

    override fun asString(isForDisplay: Boolean): String {
        return buildString {
            if (isForDisplay) {
                append(label)
            } else {
                for (codePoint in codePoints) {
                    try { appendCodePoint(codePoint) } catch (_: Throwable) { }
                }
            }
        }
    }

    override fun toString(): String {
        return contentFreeString()
    }
}

internal fun asString(data: KeyData, isForDisplay: Boolean) : String {
    return buildString {
        if (isForDisplay || data.code == KeyCode.URI_COMPONENT_TLD || data.code < KeyCode.SPACE) {
            if (data.code > 0 && Unicode.isNonSpacingMark(data.code) && !data.label.startsWith("◌")) {
                append("◌")
            }
            append(data.label)
        } else {
            try { appendCodePoint(data.code) } catch (_: Throwable) { }
        }
    }
}
