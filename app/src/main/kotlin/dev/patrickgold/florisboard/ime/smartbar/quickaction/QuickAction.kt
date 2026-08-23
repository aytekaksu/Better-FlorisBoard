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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R.string as S
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.florisboard.lib.compose.stringRes

@Serializable
sealed class QuickAction {
    open fun onPointerDown(context: Context) = Unit

    open fun onPointerUp(context: Context) = Unit

    open fun onPointerCancel(context: Context) = Unit

    @Serializable
    @SerialName("insert_key")
    data class InsertKey(val data: KeyData) : QuickAction() {
        override fun onPointerDown(context: Context) {
            val keyboardManager by context.keyboardManager()
            keyboardManager.inputEventDispatcher.sendDown(data)
        }

        override fun onPointerUp(context: Context) {
            val keyboardManager by context.keyboardManager()
            keyboardManager.inputEventDispatcher.sendUp(data)
            if (!keyboardManager.inputEventDispatcher.isRepeatable(data) &&
                data.code != KeyCode.TOGGLE_ACTIONS_OVERFLOW && data.code != KeyCode.CLIPBOARD_SELECT_ALL
            ) {
                keyboardManager.activeState.isActionsOverflowVisible = false
            }
        }

        override fun onPointerCancel(context: Context) {
            val keyboardManager by context.keyboardManager()
            keyboardManager.inputEventDispatcher.sendCancel(data)
        }
    }

    @Serializable
    @SerialName("insert_text")
    data class InsertText(val data: String) : QuickAction() {
        override fun onPointerUp(context: Context) {
            val keyboardManager by context.keyboardManager()
            keyboardManager.inputEventDispatcher.dispatchInputEvent {
                val editorInstance by context.editorInstance()
                editorInstance.commitText(data)
            }
        }
    }
}

fun QuickAction.keyData(): KeyData = if (this is QuickAction.InsertKey) data else TextKeyData.UNSPECIFIED

internal data class QuickActionStringResources(
    @param:StringRes val displayName: Int,
    @param:StringRes val tooltip: Int,
)

private fun res(@StringRes displayName: Int, @StringRes tooltip: Int) =
    QuickActionStringResources(displayName, tooltip)

internal fun quickActionStringResources(code: Int, showDragAndDropHelpers: Boolean): QuickActionStringResources =
    when (code) {
        KeyCode.ARROW_UP -> res(S.quick_action__arrow_up, S.quick_action__arrow_up__tooltip)
        KeyCode.ARROW_DOWN -> res(S.quick_action__arrow_down, S.quick_action__arrow_down__tooltip)
        KeyCode.ARROW_LEFT -> res(S.quick_action__arrow_left, S.quick_action__arrow_left__tooltip)
        KeyCode.ARROW_RIGHT -> res(S.quick_action__arrow_right, S.quick_action__arrow_right__tooltip)
        KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP ->
            res(S.quick_action__clipboard_clear_primary_clip, S.quick_action__clipboard_clear_primary_clip__tooltip)
        KeyCode.CLIPBOARD_COPY -> res(S.quick_action__clipboard_copy, S.quick_action__clipboard_copy__tooltip)
        KeyCode.CLIPBOARD_CUT -> res(S.quick_action__clipboard_cut, S.quick_action__clipboard_cut__tooltip)
        KeyCode.CLIPBOARD_PASTE -> res(S.quick_action__clipboard_paste, S.quick_action__clipboard_paste__tooltip)
        KeyCode.CLIPBOARD_SELECT_ALL ->
            res(S.quick_action__clipboard_select_all, S.quick_action__clipboard_select_all__tooltip)
        KeyCode.FORWARD_DELETE -> res(S.quick_action__forward_delete, S.quick_action__forward_delete__tooltip)
        KeyCode.IME_UI_MODE_CLIPBOARD ->
            res(S.quick_action__ime_ui_mode_clipboard, S.quick_action__ime_ui_mode_clipboard__tooltip)
        KeyCode.IME_UI_MODE_MEDIA -> res(S.quick_action__ime_ui_mode_media, S.quick_action__ime_ui_mode_media__tooltip)
        KeyCode.LANGUAGE_SWITCH -> res(S.quick_action__language_switch, S.quick_action__language_switch__tooltip)
        KeyCode.SETTINGS -> res(S.quick_action__settings, S.quick_action__settings__tooltip)
        KeyCode.UNDO -> res(S.quick_action__undo, S.quick_action__undo__tooltip)
        KeyCode.REDO -> res(S.quick_action__redo, S.quick_action__redo__tooltip)
        KeyCode.TOGGLE_ACTIONS_OVERFLOW ->
            res(S.quick_action__toggle_actions_overflow, S.quick_action__toggle_actions_overflow__tooltip)
        KeyCode.TOGGLE_INCOGNITO_MODE ->
            res(S.quick_action__toggle_incognito_mode, S.quick_action__toggle_incognito_mode__tooltip)
        KeyCode.TOGGLE_AUTOCORRECT, KeyCode.AUTOCORRECT_PLUGIN_UI ->
            res(S.quick_action__toggle_autocorrect, S.quick_action__autocorrect_plugin__tooltip)
        KeyCode.VOICE_INPUT -> res(S.quick_action__voice_input, S.quick_action__voice_input__tooltip)
        KeyCode.IME_HIDE_UI -> res(S.quick_action__ime_hide_ui, S.quick_action__ime_hide_ui__tooltip)
        KeyCode.TOGGLE_FLOATING_WINDOW ->
            res(S.quick_action__floating_window_mode, S.quick_action__floating_window_mode__tooltip)
        // Compact layout remains separate until the resize panel can own it.
        KeyCode.TOGGLE_COMPACT_LAYOUT -> res(S.quick_action__one_handed_mode, S.quick_action__one_handed_mode__tooltip)
        KeyCode.TOGGLE_RESIZE_MODE -> res(S.quick_action__resize_mode, S.quick_action__resize_mode__tooltip)
        KeyCode.DRAG_MARKER -> if (showDragAndDropHelpers) {
            res(S.quick_action__drag_marker, S.quick_action__drag_marker__tooltip)
        } else {
            res(S.general__empty_string, S.general__empty_string)
        }
        KeyCode.NOOP -> res(S.quick_action__noop, S.quick_action__noop__tooltip)
        else -> res(S.general__invalid_fatal, S.general__invalid_fatal)
    }

@Composable
fun QuickAction.computeDisplayName(evaluator: ComputingEvaluator): String = when (this) {
    is QuickAction.InsertKey -> stringRes(
        quickActionStringResources(data.code, evaluator.state.debugShowDragAndDropHelpers).displayName,
    )
    is QuickAction.InsertText -> data
}

@Composable
fun QuickAction.computeTooltip(evaluator: ComputingEvaluator): String = when (this) {
    is QuickAction.InsertKey -> stringRes(
        quickActionStringResources(data.code, evaluator.state.debugShowDragAndDropHelpers).tooltip,
    )
    is QuickAction.InsertText -> stringRes(S.quick_action__insert_text__tooltip, "text" to data)
}

internal fun QuickAction.InsertText.previewText(): String {
    if (data.isEmpty()) return "?"
    return String(Character.toChars(data.codePointAt(0))).ifBlank { "?" }
}
