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

package dev.patrickgold.florisboard.app.settings.advanced

import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest

class RestorePreferencePreflightTest :
    FunSpec({
        val root = Files.createTempDirectory("restore-preference-preflight-test")

        afterSpec {
            root.toFile().deleteRecursively()
        }

        test("Merge produces a canonical candidate without leaking prior shadow state") {
            runTest {
                val firstSnapshot = root.writePreferences(
                    "first-snapshot.jetpref",
                    "b;clipboard__use_internal_clipboard;false",
                    "b;clipboard__history_enabled;false",
                )
                val firstIncoming = root.writePreferences(
                    "first-incoming.jetpref",
                    "b;clipboard__use_internal_clipboard;true",
                )
                val firstCandidate = root.resolve("first-candidate.jetpref")

                RestorePreferencePreflight.prepare(
                    stagedSource = firstIncoming,
                    snapshot = firstSnapshot,
                    canonicalDestination = firstCandidate,
                    strategy = ImportStrategy.Merge,
                    sourceVersionCode = null,
                    sourceVersionName = null,
                )

                firstCandidate.preferenceValues().let { values ->
                    values["clipboard__use_internal_clipboard"] shouldBe "true"
                    values["clipboard__history_enabled"] shouldBe "false"
                }

                val secondSnapshot = root.writePreferences(
                    "second-snapshot.jetpref",
                    "b;clipboard__use_internal_clipboard;false",
                    "b;clipboard__history_enabled;true",
                )
                val secondIncoming = root.writePreferences(
                    "second-incoming.jetpref",
                    "b;clipboard__sync_to_system;false",
                )
                val secondCandidate = root.resolve("second-candidate.jetpref")

                RestorePreferencePreflight.prepare(
                    stagedSource = secondIncoming,
                    snapshot = secondSnapshot,
                    canonicalDestination = secondCandidate,
                    strategy = ImportStrategy.Merge,
                    sourceVersionCode = null,
                    sourceVersionName = null,
                )

                secondCandidate.preferenceValues().let { values ->
                    values["clipboard__use_internal_clipboard"] shouldBe "false"
                    values["clipboard__history_enabled"] shouldBe "true"
                    values["clipboard__sync_to_system"] shouldBe "\"NO_EVENTS\""
                }
            }
        }
    })

private fun Path.writePreferences(
    fileName: String,
    vararg lines: String,
): Path = resolve(fileName).also { path ->
    Files.write(path, lines.joinToString(separator = "\n", postfix = "\n").toByteArray())
}

private fun Path.preferenceValues(): Map<String, String> =
    String(Files.readAllBytes(this))
        .lineSequence()
        .filter(String::isNotBlank)
        .associate { line ->
            val fields = line.split(';', limit = 3)
            fields[1] to fields[2]
        }
