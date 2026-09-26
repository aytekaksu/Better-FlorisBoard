/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.app

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.theme.DisplayKbdAfterDialogs
import dev.patrickgold.florisboard.app.settings.theme.SnyggLevel
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSyncBehavior
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.ime.input.CapitalizationBehavior
import dev.patrickgold.florisboard.ime.input.HapticVibrationMode
import dev.patrickgold.florisboard.ime.input.InputFeedbackActivationMode
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarLanguageLabelMode
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarMode
import dev.patrickgold.florisboard.ime.landscapeinput.LandscapeInputUiMode
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistory
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.ime.smartbar.CandidatesDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.ExtendedActionsPlacement
import dev.patrickgold.florisboard.ime.smartbar.IncognitoDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.SharedActionsTransitionMode
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.ime.smartbar.SmartbarMotionMode
import dev.patrickgold.florisboard.ime.text.gestures.SwipeActivationArea
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyHintMode
import dev.patrickgold.florisboard.ime.text.key.KeyHintPlacement
import dev.patrickgold.florisboard.ime.text.key.UtilityKeyAction
import dev.patrickgold.florisboard.ime.theme.ThemeMode
import dev.patrickgold.florisboard.ime.window.ImeWindowMode
import dev.patrickgold.florisboard.ime.window.KeyboardContentScaleMode
import dev.patrickgold.jetpref.datastore.ui.ListPreferenceEntriesScope
import dev.patrickgold.jetpref.datastore.ui.ListPreferenceEntry
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import dev.patrickgold.jetpref.material.ui.ColorRepresentation
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.curlyFormat
import kotlin.reflect.KClass

private const val DEFAULT = ""

@Composable
private fun <V : Any> ListPreferenceEntriesScope<V>.ResourceEntry(
    key: V,
    @StringRes labelRes: Int,
) = entry(key = key, label = stringRes(labelRes))

private fun <V : Any> resourceEntries(vararg entries: Pair<V, Int>): @Composable () -> List<ListPreferenceEntry<V>> = {
    listPrefEntries {
        for ((key, labelRes) in entries) {
            ResourceEntry(key, labelRes)
        }
    }
}

private fun <V : Any> describedResourceEntries(
    vararg entries: Triple<V, Int, Int>,
    showDescriptionOnlyIfSelected: Boolean = false,
): @Composable () -> List<ListPreferenceEntry<V>> = {
    listPrefEntries {
        for ((key, labelRes, descriptionRes) in entries) {
            entry(
                key = key,
                label = stringRes(labelRes),
                description = stringRes(descriptionRes),
                showDescriptionOnlyIfSelected = showDescriptionOnlyIfSelected,
            )
        }
    }
}

private val ENUM_DISPLAY_ENTRIES = mapOf<Pair<KClass<*>, String>, @Composable () -> List<ListPreferenceEntry<*>>>(
    AppTheme::class to DEFAULT to resourceEntries(
        AppTheme.AUTO to R.string.settings__system_default,
        AppTheme.AUTO_AMOLED to R.string.pref__other__settings_theme__auto_amoled,
        AppTheme.LIGHT to R.string.pref__other__settings_theme__light,
        AppTheme.DARK to R.string.pref__other__settings_theme__dark,
        AppTheme.AMOLED_DARK to R.string.pref__other__settings_theme__amoled_dark,
    ),
    CandidatesDisplayMode::class to DEFAULT to resourceEntries(
        CandidatesDisplayMode.CLASSIC to R.string.enum__candidates_display_mode__classic,
        CandidatesDisplayMode.DYNAMIC to R.string.enum__candidates_display_mode__dynamic,
        CandidatesDisplayMode.DYNAMIC_SCROLLABLE to R.string.enum__candidates_display_mode__dynamic_scrollable,
    ),
    CapitalizationBehavior::class to DEFAULT to resourceEntries(
        CapitalizationBehavior.CAPSLOCK_BY_DOUBLE_TAP to R.string.enum__capitalization_behavior__capslock_by_double_tap,
        CapitalizationBehavior.CAPSLOCK_BY_CYCLE to R.string.enum__capitalization_behavior__capslock_by_cycle,
    ),
    ClipboardSyncBehavior::class to DEFAULT to describedResourceEntries(
        Triple(ClipboardSyncBehavior.NO_EVENTS, R.string.enum__clipboard_sync_behavior__no_events,
            R.string.enum__clipboard_sync_behavior__no_events__description),
        Triple(ClipboardSyncBehavior.ONLY_CLEAR_EVENTS, R.string.enum__clipboard_sync_behavior__only_clear_events,
            R.string.enum__clipboard_sync_behavior__only_clear_events__description),
        Triple(ClipboardSyncBehavior.ONLY_SET_EVENTS, R.string.enum__clipboard_sync_behavior__only_set_events,
            R.string.enum__clipboard_sync_behavior__only_set_events__description),
        Triple(ClipboardSyncBehavior.ALL_EVENTS, R.string.enum__clipboard_sync_behavior__all_events,
            R.string.enum__clipboard_sync_behavior__all_events__description),
    ),
    ColorRepresentation::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = ColorRepresentation.HEX,
                label = stringRes(R.string.enum__color_representation__hex),
                description = stringRes(R.string.general__example_given).curlyFormat("example" to "#4caf50ff"),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = ColorRepresentation.RGB,
                label = stringRes(R.string.enum__color_representation__rgb),
                description = stringRes(R.string.general__example_given).curlyFormat("example" to "rgba(76, 175, 80, 1.0)"),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = ColorRepresentation.HSV,
                label = stringRes(R.string.enum__color_representation__hsv),
                description = stringRes(R.string.general__example_given).curlyFormat("example" to "hsva(122, 56, 68, 1.0)"),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    DisplayKbdAfterDialogs::class to DEFAULT to describedResourceEntries(
        Triple(DisplayKbdAfterDialogs.ALWAYS, R.string.enum__display_kbd_after_dialogs__always,
            R.string.enum__display_kbd_after_dialogs__always__description),
        Triple(DisplayKbdAfterDialogs.NEVER, R.string.enum__display_kbd_after_dialogs__never,
            R.string.enum__display_kbd_after_dialogs__never__description),
        Triple(DisplayKbdAfterDialogs.REMEMBER, R.string.enum__display_kbd_after_dialogs__remember,
            R.string.enum__display_kbd_after_dialogs__remember__description),
        showDescriptionOnlyIfSelected = true,
    ),
    DisplayLanguageNamesIn::class to DEFAULT to describedResourceEntries(
        Triple(DisplayLanguageNamesIn.SYSTEM_LOCALE, R.string.enum__display_language_names_in__system_locale,
            R.string.enum__display_language_names_in__system_locale__description),
        Triple(DisplayLanguageNamesIn.NATIVE_LOCALE, R.string.enum__display_language_names_in__native_locale,
            R.string.enum__display_language_names_in__native_locale__description),
        showDescriptionOnlyIfSelected = true,
    ),
    EmojiHistory.UpdateStrategy::class to DEFAULT to describedResourceEntries(
        Triple(EmojiHistory.UpdateStrategy.AUTO_SORT_PREPEND,
            R.string.enum__emoji_history_update_strategy__auto_sort_prepend,
            R.string.enum__emoji_history_update_strategy__auto_sort_prepend__description),
        Triple(EmojiHistory.UpdateStrategy.AUTO_SORT_APPEND,
            R.string.enum__emoji_history_update_strategy__auto_sort_append,
            R.string.enum__emoji_history_update_strategy__auto_sort_append__description),
        Triple(EmojiHistory.UpdateStrategy.MANUAL_SORT_PREPEND,
            R.string.enum__emoji_history_update_strategy__manual_sort_prepend,
            R.string.enum__emoji_history_update_strategy__manual_sort_prepend__description),
        Triple(EmojiHistory.UpdateStrategy.MANUAL_SORT_APPEND,
            R.string.enum__emoji_history_update_strategy__manual_sort_append,
            R.string.enum__emoji_history_update_strategy__manual_sort_append__description),
    ),
    EmojiSkinTone::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = EmojiSkinTone.DEFAULT,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__default,
                    "emoji" to "\uD83D\uDC4B" // 👋
                ),
            )
            entry(
                key = EmojiSkinTone.LIGHT_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__light_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFB" // 👋🏻
                ),
            )
            entry(
                key = EmojiSkinTone.MEDIUM_LIGHT_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__medium_light_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFC" // 👋🏼
                ),
            )
            entry(
                key = EmojiSkinTone.MEDIUM_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__medium_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFD" // 👋🏽
                ),
            )
            entry(
                key = EmojiSkinTone.MEDIUM_DARK_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__medium_dark_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFE" // 👋🏾
                ),
            )
            entry(
                key = EmojiSkinTone.DARK_SKIN_TONE,
                label = stringRes(
                    R.string.enum__emoji_skin_tone__dark_skin_tone,
                    "emoji" to "\uD83D\uDC4B\uD83C\uDFFF" // 👋🏿
                ),
            )
        }
    },
    EmojiSuggestionType::class to DEFAULT to describedResourceEntries(
        Triple(EmojiSuggestionType.LEADING_COLON, R.string.enum__emoji_suggestion_type__leading_colon,
            R.string.enum__emoji_suggestion_type__leading_colon__description),
        Triple(EmojiSuggestionType.INLINE_TEXT, R.string.enum__emoji_suggestion_type__inline_text,
            R.string.enum__emoji_suggestion_type__inline_text__description),
    ),
    ExtendedActionsPlacement::class to DEFAULT to describedResourceEntries(
        Triple(ExtendedActionsPlacement.ABOVE_CANDIDATES, R.string.enum__extended_actions_placement__above_candidates,
            R.string.enum__extended_actions_placement__above_candidates__description),
        Triple(ExtendedActionsPlacement.BELOW_CANDIDATES, R.string.enum__extended_actions_placement__below_candidates,
            R.string.enum__extended_actions_placement__below_candidates__description),
        Triple(ExtendedActionsPlacement.OVERLAY_APP_UI, R.string.enum__extended_actions_placement__overlay_app_ui,
            R.string.enum__extended_actions_placement__overlay_app_ui__description),
        showDescriptionOnlyIfSelected = true,
    ),
    HapticVibrationMode::class to DEFAULT to describedResourceEntries(
        Triple(HapticVibrationMode.USE_VIBRATOR_DIRECTLY, R.string.enum__haptic_vibration_mode__use_vibrator_directly,
            R.string.enum__haptic_vibration_mode__use_vibrator_directly__description),
        Triple(HapticVibrationMode.USE_HAPTIC_FEEDBACK_INTERFACE,
            R.string.enum__haptic_vibration_mode__use_haptic_feedback_interface,
            R.string.enum__haptic_vibration_mode__use_haptic_feedback_interface__description),
        showDescriptionOnlyIfSelected = true,
    ),
    KeyHintMode::class to DEFAULT to describedResourceEntries(
        Triple(KeyHintMode.ACCENT_PRIORITY, R.string.enum__key_hint_mode__accent_priority,
            R.string.enum__key_hint_mode__accent_priority__description),
        Triple(KeyHintMode.HINT_PRIORITY, R.string.enum__key_hint_mode__hint_priority,
            R.string.enum__key_hint_mode__hint_priority__description),
        Triple(KeyHintMode.SMART_PRIORITY, R.string.enum__key_hint_mode__smart_priority,
            R.string.enum__key_hint_mode__smart_priority__description),
        showDescriptionOnlyIfSelected = true,
    ),
    KeyHintPlacement::class to DEFAULT to resourceEntries(
        KeyHintPlacement.CORNER to R.string.enum__key_hint_placement__corner,
        KeyHintPlacement.INSET to R.string.enum__key_hint_placement__inset,
    ),
    IncognitoDisplayMode::class to DEFAULT to resourceEntries(
        IncognitoDisplayMode.REPLACE_SHARED_ACTIONS_TOGGLE to
            R.string.enum__incognito_display_mode__replace_shared_actions_toggle,
        IncognitoDisplayMode.DISPLAY_BEHIND_KEYBOARD to R.string.enum__incognito_display_mode__display_behind_keyboard,
    ),
    IncognitoMode::class to DEFAULT to describedResourceEntries(
        Triple(IncognitoMode.FORCE_OFF, R.string.enum__incognito_mode__force_off,
            R.string.enum__incognito_mode__force_off__description),
        Triple(IncognitoMode.DYNAMIC_ON_OFF, R.string.enum__incognito_mode__dynamic_on_off,
            R.string.enum__incognito_mode__dynamic_on_off__description),
        Triple(IncognitoMode.FORCE_ON, R.string.enum__incognito_mode__force_on,
            R.string.enum__incognito_mode__force_on__description),
        showDescriptionOnlyIfSelected = true,
    ),
    InputFeedbackActivationMode::class to "audio" to resourceEntries(
        InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS to
            R.string.enum__input_feedback_activation_mode__audio_respect_system_settings,
        InputFeedbackActivationMode.IGNORE_SYSTEM_SETTINGS to
            R.string.enum__input_feedback_activation_mode__audio_ignore_system_settings,
    ),
    InputFeedbackActivationMode::class to "haptic" to resourceEntries(
        InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS to
            R.string.enum__input_feedback_activation_mode__haptic_respect_system_settings,
        InputFeedbackActivationMode.IGNORE_SYSTEM_SETTINGS to
            R.string.enum__input_feedback_activation_mode__haptic_ignore_system_settings,
    ),
    InputShiftState::class to DEFAULT to resourceEntries(
        InputShiftState.UNSHIFTED to R.string.enum__input_shift_state__unshifted,
        InputShiftState.SHIFTED_MANUAL to R.string.enum__input_shift_state__shifted_manual,
        InputShiftState.SHIFTED_AUTOMATIC to R.string.enum__input_shift_state__shifted_automatic,
        InputShiftState.CAPS_LOCK to R.string.enum__input_shift_state__caps_lock,
    ),
    ImeWindowMode::class to DEFAULT to resourceEntries(
        ImeWindowMode.FIXED to R.string.enum__ime_window_mode__fixed,
        ImeWindowMode.FLOATING to R.string.enum__ime_window_mode__floating,
    ),
    KeyboardContentScaleMode::class to DEFAULT to resourceEntries(
        KeyboardContentScaleMode.FOLLOW_KEYBOARD_HEIGHT to
            R.string.enum__keyboard_content_scale_mode__follow_keyboard_height,
        KeyboardContentScaleMode.FIXED to R.string.enum__keyboard_content_scale_mode__fixed,
    ),
    KeyboardMode::class to DEFAULT to resourceEntries(
        KeyboardMode.CHARACTERS to R.string.enum__keyboard_mode__characters,
        KeyboardMode.SYMBOLS to R.string.enum__keyboard_mode__symbols,
        KeyboardMode.SYMBOLS2 to R.string.enum__keyboard_mode__symbols2,
        KeyboardMode.NUMERIC to R.string.enum__keyboard_mode__numeric,
        KeyboardMode.NUMERIC_ADVANCED to R.string.enum__keyboard_mode__numeric_advanced,
        KeyboardMode.PHONE to R.string.enum__keyboard_mode__phone,
        KeyboardMode.PHONE2 to R.string.enum__keyboard_mode__phone2,
    ),
    LandscapeInputUiMode::class to DEFAULT to resourceEntries(
        LandscapeInputUiMode.NEVER_SHOW to R.string.enum__landscape_input_ui_mode__never_show,
        LandscapeInputUiMode.ALWAYS_SHOW to R.string.enum__landscape_input_ui_mode__always_show,
        LandscapeInputUiMode.DYNAMICALLY_SHOW to R.string.enum__landscape_input_ui_mode__dynamically_show,
    ),
    SmartbarLayout::class to DEFAULT to describedResourceEntries(
        Triple(SmartbarLayout.SUGGESTIONS_ONLY, R.string.enum__smartbar_layout__suggestions_only,
            R.string.enum__smartbar_layout__suggestions_only__description),
        Triple(SmartbarLayout.ACTIONS_ONLY, R.string.enum__smartbar_layout__actions_only,
            R.string.enum__smartbar_layout__actions_only__description),
        Triple(SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED, R.string.enum__smartbar_layout__suggestions_action_shared,
            R.string.enum__smartbar_layout__suggestions_action_shared__description),
        Triple(SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED,
            R.string.enum__smartbar_layout__suggestions_actions_extended,
            R.string.enum__smartbar_layout__suggestions_actions_extended__description),
    ),
    SharedActionsTransitionMode::class to DEFAULT to resourceEntries(
        SharedActionsTransitionMode.CURRENT to R.string.enum__shared_actions_transition_mode__current,
        SharedActionsTransitionMode.CLASSIC to R.string.enum__shared_actions_transition_mode__classic,
    ),
    SmartbarMotionMode::class to DEFAULT to resourceEntries(
        SmartbarMotionMode.STANDARD to R.string.enum__smartbar_motion_mode__standard,
        SmartbarMotionMode.REDUCED to R.string.enum__smartbar_motion_mode__reduced,
        SmartbarMotionMode.OFF to R.string.enum__smartbar_motion_mode__off,
    ),
    SnyggLevel::class to DEFAULT to describedResourceEntries(
        Triple(SnyggLevel.BASIC, R.string.enum__snygg_level__basic,
            R.string.enum__snygg_level__basic__description),
        Triple(SnyggLevel.ADVANCED, R.string.enum__snygg_level__advanced,
            R.string.enum__snygg_level__advanced__description),
        Triple(SnyggLevel.DEVELOPER, R.string.enum__snygg_level__developer,
            R.string.enum__snygg_level__developer__description),
        showDescriptionOnlyIfSelected = true,
    ),
    SpaceBarMode::class to DEFAULT to resourceEntries(
        SpaceBarMode.NOTHING to R.string.enum__space_bar_mode__nothing,
        SpaceBarMode.CURRENT_LANGUAGE to R.string.enum__space_bar_mode__current_language,
        SpaceBarMode.SPACE_BAR_KEY to R.string.enum__space_bar_mode__space_bar_key,
    ),
    SpaceBarLanguageLabelMode::class to DEFAULT to resourceEntries(
        SpaceBarLanguageLabelMode.LOCALE_NAME to R.string.enum__space_bar_language_label_mode__locale_name,
        SpaceBarLanguageLabelMode.LANGUAGE_NAME to R.string.enum__space_bar_language_label_mode__language_name,
        SpaceBarLanguageLabelMode.LANGUAGE_CODE to R.string.enum__space_bar_language_label_mode__language_code,
    ),
    SwipeActivationArea::class to DEFAULT to resourceEntries(
        SwipeActivationArea.KEYS_ONLY to R.string.enum__swipe_activation_area__keys_only,
        SwipeActivationArea.ENTIRE_KEYBOARD to R.string.enum__swipe_activation_area__entire_keyboard,
    ),
    SwipeAction::class to "general" to resourceEntries(
        SwipeAction.NO_ACTION to R.string.enum__swipe_action__no_action,
        SwipeAction.CYCLE_TO_PREVIOUS_KEYBOARD_MODE to R.string.enum__swipe_action__cycle_to_previous_keyboard_mode,
        SwipeAction.CYCLE_TO_NEXT_KEYBOARD_MODE to R.string.enum__swipe_action__cycle_to_next_keyboard_mode,
        SwipeAction.DELETE_WORD to R.string.enum__swipe_action__delete_word,
        SwipeAction.HIDE_KEYBOARD to R.string.enum__swipe_action__hide_keyboard,
        SwipeAction.INSERT_SPACE to R.string.enum__swipe_action__insert_space,
        SwipeAction.MOVE_CURSOR_UP to R.string.enum__swipe_action__move_cursor_up,
        SwipeAction.MOVE_CURSOR_DOWN to R.string.enum__swipe_action__move_cursor_down,
        SwipeAction.MOVE_CURSOR_LEFT to R.string.enum__swipe_action__move_cursor_left,
        SwipeAction.MOVE_CURSOR_RIGHT to R.string.enum__swipe_action__move_cursor_right,
        SwipeAction.MOVE_CURSOR_START_OF_LINE to R.string.enum__swipe_action__move_cursor_start_of_line,
        SwipeAction.MOVE_CURSOR_END_OF_LINE to R.string.enum__swipe_action__move_cursor_end_of_line,
        SwipeAction.MOVE_CURSOR_START_OF_PAGE to R.string.enum__swipe_action__move_cursor_start_of_page,
        SwipeAction.MOVE_CURSOR_END_OF_PAGE to R.string.enum__swipe_action__move_cursor_end_of_page,
        SwipeAction.SHIFT to R.string.enum__swipe_action__shift,
        SwipeAction.REDO to R.string.enum__swipe_action__redo,
        SwipeAction.UNDO to R.string.enum__swipe_action__undo,
        SwipeAction.SWITCH_TO_CLIPBOARD_CONTEXT to R.string.enum__swipe_action__switch_to_clipboard_context,
        SwipeAction.SWITCH_TO_MEDIA_CONTEXT to R.string.enum__swipe_action__switch_to_media_context,
        SwipeAction.SHOW_INPUT_METHOD_PICKER to R.string.enum__swipe_action__show_input_method_picker,
        SwipeAction.SHOW_SUBTYPE_PICKER to R.string.enum__swipe_action__show_subtype_picker,
        SwipeAction.SWITCH_TO_PREV_SUBTYPE to R.string.enum__swipe_action__switch_to_prev_subtype,
        SwipeAction.SWITCH_TO_NEXT_SUBTYPE to R.string.enum__swipe_action__switch_to_next_subtype,
        SwipeAction.SWITCH_TO_PREV_KEYBOARD to R.string.enum__swipe_action__switch_to_prev_keyboard,
        SwipeAction.TOGGLE_SMARTBAR_VISIBILITY to R.string.enum__swipe_action__toggle_smartbar_visibility,
        SwipeAction.TOGGLE_COMPACT_LAYOUT to R.string.enum__swipe_action__toggle_compact_layout,
    ),
    SwipeAction::class to "deleteSwipe" to resourceEntries(
        SwipeAction.NO_ACTION to R.string.enum__swipe_action__no_action,
        SwipeAction.DELETE_CHARACTERS_PRECISELY to R.string.enum__swipe_action__delete_characters_precisely,
        SwipeAction.DELETE_WORD to R.string.enum__swipe_action__delete_word,
        SwipeAction.DELETE_WORDS_PRECISELY to R.string.enum__swipe_action__delete_words_precisely,
        SwipeAction.SELECT_CHARACTERS_PRECISELY to R.string.enum__swipe_action__select_characters_precisely,
        SwipeAction.SELECT_WORDS_PRECISELY to R.string.enum__swipe_action__select_words_precisely,
    ),
    SwipeAction::class to "deleteLongPress" to resourceEntries(
        SwipeAction.DELETE_CHARACTER to R.string.enum__swipe_action__delete_character,
        SwipeAction.DELETE_WORD to R.string.enum__swipe_action__delete_word,
    ),
    ThemeMode::class to DEFAULT to resourceEntries(
        ThemeMode.ALWAYS_DAY to R.string.enum__theme_mode__always_day,
        ThemeMode.ALWAYS_NIGHT to R.string.enum__theme_mode__always_night,
        ThemeMode.FOLLOW_SYSTEM to R.string.enum__theme_mode__follow_system,
        ThemeMode.FOLLOW_TIME to R.string.enum__theme_mode__follow_time,
    ),
    UtilityKeyAction::class to DEFAULT to resourceEntries(
        UtilityKeyAction.SWITCH_TO_EMOJIS to R.string.enum__utility_key_action__switch_to_emojis,
        UtilityKeyAction.SWITCH_LANGUAGE to R.string.enum__utility_key_action__switch_language,
        UtilityKeyAction.SWITCH_KEYBOARD_APP to R.string.enum__utility_key_action__switch_keyboard_app,
        UtilityKeyAction.DYNAMIC_SWITCH_LANGUAGE_EMOJIS to
            R.string.enum__utility_key_action__dynamic_switch_language_emojis,
    ),
)

@Composable
fun <V : Any> enumDisplayEntriesOf(
    enumClass: KClass<V>,
    variant: String = DEFAULT,
): List<ListPreferenceEntry<V>> {
    @Suppress("UNCHECKED_CAST")
    return ENUM_DISPLAY_ENTRIES[enumClass to variant]?.invoke()
        as List<ListPreferenceEntry<V>>
}
