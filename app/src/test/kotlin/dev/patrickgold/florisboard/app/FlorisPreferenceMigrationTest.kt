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

package dev.patrickgold.florisboard.app

import androidx.compose.ui.graphics.Color
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSyncBehavior
import dev.patrickgold.florisboard.ime.input.HapticVibrationMode
import dev.patrickgold.florisboard.ime.input.InputFeedbackActivationMode
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarMode
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.smartbar.ExtendedActionsPlacement
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionJsonConfig
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.ThemeMode
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.runtime.DataStoreReader
import dev.patrickgold.jetpref.datastore.runtime.DataStoreWriter
import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import dev.patrickgold.jetpref.material.ui.ColorRepresentation
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.florisboard.lib.color.DEFAULT_GREEN

class FlorisPreferenceMigrationTest :
    FunSpec({
        test("minimum supported lowercase preferences preserve behavior") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(
                    encodedPreferences(
                        """b;advanced__force_private_mode;true""",
                        """s;advanced__settings_theme;"dark"""",
                        """s;gestures__swipe_up;"shift"""",
                        """s;theme__mode;"always_night"""",
                    ),
                )

                fixture.prefs.suggestion.incognitoMode.get() shouldBe IncognitoMode.FORCE_ON
                fixture.prefs.other.settingsTheme.get() shouldBe AppTheme.DARK
                fixture.prefs.gestures.swipeUp.get() shouldBe SwipeAction.SHIFT
                fixture.prefs.theme.mode.get() shouldBe ThemeMode.ALWAYS_NIGHT
                fixture.exportedKeys() shouldContainExactlyInAnyOrder setOf(
                    "suggestion__incognito_mode",
                    "other__settings_theme",
                    "gestures__swipe_up",
                    "theme__mode",
                )
            }
        }

        test("every retained lowercase enum selector loads a real enum value") {
            runTest {
                legacyLowercaseEnums.forEach { (key, value) ->
                    val fixture = PreferenceFixture()
                    fixture.load(encodedPreferences("""s;$key;"$value""""))
                    val migratedValue = fixture.prefs.declaredPreferenceEntries
                        .entries
                        .single { it.key.key == key }
                        .value
                        .getOrNull()
                    val exportedKeys = fixture.exportedKeys()

                    withClue(key) {
                        migratedValue shouldNotBe null
                        exportedKeys shouldBe setOf(key)
                    }
                }
            }
        }

        test("disabled legacy private mode does not override the current default") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(encodedPreferences("""b;advanced__force_private_mode;false"""))

                fixture.prefs.suggestion.incognitoMode.getOrNull() shouldBe null
                fixture.prefs.suggestion.incognitoMode.get() shouldBe IncognitoMode.DYNAMIC_ON_OFF
                fixture.exportedKeys() shouldBe emptySet()
            }
        }

        test("legacy input feedback booleans retain their meaning") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(
                    encodedPreferences(
                        """b;input_feedback__audio_ignore_system_settings;true""",
                        """b;input_feedback__haptic_ignore_system_settings;false""",
                        """b;input_feedback__haptic_use_vibrator;false""",
                    ),
                )

                fixture.prefs.inputFeedback.audioActivationMode.get() shouldBe
                    InputFeedbackActivationMode.IGNORE_SYSTEM_SETTINGS
                fixture.prefs.inputFeedback.hapticActivationMode.get() shouldBe
                    InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS
                fixture.prefs.inputFeedback.hapticVibrationMode.get() shouldBe
                    HapticVibrationMode.USE_HAPTIC_FEEDBACK_INTERFACE
                fixture.exportedKeys() shouldContainExactlyInAnyOrder setOf(
                    "input_feedback__audio_activation_mode",
                    "input_feedback__haptic_activation_mode",
                    "input_feedback__haptic_vibration_mode",
                )
            }
        }

        test("legacy media and space-bar preferences retain their meaning") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(
                    encodedPreferences(
                        """s;media__emoji_preferred_skin_tone;"medium_dark_skin_tone"""",
                        """b;keyboard__space_bar_language_display_enabled;false""",
                    ),
                )

                fixture.prefs.emoji.preferredSkinTone.get() shouldBe EmojiSkinTone.MEDIUM_DARK_SKIN_TONE
                fixture.prefs.keyboard.spaceBarMode.get() shouldBe SpaceBarMode.NOTHING
                fixture.exportedKeys() shouldContainExactlyInAnyOrder setOf(
                    "emoji__preferred_skin_tone",
                    "keyboard__space_bar_display_mode",
                )
            }
        }

        test("legacy Material You choices preserve explicit accent behavior") {
            runTest {
                val fixedFixture = PreferenceFixture()
                fixedFixture.load(encodedPreferences("""b;advanced__use_material_you;false"""))
                fixedFixture.prefs.other.accentColor.get() shouldBe DEFAULT_GREEN

                val dynamicFixture = PreferenceFixture()
                dynamicFixture.prefs.other.accentColor.set(DEFAULT_GREEN)
                dynamicFixture.load(
                    raw = encodedPreferences("""b;advanced__use_material_you;true"""),
                    strategy = ImportStrategy.Merge,
                )
                dynamicFixture.prefs.other.accentColor.get() shouldBe Color.Unspecified
            }
        }

        test("empty legacy emoji history stays empty") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(encodedPreferences("""s;media__emoji_recently_used;"  ; ;  """"))

                val migrated = fixture.prefs.emoji.historyData.get()
                migrated.recent.size shouldBe 0
            }
        }

        test("legacy emoji history trims and drops blank entries") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(encodedPreferences("""s;media__emoji_recently_used;" alpha ; ; beta """"))

                val migrated = fixture.prefs.emoji.historyData.get()
                migrated.recent.size shouldBe 2
                migrated.recent.all { it.value.isNotEmpty() && it.value == it.value.trim() } shouldBe true
            }
        }

        test("legacy clipboard sync booleans retain their meaning") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(
                    encodedPreferences(
                        """b;clipboard__sync_to_floris;false""",
                        """b;clipboard__sync_to_system;true""",
                    ),
                )

                fixture.prefs.clipboard.syncToFloris.get() shouldBe ClipboardSyncBehavior.NO_EVENTS
                fixture.prefs.clipboard.syncToSystem.get() shouldBe ClipboardSyncBehavior.ALL_EVENTS
            }
        }

        test("legacy Smartbar names retain their one-to-one settings") {
            runTest {
                val betaFixture = PreferenceFixture()
                betaFixture.load(
                    encodedPreferences(
                        """b;smartbar__primary_row_flip_toggles;true""",
                        """b;smartbar__action_row_expanded;true""",
                        """b;smartbar__secondary_row_expanded;true""",
                        """s;smartbar__secondary_row_placement;"below_primary"""",
                    ),
                )

                betaFixture.prefs.smartbar.flipToggles.get() shouldBe true
                betaFixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe true
                betaFixture.prefs.smartbar.extendedActionsExpanded.get() shouldBe true
                betaFixture.prefs.smartbar.extendedActionsPlacement.get() shouldBe
                    ExtendedActionsPlacement.BELOW_CANDIDATES

                val releaseFixture = PreferenceFixture()
                releaseFixture.load(
                    encodedPreferences(
                        """b;smartbar__primary_actions_expanded;true""",
                        """b;smartbar__secondary_actions_expanded;true""",
                        """s;smartbar__secondary_actions_placement;"OVERLAY_APP_UI"""",
                    ),
                )

                releaseFixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe true
                releaseFixture.prefs.smartbar.extendedActionsExpanded.get() shouldBe true
                releaseFixture.prefs.smartbar.extendedActionsPlacement.get() shouldBe
                    ExtendedActionsPlacement.OVERLAY_APP_UI
            }
        }

        test("malformed legacy values are dropped") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(
                    encodedPreferences(
                        """s;smartbar__secondary_row_placement;"somewhere_else"""",
                        """b;input_feedback__audio_ignore_system_settings;not-a-boolean""",
                        """i;input_feedback__haptic_ignore_system_settings;1""",
                        """i;clipboard__sync_to_floris;1""",
                        """s;theme__editor_display_colors_as;"unknown"""",
                    ),
                )

                fixture.prefs.smartbar.extendedActionsPlacement.getOrNull() shouldBe null
                fixture.prefs.inputFeedback.audioActivationMode.getOrNull() shouldBe null
                fixture.prefs.inputFeedback.hapticActivationMode.getOrNull() shouldBe null
                fixture.prefs.clipboard.syncToFloris.getOrNull() shouldBe null
                fixture.prefs.theme.editorColorRepresentation.getOrNull() shouldBe null
                fixture.exportedKeys() shouldBe emptySet()
            }
        }

        test("malformed legacy Smartbar data is dropped without aborting valid siblings") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(
                    encodedPreferences(
                        """s;smartbar__action_arrangement;"not-json"""",
                        """b;correction__auto_capitalization;false""",
                    ),
                )

                fixture.prefs.smartbar.actionArrangement.getOrNull() shouldBe null
                fixture.prefs.correction.autoCapitalization.get() shouldBe false
                fixture.exportedKeys() shouldBe setOf("correction__auto_capitalization")
            }
        }

        test("Smartbar arrangement migration retains actions and adds required controls") {
            runTest {
                val fixture = PreferenceFixture()
                val original = QuickActionArrangement(
                    stickyAction = QuickAction.InsertKey(TextKeyData.COMPACT_LAYOUT_TO_RIGHT),
                    dynamicActions = emptyList(),
                    hiddenActions = emptyList(),
                )
                val rawValue = QuickActionJsonConfig.encodeToString(original)
                fixture.load(
                    encodedPreferences(
                        "s;smartbar__action_arrangement;${Json.encodeToString(rawValue)}",
                    ),
                )

                val migrated = fixture.prefs.smartbar.actionArrangement.get()
                val requiredActions = listOf(
                    TextKeyData.LANGUAGE_SWITCH,
                    TextKeyData.FORWARD_DELETE,
                    TextKeyData.IME_HIDE_UI,
                    TextKeyData.TOGGLE_FLOATING_WINDOW,
                    TextKeyData.TOGGLE_RESIZE_MODE,
                ).map { QuickAction.InsertKey(it) }
                migrated.stickyAction shouldBe QuickAction.InsertKey(TextKeyData.TOGGLE_COMPACT_LAYOUT)
                requiredActions.all { it in migrated } shouldBe true
                migrated.dynamicActions.size shouldBe requiredActions.size
            }
        }

        test("migrated output loads without applying migrations again") {
            runTest {
                val first = PreferenceFixture()
                first.load(
                    encodedPreferences(
                        """s;advanced__incognito_mode;"force_off"""",
                        """s;theme__editor_display_colors_as;"rgba"""",
                        """b;suggestion__clipboard_content_enabled;false""",
                    ),
                )

                val second = PreferenceFixture()
                second.load(first.exportRaw())

                second.prefs.suggestion.incognitoMode.get() shouldBe IncognitoMode.FORCE_OFF
                second.prefs.clipboard.suggestionEnabled.get() shouldBe false
                second.prefs.theme.editorColorRepresentation.get() shouldBe ColorRepresentation.RGB
                second.exportedKeys() shouldContainExactlyInAnyOrder first.exportedKeys()
            }
        }

        test("legacy HEX8 color display remains HEX") {
            runTest {
                val fixture = PreferenceFixture()
                fixture.load(encodedPreferences("""s;theme__editor_display_colors_as;"hex8""""))

                fixture.prefs.theme.editorColorRepresentation.get() shouldBe ColorRepresentation.HEX
                fixture.exportedKeys() shouldBe setOf("theme__editor_color_representation")
            }
        }
    })

private class PreferenceFixture {
    private val dataStore = jetprefDataStoreOf(FlorisPreferenceModel::class)
    val prefs by dataStore

    suspend fun load(raw: String, strategy: ImportStrategy = ImportStrategy.Erase) {
        dataStore.import(
            strategy = strategy,
            reader = DataStoreReader { raw },
        ).getOrThrow()
    }

    suspend fun exportRaw(): String {
        var raw = ""
        dataStore.export(DataStoreWriter { raw = it }).getOrThrow()
        return raw
    }

    suspend fun exportedKeys(): Set<String> = exportRaw()
        .lineSequence()
        .filterNot(String::isBlank)
        .map { line -> line.substringAfter(';').substringBefore(';') }
        .toSet()
}

private fun encodedPreferences(vararg lines: String): String = lines.joinToString(separator = "\n", postfix = "\n")

private val legacyLowercaseEnums = listOf(
    "gestures__swipe_up" to "no_action",
    "gestures__swipe_down" to "no_action",
    "gestures__swipe_left" to "no_action",
    "gestures__swipe_right" to "no_action",
    "gestures__space_bar_swipe_up" to "no_action",
    "gestures__space_bar_swipe_left" to "no_action",
    "gestures__space_bar_swipe_right" to "no_action",
    "gestures__space_bar_long_press" to "no_action",
    "gestures__delete_key_swipe_left" to "no_action",
    "gestures__delete_key_long_press" to "no_action",
    "keyboard__hinted_number_row_mode" to "smart_priority",
    "keyboard__hinted_symbols_mode" to "smart_priority",
    "keyboard__utility_key_action" to "dynamic_switch_language_emojis",
    "keyboard__landscape_input_ui_mode" to "dynamically_show",
    "localization__display_language_names_in" to "system_locale",
    "spelling__language_mode" to "use_keyboard_subtypes",
    "suggestion__display_mode" to "dynamic_scrollable",
    "theme__mode" to "follow_system",
    "theme__editor_display_kbd_after_dialogs" to "remember",
    "theme__editor_level" to "advanced",
)
