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

import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.smartbar.ExtendedActionsPlacement
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.window.ImeFormFactor
import dev.patrickgold.florisboard.ime.window.ImeWindowConfig
import dev.patrickgold.florisboard.ime.window.ImeWindowMode
import dev.patrickgold.florisboard.ime.window.ImeWindowProps
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.runtime.DataStoreReader
import dev.patrickgold.jetpref.datastore.runtime.DataStoreWriter
import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FlorisPreferencePersistenceTest :
    FunSpec({
        test("legacy payload imports through the current JetPref model") {
            runTest {
                val fixture = PersistenceFixture()
                fixture.import(
                    strategy = ImportStrategy.Erase,
                    raw = persistencePayload(
                        """i;keyboard__height_factor_portrait;120""",
                        """s;keyboard__one_handed_mode;"START"""",
                    ),
                    sourceVersionCode = 104,
                )

                val config = fixture.prefs.keyboard.windowConfig.get()
                config.keys shouldBe setOf(
                    ImeFormFactor.Type.PHONE_PORTRAIT,
                    ImeFormFactor.Type.TABLET_PORTRAIT,
                )
                config.values.all { it.fixedMode == ImeWindowMode.Fixed.COMPACT } shouldBe true
                fixture.exportedKeys() shouldBe setOf("keyboard__window_config")
            }
        }

        test("Merge preserves current form factors outside the legacy payload") {
            runTest {
                val fixture = PersistenceFixture()
                val desktop = ImeWindowConfig(
                    mode = ImeWindowMode.FLOATING,
                    floatingProps = mapOf(
                        ImeWindowMode.Floating.NORMAL to ImeWindowProps.Floating(
                            keyboardHeight = 200.dp,
                            keyboardWidth = 300.dp,
                            offsetLeft = 10.dp,
                            offsetBottom = 20.dp,
                        ),
                    ),
                )
                fixture.prefs.keyboard.windowConfig.set(mapOf(ImeFormFactor.Type.DESKTOP to desktop))

                fixture.import(
                    strategy = ImportStrategy.Merge,
                    raw = persistencePayload("""i;keyboard__height_factor_portrait;120"""),
                    sourceVersionCode = 104,
                )

                val config = fixture.prefs.keyboard.windowConfig.get()
                config.getValue(ImeFormFactor.Type.DESKTOP) shouldBe desktop
                config.containsKey(ImeFormFactor.Type.PHONE_PORTRAIT) shouldBe true
                config.containsKey(ImeFormFactor.Type.TABLET_PORTRAIT) shouldBe true
            }
        }

        test("Erase removes current-only form factors before applying legacy state") {
            runTest {
                val fixture = PersistenceFixture()
                fixture.prefs.keyboard.windowConfig.set(
                    mapOf(ImeFormFactor.Type.DESKTOP to ImeWindowConfig.Default),
                )

                fixture.import(
                    strategy = ImportStrategy.Erase,
                    raw = persistencePayload("""i;keyboard__height_factor_landscape;110"""),
                    sourceVersionCode = 104,
                )

                fixture.prefs.keyboard.windowConfig.get().keys shouldBe setOf(
                    ImeFormFactor.Type.PHONE_LANDSCAPE,
                    ImeFormFactor.Type.TABLET_LANDSCAPE,
                    ImeFormFactor.Type.LARGE_TABLET,
                )
            }
        }

        test("canonical compound values retain normal whole-value Merge semantics") {
            runTest {
                val fixture = PersistenceFixture()
                fixture.prefs.keyboard.windowConfig.set(
                    mapOf(ImeFormFactor.Type.DESKTOP to ImeWindowConfig.Default),
                )
                val incoming = mapOf(
                    ImeFormFactor.Type.PHONE_PORTRAIT to ImeWindowConfig(
                        mode = ImeWindowMode.FIXED,
                        fixedMode = ImeWindowMode.Fixed.COMPACT,
                    ),
                )
                val serialized = ImeWindowConfig.ByTypeSerializer.serialize(incoming)
                fixture.import(
                    strategy = ImportStrategy.Merge,
                    raw = persistencePayload(
                        "s;keyboard__window_config;${Json.encodeToString(serialized)}",
                        """i;keyboard__height_factor_landscape;150""",
                    ),
                    sourceVersionCode = 104,
                )

                fixture.prefs.keyboard.windowConfig.get() shouldBe incoming
            }
        }

        test("orphaned preference state imports without surviving canonical export") {
            runTest {
                val fixture = PersistenceFixture()
                fixture.import(
                    strategy = ImportStrategy.Erase,
                    raw = persistencePayload(
                        """b;emoji__history_enabled;false""",
                        persistenceStringPreference("emoji__preferred_hair_style", "CURLY_HAIR"),
                        persistenceStringPreference("media__emoji_preferred_hair_style", "curly_hair"),
                        """b;input_feedback__audio_feat_gesture_swipe;true""",
                        """b;input_feedback__haptic_feat_gesture_swipe;true""",
                        """b;spelling__use_contacts;false""",
                        """b;spelling__use_udm_entries;false""",
                    ),
                    sourceVersionCode = 88,
                )

                fixture.prefs.emoji.historyEnabled.get() shouldBe false
                fixture.exportedKeys() shouldBe setOf("emoji__history_enabled")
            }
        }

        test("legacy Smartbar state imports through the current model without payload data") {
            runTest {
                val fixture = PersistenceFixture()
                val marker = "SENSITIVE_MARKER"
                fixture.import(
                    strategy = ImportStrategy.Erase,
                    raw = persistencePayload(
                        """b;smartbar__enabled;false""",
                        """b;smartbar__action_row_auto_expand_collapse;false""",
                        """b;smartbar__primary_actions_auto_expand_collapse;false""",
                        """b;smartbar__shared_actions_auto_expand_collapse;false""",
                        """b;smartbar__secondary_actions_expanded;true""",
                        persistenceStringPreference(
                            "smartbar__quick_actions",
                            persistenceLegacyActions(marker),
                        ),
                    ),
                    sourceVersionCode = 88,
                )

                fixture.prefs.smartbar.enabled.get() shouldBe false
                fixture.prefs.smartbar.layout.get() shouldBe
                    SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
                val arrangement = fixture.prefs.smartbar.actionArrangement.get()
                arrangement.stickyAction shouldBe QuickAction.InsertKey(TextKeyData.VOICE_INPUT)
                arrangement.dynamicActions.all { it is QuickAction.InsertKey } shouldBe true
                QuickActionArrangement.Serializer.serialize(arrangement).contains(marker) shouldBe false

                fixture.exportedKeys() shouldBe setOf(
                    "smartbar__enabled",
                    "smartbar__layout",
                    "smartbar__action_arrangement",
                    "smartbar__extended_actions_expanded",
                )
            }
        }

        test("Smartbar Merge keeps current-only actions and layout") {
            runTest {
                val fixture = PersistenceFixture()
                fixture.prefs.smartbar.layout.set(SmartbarLayout.ACTIONS_ONLY)
                fixture.prefs.smartbar.actionArrangement.set(
                    QuickActionArrangement(
                        stickyAction = null,
                        dynamicActions = listOf(
                            QuickAction.InsertText("current-local-action"),
                            QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE),
                            QuickAction.InsertKey(TextKeyData.UNDO),
                        ),
                        hiddenActions = emptyList(),
                    ),
                )

                fixture.import(
                    strategy = ImportStrategy.Merge,
                    raw = persistencePayload(
                        persistenceStringPreference(
                            "smartbar__quick_actions",
                            persistenceLegacyActions("UNTRUSTED_INCOMING_ACTION"),
                        ),
                    ),
                    sourceVersionCode = 70,
                )

                fixture.prefs.smartbar.layout.get() shouldBe SmartbarLayout.ACTIONS_ONLY
                val actions = fixture.prefs.smartbar.actionArrangement.get().dynamicActions
                actions shouldContain QuickAction.InsertText("current-local-action")
                actions shouldContain QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE)
                actions.filterIsInstance<QuickAction.InsertText>() shouldBe
                    listOf(QuickAction.InsertText("current-local-action"))
            }
        }

        test("canonical Smartbar scalars beat aliases regardless of payload order") {
            runTest {
                val fixture = PersistenceFixture()
                fixture.import(
                    strategy = ImportStrategy.Erase,
                    raw = persistencePayload(
                        """b;smartbar__flip_toggles;false""",
                        """b;smartbar__primary_row_flip_toggles;true""",
                        """b;smartbar__shared_actions_expanded;false""",
                        """b;smartbar__action_row_expanded;true""",
                        """b;smartbar__extended_actions_expanded;false""",
                        """b;smartbar__secondary_actions_expanded;true""",
                        persistenceStringPreference(
                            "smartbar__extended_actions_placement",
                            "ABOVE_CANDIDATES",
                        ),
                        persistenceStringPreference(
                            "smartbar__secondary_actions_placement",
                            "OVERLAY_APP_UI",
                        ),
                    ),
                    sourceVersionCode = 89,
                )

                fixture.prefs.smartbar.flipToggles.get() shouldBe false
                fixture.prefs.smartbar.sharedActionsExpanded.get() shouldBe false
                fixture.prefs.smartbar.extendedActionsExpanded.get() shouldBe false
                fixture.prefs.smartbar.extendedActionsPlacement.get() shouldBe
                    ExtendedActionsPlacement.ABOVE_CANDIDATES
            }
        }

        test("reader failures remain typed Result failures") {
            runTest {
                val fixture = PersistenceFixture()

                fixture.importFailure().isFailure shouldBe true
            }
        }
    })

private class PersistenceFixture {
    private val dataStore = jetprefDataStoreOf(FlorisPreferenceModel::class)
    val prefs by dataStore

    suspend fun import(strategy: ImportStrategy, raw: String, sourceVersionCode: Int?) {
        dataStore.importWithLegacyMigrations(
            strategy = strategy,
            reader = DataStoreReader { raw },
            sourceVersionCode = sourceVersionCode,
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
        .map { it.substringAfter(';').substringBefore(';') }
        .toSet()

    suspend fun importFailure(): Result<Unit> = dataStore.importWithLegacyMigrations(
        strategy = ImportStrategy.Erase,
        reader = DataStoreReader { error("synthetic") },
    )
}

private fun persistencePayload(vararg lines: String): String = lines.joinToString(separator = "\n", postfix = "\n")

private fun persistenceStringPreference(key: String, value: String): String = "s;$key;${Json.encodeToString(value)}"

private fun persistenceLegacyActions(marker: String): String = buildJsonArray {
    add(
        buildJsonObject {
            put("$", "key")
            put(
                "data",
                buildJsonObject {
                    put("$", "text_key")
                    put("code", KeyCode.UNDO)
                    put("label", marker)
                },
            )
        },
    )
    add(
        buildJsonObject {
            put("$", "insert_text")
            put("data", marker)
        },
    )
}.toString()
