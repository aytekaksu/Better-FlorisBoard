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
import dev.patrickgold.florisboard.ime.nlp.SpellingLanguageMode
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

private val ENUM_DISPLAY_ENTRIES = mapOf<Pair<KClass<*>, String>, @Composable () -> List<ListPreferenceEntry<*>>>(
    AppTheme::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(AppTheme.AUTO, R.string.settings__system_default)
            ResourceEntry(AppTheme.AUTO_AMOLED, R.string.pref__other__settings_theme__auto_amoled)
            ResourceEntry(AppTheme.LIGHT, R.string.pref__other__settings_theme__light)
            ResourceEntry(AppTheme.DARK, R.string.pref__other__settings_theme__dark)
            ResourceEntry(AppTheme.AMOLED_DARK, R.string.pref__other__settings_theme__amoled_dark)
        }
    },
    CandidatesDisplayMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(CandidatesDisplayMode.CLASSIC, R.string.enum__candidates_display_mode__classic)
            ResourceEntry(CandidatesDisplayMode.DYNAMIC, R.string.enum__candidates_display_mode__dynamic)
            ResourceEntry(
                CandidatesDisplayMode.DYNAMIC_SCROLLABLE,
                R.string.enum__candidates_display_mode__dynamic_scrollable,
            )
        }
    },
    CapitalizationBehavior::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(
                CapitalizationBehavior.CAPSLOCK_BY_DOUBLE_TAP,
                R.string.enum__capitalization_behavior__capslock_by_double_tap,
            )
            ResourceEntry(
                CapitalizationBehavior.CAPSLOCK_BY_CYCLE,
                R.string.enum__capitalization_behavior__capslock_by_cycle,
            )
        }
    },
    ClipboardSyncBehavior::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = ClipboardSyncBehavior.NO_EVENTS,
                label = stringRes(R.string.enum__clipboard_sync_behavior__no_events),
                description = stringRes(R.string.enum__clipboard_sync_behavior__no_events__description),
            )
            entry(
                key = ClipboardSyncBehavior.ONLY_CLEAR_EVENTS,
                label = stringRes(R.string.enum__clipboard_sync_behavior__only_clear_events),
                description = stringRes(R.string.enum__clipboard_sync_behavior__only_clear_events__description),
            )
            entry(
                key = ClipboardSyncBehavior.ONLY_SET_EVENTS,
                label = stringRes(R.string.enum__clipboard_sync_behavior__only_set_events),
                description = stringRes(R.string.enum__clipboard_sync_behavior__only_set_events__description),
            )
            entry(
                key = ClipboardSyncBehavior.ALL_EVENTS,
                label = stringRes(R.string.enum__clipboard_sync_behavior__all_events),
                description = stringRes(R.string.enum__clipboard_sync_behavior__all_events__description),
            )
        }
    },
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
    DisplayKbdAfterDialogs::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = DisplayKbdAfterDialogs.ALWAYS,
                label = stringRes(R.string.enum__display_kbd_after_dialogs__always),
                description = stringRes(R.string.enum__display_kbd_after_dialogs__always__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = DisplayKbdAfterDialogs.NEVER,
                label = stringRes(R.string.enum__display_kbd_after_dialogs__never),
                description = stringRes(R.string.enum__display_kbd_after_dialogs__never__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = DisplayKbdAfterDialogs.REMEMBER,
                label = stringRes(R.string.enum__display_kbd_after_dialogs__remember),
                description = stringRes(R.string.enum__display_kbd_after_dialogs__remember__description),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    DisplayLanguageNamesIn::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = DisplayLanguageNamesIn.SYSTEM_LOCALE,
                label = stringRes(R.string.enum__display_language_names_in__system_locale),
                description = stringRes(R.string.enum__display_language_names_in__system_locale__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = DisplayLanguageNamesIn.NATIVE_LOCALE,
                label = stringRes(R.string.enum__display_language_names_in__native_locale),
                description = stringRes(R.string.enum__display_language_names_in__native_locale__description),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    EmojiHistory.UpdateStrategy::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = EmojiHistory.UpdateStrategy.AUTO_SORT_PREPEND,
                label = stringRes(R.string.enum__emoji_history_update_strategy__auto_sort_prepend),
                description = stringRes(R.string.enum__emoji_history_update_strategy__auto_sort_prepend__description),
            )
            entry(
                key = EmojiHistory.UpdateStrategy.AUTO_SORT_APPEND,
                label = stringRes(R.string.enum__emoji_history_update_strategy__auto_sort_append),
                description = stringRes(R.string.enum__emoji_history_update_strategy__auto_sort_append__description),
            )
            entry(
                key = EmojiHistory.UpdateStrategy.MANUAL_SORT_PREPEND,
                label = stringRes(R.string.enum__emoji_history_update_strategy__manual_sort_prepend),
                description = stringRes(R.string.enum__emoji_history_update_strategy__manual_sort_prepend__description),
            )
            entry(
                key = EmojiHistory.UpdateStrategy.MANUAL_SORT_APPEND,
                label = stringRes(R.string.enum__emoji_history_update_strategy__manual_sort_append),
                description = stringRes(R.string.enum__emoji_history_update_strategy__manual_sort_append__description),
            )
        }
    },
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
    EmojiSuggestionType::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = EmojiSuggestionType.LEADING_COLON,
                label = stringRes(R.string.enum__emoji_suggestion_type__leading_colon),
                description = stringRes(R.string.enum__emoji_suggestion_type__leading_colon__description),
            )
            entry(
                key = EmojiSuggestionType.INLINE_TEXT,
                label = stringRes(R.string.enum__emoji_suggestion_type__inline_text),
                description = stringRes(R.string.enum__emoji_suggestion_type__inline_text__description),
            )
        }
    },
    ExtendedActionsPlacement::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = ExtendedActionsPlacement.ABOVE_CANDIDATES,
                label = stringRes(R.string.enum__extended_actions_placement__above_candidates),
                description = stringRes(R.string.enum__extended_actions_placement__above_candidates__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = ExtendedActionsPlacement.BELOW_CANDIDATES,
                label = stringRes(R.string.enum__extended_actions_placement__below_candidates),
                description = stringRes(R.string.enum__extended_actions_placement__below_candidates__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = ExtendedActionsPlacement.OVERLAY_APP_UI,
                label = stringRes(R.string.enum__extended_actions_placement__overlay_app_ui),
                description = stringRes(R.string.enum__extended_actions_placement__overlay_app_ui__description),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    HapticVibrationMode::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = HapticVibrationMode.USE_VIBRATOR_DIRECTLY,
                label = stringRes(R.string.enum__haptic_vibration_mode__use_vibrator_directly),
                description = stringRes(R.string.enum__haptic_vibration_mode__use_vibrator_directly__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = HapticVibrationMode.USE_HAPTIC_FEEDBACK_INTERFACE,
                label = stringRes(R.string.enum__haptic_vibration_mode__use_haptic_feedback_interface),
                description = stringRes(R.string.enum__haptic_vibration_mode__use_haptic_feedback_interface__description),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    KeyHintMode::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = KeyHintMode.ACCENT_PRIORITY,
                label = stringRes(R.string.enum__key_hint_mode__accent_priority),
                description = stringRes(R.string.enum__key_hint_mode__accent_priority__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = KeyHintMode.HINT_PRIORITY,
                label = stringRes(R.string.enum__key_hint_mode__hint_priority),
                description = stringRes(R.string.enum__key_hint_mode__hint_priority__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = KeyHintMode.SMART_PRIORITY,
                label = stringRes(R.string.enum__key_hint_mode__smart_priority),
                description = stringRes(R.string.enum__key_hint_mode__smart_priority__description),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    KeyHintPlacement::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(KeyHintPlacement.CORNER, R.string.enum__key_hint_placement__corner)
            ResourceEntry(KeyHintPlacement.INSET, R.string.enum__key_hint_placement__inset)
        }
    },
    IncognitoDisplayMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(
                IncognitoDisplayMode.REPLACE_SHARED_ACTIONS_TOGGLE,
                R.string.enum__incognito_display_mode__replace_shared_actions_toggle,
            )
            ResourceEntry(
                IncognitoDisplayMode.DISPLAY_BEHIND_KEYBOARD,
                R.string.enum__incognito_display_mode__display_behind_keyboard,
            )
        }
    },
    IncognitoMode::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = IncognitoMode.FORCE_OFF,
                label = stringRes(R.string.enum__incognito_mode__force_off),
                description = stringRes(R.string.enum__incognito_mode__force_off__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = IncognitoMode.DYNAMIC_ON_OFF,
                label = stringRes(R.string.enum__incognito_mode__dynamic_on_off),
                description = stringRes(R.string.enum__incognito_mode__dynamic_on_off__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = IncognitoMode.FORCE_ON,
                label = stringRes(R.string.enum__incognito_mode__force_on),
                description = stringRes(R.string.enum__incognito_mode__force_on__description),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    InputFeedbackActivationMode::class to "audio" to {
        listPrefEntries {
            ResourceEntry(
                InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS,
                R.string.enum__input_feedback_activation_mode__audio_respect_system_settings,
            )
            ResourceEntry(
                InputFeedbackActivationMode.IGNORE_SYSTEM_SETTINGS,
                R.string.enum__input_feedback_activation_mode__audio_ignore_system_settings,
            )
        }
    },
    InputFeedbackActivationMode::class to "haptic" to {
        listPrefEntries {
            ResourceEntry(
                InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS,
                R.string.enum__input_feedback_activation_mode__haptic_respect_system_settings,
            )
            ResourceEntry(
                InputFeedbackActivationMode.IGNORE_SYSTEM_SETTINGS,
                R.string.enum__input_feedback_activation_mode__haptic_ignore_system_settings,
            )
        }
    },
    InputShiftState::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(InputShiftState.UNSHIFTED, R.string.enum__input_shift_state__unshifted)
            ResourceEntry(InputShiftState.SHIFTED_MANUAL, R.string.enum__input_shift_state__shifted_manual)
            ResourceEntry(InputShiftState.SHIFTED_AUTOMATIC, R.string.enum__input_shift_state__shifted_automatic)
            ResourceEntry(InputShiftState.CAPS_LOCK, R.string.enum__input_shift_state__caps_lock)
        }
    },
    ImeWindowMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(ImeWindowMode.FIXED, R.string.enum__ime_window_mode__fixed)
            ResourceEntry(ImeWindowMode.FLOATING, R.string.enum__ime_window_mode__floating)
        }
    },
    KeyboardContentScaleMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(
                KeyboardContentScaleMode.FOLLOW_KEYBOARD_HEIGHT,
                R.string.enum__keyboard_content_scale_mode__follow_keyboard_height,
            )
            ResourceEntry(KeyboardContentScaleMode.FIXED, R.string.enum__keyboard_content_scale_mode__fixed)
        }
    },
    KeyboardMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(KeyboardMode.CHARACTERS, R.string.enum__keyboard_mode__characters)
            ResourceEntry(KeyboardMode.SYMBOLS, R.string.enum__keyboard_mode__symbols)
            ResourceEntry(KeyboardMode.SYMBOLS2, R.string.enum__keyboard_mode__symbols2)
            ResourceEntry(KeyboardMode.NUMERIC, R.string.enum__keyboard_mode__numeric)
            ResourceEntry(KeyboardMode.NUMERIC_ADVANCED, R.string.enum__keyboard_mode__numeric_advanced)
            ResourceEntry(KeyboardMode.PHONE, R.string.enum__keyboard_mode__phone)
            ResourceEntry(KeyboardMode.PHONE2, R.string.enum__keyboard_mode__phone2)
        }
    },
    LandscapeInputUiMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(LandscapeInputUiMode.NEVER_SHOW, R.string.enum__landscape_input_ui_mode__never_show)
            ResourceEntry(LandscapeInputUiMode.ALWAYS_SHOW, R.string.enum__landscape_input_ui_mode__always_show)
            ResourceEntry(LandscapeInputUiMode.DYNAMICALLY_SHOW, R.string.enum__landscape_input_ui_mode__dynamically_show)
        }
    },
    SmartbarLayout::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = SmartbarLayout.SUGGESTIONS_ONLY,
                label = stringRes(R.string.enum__smartbar_layout__suggestions_only),
                description = stringRes(R.string.enum__smartbar_layout__suggestions_only__description),
            )
            entry(
                key = SmartbarLayout.ACTIONS_ONLY,
                label = stringRes(R.string.enum__smartbar_layout__actions_only),
                description = stringRes(R.string.enum__smartbar_layout__actions_only__description),
            )
            entry(
                key = SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED,
                label = stringRes(R.string.enum__smartbar_layout__suggestions_action_shared),
                description = stringRes(R.string.enum__smartbar_layout__suggestions_action_shared__description),
            )
            entry(
                key = SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED,
                label = stringRes(R.string.enum__smartbar_layout__suggestions_actions_extended),
                description = stringRes(R.string.enum__smartbar_layout__suggestions_actions_extended__description),
            )
        }
    },
    SharedActionsTransitionMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(SharedActionsTransitionMode.CURRENT, R.string.enum__shared_actions_transition_mode__current)
            ResourceEntry(SharedActionsTransitionMode.CLASSIC, R.string.enum__shared_actions_transition_mode__classic)
        }
    },
    SmartbarMotionMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(SmartbarMotionMode.STANDARD, R.string.enum__smartbar_motion_mode__standard)
            ResourceEntry(SmartbarMotionMode.REDUCED, R.string.enum__smartbar_motion_mode__reduced)
            ResourceEntry(SmartbarMotionMode.OFF, R.string.enum__smartbar_motion_mode__off)
        }
    },
    SnyggLevel::class to DEFAULT to {
        listPrefEntries {
            entry(
                key = SnyggLevel.BASIC,
                label = stringRes(R.string.enum__snygg_level__basic),
                description = stringRes(R.string.enum__snygg_level__basic__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = SnyggLevel.ADVANCED,
                label = stringRes(R.string.enum__snygg_level__advanced),
                description = stringRes(R.string.enum__snygg_level__advanced__description),
                showDescriptionOnlyIfSelected = true,
            )
            entry(
                key = SnyggLevel.DEVELOPER,
                label = stringRes(R.string.enum__snygg_level__developer),
                description = stringRes(R.string.enum__snygg_level__developer__description),
                showDescriptionOnlyIfSelected = true,
            )
        }
    },
    SpaceBarMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(SpaceBarMode.NOTHING, R.string.enum__space_bar_mode__nothing)
            ResourceEntry(SpaceBarMode.CURRENT_LANGUAGE, R.string.enum__space_bar_mode__current_language)
            ResourceEntry(SpaceBarMode.SPACE_BAR_KEY, R.string.enum__space_bar_mode__space_bar_key)
        }
    },
    SpaceBarLanguageLabelMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(SpaceBarLanguageLabelMode.LOCALE_NAME, R.string.enum__space_bar_language_label_mode__locale_name)
            ResourceEntry(
                SpaceBarLanguageLabelMode.LANGUAGE_NAME,
                R.string.enum__space_bar_language_label_mode__language_name,
            )
            ResourceEntry(
                SpaceBarLanguageLabelMode.LANGUAGE_CODE,
                R.string.enum__space_bar_language_label_mode__language_code,
            )
        }
    },
    SpellingLanguageMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(
                SpellingLanguageMode.USE_SYSTEM_LANGUAGES,
                R.string.enum__spelling_language_mode__use_system_languages,
            )
            ResourceEntry(
                SpellingLanguageMode.USE_KEYBOARD_SUBTYPES,
                R.string.enum__spelling_language_mode__use_keyboard_subtypes,
            )
        }
    },
    SwipeActivationArea::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(SwipeActivationArea.KEYS_ONLY, R.string.enum__swipe_activation_area__keys_only)
            ResourceEntry(SwipeActivationArea.ENTIRE_KEYBOARD, R.string.enum__swipe_activation_area__entire_keyboard)
        }
    },
    SwipeAction::class to "general" to {
        listPrefEntries {
            ResourceEntry(SwipeAction.NO_ACTION, R.string.enum__swipe_action__no_action)
            ResourceEntry(
                SwipeAction.CYCLE_TO_PREVIOUS_KEYBOARD_MODE,
                R.string.enum__swipe_action__cycle_to_previous_keyboard_mode,
            )
            ResourceEntry(SwipeAction.CYCLE_TO_NEXT_KEYBOARD_MODE, R.string.enum__swipe_action__cycle_to_next_keyboard_mode)
            ResourceEntry(SwipeAction.DELETE_WORD, R.string.enum__swipe_action__delete_word)
            ResourceEntry(SwipeAction.HIDE_KEYBOARD, R.string.enum__swipe_action__hide_keyboard)
            ResourceEntry(SwipeAction.INSERT_SPACE, R.string.enum__swipe_action__insert_space)
            ResourceEntry(SwipeAction.MOVE_CURSOR_UP, R.string.enum__swipe_action__move_cursor_up)
            ResourceEntry(SwipeAction.MOVE_CURSOR_DOWN, R.string.enum__swipe_action__move_cursor_down)
            ResourceEntry(SwipeAction.MOVE_CURSOR_LEFT, R.string.enum__swipe_action__move_cursor_left)
            ResourceEntry(SwipeAction.MOVE_CURSOR_RIGHT, R.string.enum__swipe_action__move_cursor_right)
            ResourceEntry(SwipeAction.MOVE_CURSOR_START_OF_LINE, R.string.enum__swipe_action__move_cursor_start_of_line)
            ResourceEntry(SwipeAction.MOVE_CURSOR_END_OF_LINE, R.string.enum__swipe_action__move_cursor_end_of_line)
            ResourceEntry(SwipeAction.MOVE_CURSOR_START_OF_PAGE, R.string.enum__swipe_action__move_cursor_start_of_page)
            ResourceEntry(SwipeAction.MOVE_CURSOR_END_OF_PAGE, R.string.enum__swipe_action__move_cursor_end_of_page)
            ResourceEntry(SwipeAction.SHIFT, R.string.enum__swipe_action__shift)
            ResourceEntry(SwipeAction.REDO, R.string.enum__swipe_action__redo)
            ResourceEntry(SwipeAction.UNDO, R.string.enum__swipe_action__undo)
            ResourceEntry(SwipeAction.SWITCH_TO_CLIPBOARD_CONTEXT, R.string.enum__swipe_action__switch_to_clipboard_context)
            ResourceEntry(SwipeAction.SWITCH_TO_MEDIA_CONTEXT, R.string.enum__swipe_action__switch_to_media_context)
            ResourceEntry(SwipeAction.SHOW_INPUT_METHOD_PICKER, R.string.enum__swipe_action__show_input_method_picker)
            ResourceEntry(SwipeAction.SHOW_SUBTYPE_PICKER, R.string.enum__swipe_action__show_subtype_picker)
            ResourceEntry(SwipeAction.SWITCH_TO_PREV_SUBTYPE, R.string.enum__swipe_action__switch_to_prev_subtype)
            ResourceEntry(SwipeAction.SWITCH_TO_NEXT_SUBTYPE, R.string.enum__swipe_action__switch_to_next_subtype)
            ResourceEntry(SwipeAction.SWITCH_TO_PREV_KEYBOARD, R.string.enum__swipe_action__switch_to_prev_keyboard)
            ResourceEntry(SwipeAction.TOGGLE_SMARTBAR_VISIBILITY, R.string.enum__swipe_action__toggle_smartbar_visibility)
            ResourceEntry(SwipeAction.TOGGLE_COMPACT_LAYOUT, R.string.enum__swipe_action__toggle_compact_layout)
        }
    },
    SwipeAction::class to "deleteSwipe" to {
        listPrefEntries {
            ResourceEntry(SwipeAction.NO_ACTION, R.string.enum__swipe_action__no_action)
            ResourceEntry(SwipeAction.DELETE_CHARACTERS_PRECISELY, R.string.enum__swipe_action__delete_characters_precisely)
            ResourceEntry(SwipeAction.DELETE_WORD, R.string.enum__swipe_action__delete_word)
            ResourceEntry(SwipeAction.DELETE_WORDS_PRECISELY, R.string.enum__swipe_action__delete_words_precisely)
            ResourceEntry(SwipeAction.SELECT_CHARACTERS_PRECISELY, R.string.enum__swipe_action__select_characters_precisely)
            ResourceEntry(SwipeAction.SELECT_WORDS_PRECISELY, R.string.enum__swipe_action__select_words_precisely)
        }
    },
    SwipeAction::class to "deleteLongPress" to {
        listPrefEntries {
            ResourceEntry(SwipeAction.DELETE_CHARACTER, R.string.enum__swipe_action__delete_character)
            ResourceEntry(SwipeAction.DELETE_WORD, R.string.enum__swipe_action__delete_word)
        }
    },
    ThemeMode::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(ThemeMode.ALWAYS_DAY, R.string.enum__theme_mode__always_day)
            ResourceEntry(ThemeMode.ALWAYS_NIGHT, R.string.enum__theme_mode__always_night)
            ResourceEntry(ThemeMode.FOLLOW_SYSTEM, R.string.enum__theme_mode__follow_system)
            ResourceEntry(ThemeMode.FOLLOW_TIME, R.string.enum__theme_mode__follow_time)
        }
    },
    UtilityKeyAction::class to DEFAULT to {
        listPrefEntries {
            ResourceEntry(UtilityKeyAction.SWITCH_TO_EMOJIS, R.string.enum__utility_key_action__switch_to_emojis)
            ResourceEntry(UtilityKeyAction.SWITCH_LANGUAGE, R.string.enum__utility_key_action__switch_language)
            ResourceEntry(UtilityKeyAction.SWITCH_KEYBOARD_APP, R.string.enum__utility_key_action__switch_keyboard_app)
            ResourceEntry(
                UtilityKeyAction.DYNAMIC_SWITCH_LANGUAGE_EMOJIS,
                R.string.enum__utility_key_action__dynamic_switch_language_emojis,
            )
        }
    },
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
