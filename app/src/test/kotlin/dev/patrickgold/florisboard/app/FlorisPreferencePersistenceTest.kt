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
import dev.patrickgold.florisboard.ime.window.ImeFormFactor
import dev.patrickgold.florisboard.ime.window.ImeWindowConfig
import dev.patrickgold.florisboard.ime.window.ImeWindowMode
import dev.patrickgold.florisboard.ime.window.ImeWindowProps
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.runtime.DataStoreReader
import dev.patrickgold.jetpref.datastore.runtime.DataStoreWriter
import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

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
                fixture.exportRaw().lineSequence()
                    .filterNot(String::isBlank)
                    .map { it.substringAfter(';').substringBefore(';') }
                    .toSet() shouldBe setOf("keyboard__window_config")
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

    suspend fun importFailure(): Result<Unit> = dataStore.importWithLegacyMigrations(
        strategy = ImportStrategy.Erase,
        reader = DataStoreReader { error("synthetic") },
    )
}

private fun persistencePayload(vararg lines: String): String = lines.joinToString(separator = "\n", postfix = "\n")
