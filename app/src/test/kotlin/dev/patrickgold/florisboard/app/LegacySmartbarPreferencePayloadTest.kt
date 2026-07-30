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

import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LegacySmartbarPreferencePayloadTest :
    FunSpec({
        test("fixed-row defaults become a shared arrangement") {
            val processed = processSmartbar(
                rawPreferences(
                    """b;smartbar__secondary_row_enabled;true""",
                    stringPreference("smartbar__actions", "[]"),
                ),
                sourceVersionCode = 63,
            )

            migratedLayout(processed) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
            val arrangement = migratedArrangement(processed)
            arrangement.stickyAction.keyCode() shouldBe KeyCode.VOICE_INPUT
            arrangement.dynamicActions.keyCodes() shouldBe sharedQuickThenClipboardCodes
        }

        test("fixed-row expansion keeps stored order and puts clipboard tools first") {
            val storedOrder = legacyActionList(
                KeyCode.SETTINGS,
                KeyCode.COMPACT_LAYOUT_TO_RIGHT,
                KeyCode.UNDO,
                KeyCode.REDO,
                KeyCode.IME_UI_MODE_CLIPBOARD,
                KeyCode.IME_UI_MODE_MEDIA,
            )
            val processed = processSmartbar(
                rawPreferences(
                    """b;smartbar__secondary_row_expanded;true""",
                    stringPreference("smartbar__actions", storedOrder),
                ),
                sourceVersionCode = 69,
            )

            migratedLayout(processed) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
            migratedArrangement(processed).dynamicActions.keyCodes() shouldBe
                clipboardCodes + listOf(
                    KeyCode.SETTINGS,
                    KeyCode.TOGGLE_COMPACT_LAYOUT,
                    KeyCode.UNDO,
                    KeyCode.REDO,
                    KeyCode.IME_UI_MODE_MEDIA,
                )
        }

        test("typed rows preserve row selection and disabled secondary tools") {
            val processed = processSmartbar(
                rawPreferences(
                    stringPreference(
                        "smartbar__primary_actions_row_type",
                        "CLIPBOARD_CURSOR_TOOLS",
                    ),
                    stringPreference("smartbar__secondary_actions_row_type", "QUICK_ACTIONS"),
                    """b;smartbar__secondary_actions_enabled;false""",
                    stringPreference("smartbar__quick_actions", "[]"),
                ),
                sourceVersionCode = 70,
            )

            migratedLayout(processed) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
            val arrangement = migratedArrangement(processed)
            arrangement.dynamicActions.keyCodes() shouldBe clipboardCodes
            arrangement.hiddenActions.keyCodes() shouldContainAll legacyQuickCodes.filter {
                it != KeyCode.IME_UI_MODE_CLIPBOARD
            }
        }

        test("typed-row extended layout normalizes the retired compact action") {
            val processed = processSmartbar(
                rawPreferences(
                    """b;smartbar__secondary_actions_expanded;true""",
                    stringPreference(
                        "smartbar__quick_actions",
                        legacyActionList(
                            KeyCode.COMPACT_LAYOUT_TO_RIGHT,
                            KeyCode.SETTINGS,
                        ),
                    ),
                ),
                sourceVersionCode = 88,
            )

            migratedLayout(processed) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
            val codes = migratedArrangement(processed).dynamicActions.keyCodes()
            codes.take(clipboardCodes.size) shouldBe clipboardCodes
            codes shouldContain KeyCode.TOGGLE_COMPACT_LAYOUT
            codes shouldNotContain KeyCode.COMPACT_LAYOUT_TO_RIGHT
        }

        test("either canonical structural key blocks the whole family") {
            val malformedLayout = rawPreferences(
                """x;smartbar__layout;malformed""",
                """b;smartbar__secondary_row_expanded;true""",
            )
            processSmartbar(malformedLayout, sourceVersionCode = 69) shouldBe malformedLayout

            val arrangementOnly = rawPreferences(
                stringPreference("smartbar__action_arrangement", "{}"),
                stringPreference("smartbar__quick_actions", "[]"),
            )
            processSmartbar(arrangementOnly, sourceVersionCode = 88) shouldBe arrangementOnly
        }

        test("canonical scalar targets win over trailing legacy aliases") {
            val canonicalFirst = rawPreferences(
                """b;smartbar__flip_toggles;false""",
                """b;smartbar__primary_row_flip_toggles;true""",
                """b;smartbar__shared_actions_expanded;false""",
                """b;smartbar__primary_actions_expanded;true""",
                """b;smartbar__extended_actions_expanded;false""",
                """b;smartbar__secondary_row_expanded;true""",
                stringPreference("smartbar__extended_actions_placement", "ABOVE_CANDIDATES"),
                stringPreference("smartbar__secondary_actions_placement", "OVERLAY_APP_UI"),
            )
            val processed = processSmartbar(canonicalFirst, sourceVersionCode = 89)
            val wire = LegacyPreferencePayload.parse(processed)

            wire.lastBoolean("smartbar__flip_toggles") shouldBe false
            wire.lastBoolean("smartbar__shared_actions_expanded") shouldBe false
            wire.lastBoolean("smartbar__extended_actions_expanded") shouldBe false
            wire.lastString("smartbar__extended_actions_placement") shouldBe "ABOVE_CANDIDATES"
            processSmartbar(processed, sourceVersionCode = 89) shouldBe processed

            val canonicalLast = rawPreferences(
                """b;smartbar__primary_actions_expanded;true""",
                """b;smartbar__shared_actions_expanded;false""",
            )
            processSmartbar(canonicalLast, sourceVersionCode = 89) shouldBe canonicalLast

            val wrongTypeCanonical = rawPreferences(
                stringPreference("smartbar__flip_toggles", "false"),
                """b;smartbar__primary_row_flip_toggles;true""",
            )
            processSmartbar(wrongTypeCanonical, sourceVersionCode = 89) shouldBe
                wrongTypeCanonical
        }

        test("released code and version-name boundaries select the matching schema") {
            val mixed = rawPreferences(
                """b;smartbar__secondary_row_expanded;true""",
                """b;smartbar__secondary_actions_enabled;false""",
            )
            listOf(62, 89).forEach { versionCode ->
                processSmartbar(mixed, sourceVersionCode = versionCode) shouldBe mixed
            }
            listOf(63, 69).forEach { versionCode ->
                migratedLayout(
                    processSmartbar(mixed, sourceVersionCode = versionCode),
                ) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
            }
            listOf(70, 88).forEach { versionCode ->
                migratedLayout(
                    processSmartbar(mixed, sourceVersionCode = versionCode),
                ) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
            }

            listOf("0.3.14-beta06", "0.4.0-alpha03").forEach { versionName ->
                processSmartbar(mixed, sourceVersionName = versionName) shouldBe mixed
            }
            listOf("0.3.14-beta07", "0.3.14-beta13").forEach { versionName ->
                migratedLayout(
                    processSmartbar(mixed, sourceVersionName = versionName),
                ) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
            }
            listOf("0.3.14-beta14", "0.4.0-alpha02").forEach { versionName ->
                migratedLayout(
                    processSmartbar(mixed, sourceVersionName = versionName),
                ) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
            }

            migratedLayout(
                processSmartbar(
                    mixed,
                    sourceVersionCode = 89,
                    sourceVersionName = "0.3.14-beta13",
                ),
            ) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
        }

        test("install metadata only vetoes schemas which were already canonical") {
            listOf("0.0.0", "0.3.14-beta06").forEach { installVersion ->
                val installedBeforeActionRows = rawPreferences(
                    stringPreference("internal__version_on_install", installVersion),
                    """b;smartbar__secondary_row_expanded;true""",
                )
                migratedLayout(processSmartbar(installedBeforeActionRows)) shouldBe
                    SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
            }

            val installedCanonical = rawPreferences(
                stringPreference("internal__version_on_install", "0.4.0-alpha03"),
                """b;smartbar__secondary_row_expanded;true""",
            )
            processSmartbar(installedCanonical) shouldBe installedCanonical
        }

        test("unrecognized debug version names fall back to structural markers") {
            val fixedDebug = rawPreferences(
                """b;smartbar__secondary_row_expanded;true""",
            )
            migratedLayout(
                processSmartbar(
                    fixedDebug,
                    sourceVersionName = "0.3.14-debug-deadbeef",
                ),
            ) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED

            val typedDebug = rawPreferences(
                """b;smartbar__secondary_actions_enabled;false""",
            )
            migratedLayout(
                processSmartbar(
                    typedDebug,
                    sourceVersionName = "0.4.0-debug-deadbeef",
                ),
            ) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
        }

        test("typed markers win without metadata and each schema reads only its own aliases") {
            val mixed = rawPreferences(
                """b;smartbar__secondary_row_expanded;true""",
                """b;smartbar__secondary_actions_enabled;false""",
                stringPreference(
                    "smartbar__primary_actions_row_type",
                    "CLIPBOARD_CURSOR_TOOLS",
                ),
            )
            migratedArrangement(processSmartbar(mixed)).dynamicActions.keyCodes() shouldBe clipboardCodes

            val fixed = processSmartbar(mixed, sourceVersionCode = 69)
            migratedLayout(fixed) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
            migratedArrangement(fixed).dynamicActions.keyCodes().take(clipboardCodes.size) shouldBe clipboardCodes
        }

        test("last valid wire values survive malformed duplicates and old action fallback") {
            val processed = processSmartbar(
                rawPreferences(
                    """b;smartbar__secondary_actions_enabled;false""",
                    """b;smartbar__secondary_actions_enabled;malformed""",
                    stringPreference(
                        "smartbar__primary_actions_row_type",
                        "CLIPBOARD_CURSOR_TOOLS",
                    ),
                    """s;smartbar__primary_actions_row_type;not-json""",
                    stringPreference(
                        "smartbar__quick_actions",
                        "malformed-json",
                    ),
                    stringPreference(
                        "smartbar__actions",
                        legacyActionList(KeyCode.SETTINGS, KeyCode.UNDO),
                    ),
                ),
                sourceVersionCode = 70,
            )

            migratedLayout(processed) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
            migratedArrangement(processed).dynamicActions.keyCodes() shouldBe clipboardCodes
        }

        test("malformed legacy values safely reconstruct historical defaults") {
            val processed = processSmartbar(
                rawPreferences(
                    """b;smartbar__secondary_actions_enabled;maybe""",
                    stringPreference("smartbar__primary_actions_row_type", "UNKNOWN"),
                    stringPreference("smartbar__secondary_actions_row_type", "UNKNOWN"),
                    stringPreference("smartbar__quick_actions", "not-json"),
                ),
                sourceVersionCode = 70,
            )

            migratedLayout(processed) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
            migratedArrangement(processed).dynamicActions.keyCodes() shouldBe sharedQuickThenClipboardCodes
        }

        test("merge overlays legacy actions without discarding current-only state") {
            val baseArrangement = QuickActionArrangement(
                stickyAction = null,
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE),
                    QuickAction.InsertText("current-local-action"),
                    QuickAction.InsertKey(TextKeyData.UNDO),
                ),
                hiddenActions = listOf(
                    QuickAction.InsertKey(TextKeyData.LANGUAGE_SWITCH),
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_COPY),
                ),
            )
            val processed = LegacyPreferencePayloadPreprocessor.process(
                payload = rawPreferences(
                    stringPreference(
                        "smartbar__quick_actions",
                        legacyActionList(KeyCode.SETTINGS, KeyCode.UNDO),
                    ),
                ),
                baseSmartbar = LegacySmartbarMigrationBase(
                    layout = SmartbarLayout.ACTIONS_ONLY,
                    actionArrangement = baseArrangement,
                ),
                sourceVersionCode = 70,
            )

            migratedLayout(processed) shouldBe SmartbarLayout.ACTIONS_ONLY
            val arrangement = migratedArrangement(processed)
            arrangement.stickyAction shouldBe null
            arrangement.dynamicActions.take(2).keyCodes() shouldBe listOf(
                KeyCode.SETTINGS,
                KeyCode.UNDO,
            )
            arrangement.dynamicActions shouldContain QuickAction.InsertKey(
                TextKeyData.TOGGLE_INCOGNITO_MODE,
            )
            arrangement.dynamicActions shouldContain QuickAction.InsertText("current-local-action")
            arrangement.hiddenActions shouldContain QuickAction.InsertKey(TextKeyData.LANGUAGE_SWITCH)
        }

        test("merge preserves unrelated canonical Smartbar state") {
            val baseArrangement = QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE)),
                hiddenActions = listOf(QuickAction.InsertText("current-hidden-action")),
            )
            val base = LegacySmartbarMigrationBase(
                layout = SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED,
                actionArrangement = baseArrangement,
            )
            val enabledOnly = LegacyPreferencePayloadPreprocessor.process(
                payload = rawPreferences(
                    """b;smartbar__secondary_actions_enabled;true""",
                ),
                baseSmartbar = base,
                sourceVersionCode = 70,
            )
            migratedLayout(enabledOnly) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
            migratedArrangement(enabledOnly) shouldBe baseArrangement

            val scalarOnly = LegacyPreferencePayloadPreprocessor.process(
                payload = rawPreferences(
                    """b;smartbar__action_row_expanded;true""",
                ),
                baseSmartbar = base,
                sourceVersionCode = 69,
            )
            migratedLayout(scalarOnly) shouldBe base.layout
            migratedArrangement(scalarOnly) shouldBe baseArrangement

            val expandedOnly = LegacyPreferencePayloadPreprocessor.process(
                payload = rawPreferences(
                    """b;smartbar__secondary_actions_expanded;false""",
                ),
                baseSmartbar = base,
                sourceVersionCode = 70,
            )
            migratedLayout(expandedOnly) shouldBe SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
            migratedArrangement(expandedOnly) shouldBe baseArrangement
        }

        test("generated actions discard user payloads and are byte-idempotent") {
            val marker = "SENSITIVE_MARKER"
            val actions = buildJsonArray {
                add(legacyAction(KeyCode.UNDO, marker))
                add(
                    buildJsonObject {
                        put("$", "insert_text")
                        put("data", marker)
                    },
                )
                add(legacyAction(Int.MIN_VALUE, marker))
                add(legacyAction(KeyCode.UNDO, marker))
            }.toString()
            val original = rawPreferences(
                """b;smartbar__enabled;false""",
                stringPreference("smartbar__quick_actions", actions),
                """b;smartbar__secondary_actions_expanded;true""",
            )
            val processed = processSmartbar(original, sourceVersionCode = 88)

            LegacyPreferencePayload.parse(processed)
                .lastString("smartbar__action_arrangement") shouldNotContain marker
            migratedArrangement(processed).allActions().all {
                it is QuickAction.InsertKey
            } shouldBe true
            processSmartbar(processed, sourceVersionCode = 88) shouldBe processed
            LegacyPreferencePayload.parse(processed).lastBoolean("smartbar__enabled") shouldBe false
        }

        test("window and Smartbar families never block each other") {
            val canonicalWindow = rawPreferences(
                """x;keyboard__window_config;malformed""",
                stringPreference("smartbar__actions", "[]"),
            )
            val smartbarProcessed = processSmartbar(canonicalWindow, sourceVersionCode = 63)
            LegacyPreferencePayload.parse(smartbarProcessed).hasKey("smartbar__layout") shouldBe true

            val canonicalSmartbar = rawPreferences(
                """x;smartbar__layout;malformed""",
                """i;keyboard__height_factor_portrait;120""",
            )
            val windowProcessed = processSmartbar(canonicalSmartbar, sourceVersionCode = 63)
            LegacyPreferencePayload.parse(windowProcessed).hasKey("keyboard__window_config") shouldBe true
        }
    })

private fun processSmartbar(
    payload: String,
    sourceVersionCode: Int? = null,
    sourceVersionName: String? = null,
): String = LegacyPreferencePayloadPreprocessor.process(
    payload = payload,
    sourceVersionCode = sourceVersionCode,
    sourceVersionName = sourceVersionName,
)

private fun migratedLayout(payload: String): SmartbarLayout = SmartbarLayout.valueOf(
    requireNotNull(LegacyPreferencePayload.parse(payload).lastString("smartbar__layout")),
)

private fun migratedArrangement(payload: String): QuickActionArrangement =
    QuickActionArrangement.Serializer.deserialize(
        requireNotNull(
            LegacyPreferencePayload.parse(payload).lastString("smartbar__action_arrangement"),
        ),
    )

private fun stringPreference(key: String, value: String): String = "s;$key;${Json.encodeToString(value)}"

private fun rawPreferences(vararg lines: String): String = lines.joinToString(separator = "\n", postfix = "\n")

private fun legacyActionList(vararg codes: Int): String = buildJsonArray {
    codes.forEach { add(legacyAction(it, "legacy")) }
}.toString()

private fun legacyAction(code: Int, label: String) = buildJsonObject {
    put("$", "key")
    put(
        "data",
        buildJsonObject {
            put("$", "text_key")
            put("type", "SYSTEM_GUI")
            put("code", code)
            put("label", label)
        },
    )
}

private fun QuickAction?.keyCode(): Int? = (this as? QuickAction.InsertKey)?.data?.code

private fun List<QuickAction>.keyCodes(): List<Int> = mapNotNull {
    (it as? QuickAction.InsertKey)?.data?.code
}

private fun QuickActionArrangement.allActions(): List<QuickAction> = buildList {
    stickyAction?.let(::add)
    addAll(dynamicActions)
    addAll(hiddenActions)
}

private val legacyQuickCodes = listOf(
    KeyCode.UNDO,
    KeyCode.REDO,
    KeyCode.SETTINGS,
    KeyCode.IME_UI_MODE_MEDIA,
    KeyCode.TOGGLE_COMPACT_LAYOUT,
    KeyCode.IME_UI_MODE_CLIPBOARD,
)

private val clipboardCodes = listOf(
    KeyCode.CLIPBOARD_SELECT_ALL,
    KeyCode.CLIPBOARD_COPY,
    KeyCode.CLIPBOARD_CUT,
    KeyCode.ARROW_LEFT,
    KeyCode.ARROW_RIGHT,
    KeyCode.CLIPBOARD_PASTE,
    KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP,
    KeyCode.IME_UI_MODE_CLIPBOARD,
)

private val sharedQuickThenClipboardCodes =
    legacyQuickCodes + clipboardCodes.filterNot(legacyQuickCodes::contains)
