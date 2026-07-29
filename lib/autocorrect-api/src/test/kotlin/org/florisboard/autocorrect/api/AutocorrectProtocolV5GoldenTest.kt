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

package org.florisboard.autocorrect.api

import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutocorrectProtocolV5GoldenTest {
    @Test
    fun currentConstantsEnumsAndWireShapesMatchTheV5Fixture() {
        val actual = buildGoldenFixture()
        writeReport("protocol-v5.golden", actual)
        if (updateSnapshot("protocol-v5.golden", actual)) return
        val expected = requireNotNull(javaClass.getResource("/api/protocol-v5.golden")) {
            "Missing src/test/resources/api/protocol-v5.golden"
        }.readText().trimEnd()

        assertEquals(
            "Protocol v5 changed. Keep old wire identifiers stable; use a new protocol version " +
                "for incompatible changes. The generated candidate is in the module build reports.",
            expected,
            actual,
        )
    }

    @Suppress("LongMethod")
    private fun buildGoldenFixture(): String {
        val contractFields = AutocorrectPluginContract::class.java.fields
            .filter {
                it.name.startsWith("ACTION_") ||
                    it.name.startsWith("META_") ||
                    it.name.startsWith("MSG_") ||
                    it.name.startsWith("MAX_") ||
                    it.name == "PROTOCOL_VERSION"
            }
            .sortedBy { it.name }
            .joinToString("\n") { field ->
                "contract.${field.name}=${field.get(null)}"
            }
        val enums = listOf(
            AutocorrectAcceptanceKind::class.java,
            AutocorrectCandidateKind::class.java,
            AutocorrectCapsMode::class.java,
            AutocorrectInputMode::class.java,
            AutocorrectPluginHostSetting::class.java,
            AutocorrectPluginUiIcon::class.java,
            AutocorrectPluginUiItemKind::class.java,
            AutocorrectPluginUiSurface::class.java,
            AutocorrectSeparatorBehavior::class.java,
            AutocorrectTextEventKind::class.java,
            AutocorrectUserDictionaryOperation::class.java,
            AutocorrectUserDictionaryStatus::class.java,
        ).joinToString("\n") { type ->
            "enum.${type.simpleName}=" + type.enumConstants.orEmpty().joinToString(",") {
                (it as Enum<*>).name
            }
        }

        val trace = AutocorrectInputTrace(
            keys = listOf(AutocorrectKeyGeometry("a", 0.1f, 0.2f, 0.3f, 0.4f)),
            points = listOf(AutocorrectTouchPoint("a", 0.25f, 0.75f)),
            gesturePoints = listOf(AutocorrectGesturePoint(0.5f, 1f, 17)),
            mode = AutocorrectInputMode.GESTURE,
        )
        val request = AutocorrectRequest(
            sessionId = 7L,
            requestId = 11L,
            text = "word",
            selectionStart = 4,
            selectionEnd = 4,
            composingStart = 0,
            composingEnd = 4,
            currentWordStart = 0,
            currentWordEnd = 4,
            maxCandidateCount = 3,
            allowPossiblyOffensive = true,
            inputTrace = trace,
            capsMode = AutocorrectCapsMode.SHIFTED_AUTOMATIC,
        )
        val session = AutocorrectSession(
            sessionId = 7L,
            primaryLanguageTag = "en-US",
            secondaryLanguageTags = listOf("de-DE"),
            inputType = 1,
            capsMode = 2,
            allowPersonalizedLearning = true,
            editorFlags = AutocorrectEditorFlags.CODE_LIKE or AutocorrectEditorFlags.WEB_FIELD,
            preferredEmojiSkinToneModifier = 0x1F3FD,
        )
        val candidate = AutocorrectCandidate(
            id = "candidate-1",
            text = "word",
            secondaryText = "noun",
            confidence = 0.75,
            kind = AutocorrectCandidateKind.CORRECTION,
            autoCommit = true,
            removable = true,
            visible = true,
            replacementStart = 0,
            replacementEnd = 4,
            separatorBehavior = AutocorrectSeparatorBehavior.INSERT,
        )
        val pluginUi = AutocorrectPluginUi(
            appRootPageId = "root",
            keyboardRootPageId = "root",
            pages = listOf(
                AutocorrectPluginUiPage(
                    id = "root",
                    title = "Provider",
                    summary = "Settings",
                    surface = AutocorrectPluginUiSurface.BOTH,
                    items = listOf(
                        AutocorrectPluginUiItem(
                            id = "mode",
                            kind = AutocorrectPluginUiItemKind.CHOICE,
                            title = "Mode",
                            summary = "Prediction mode",
                            value = "balanced",
                            options = listOf(
                                AutocorrectPluginUiOption("fast", "Fast"),
                                AutocorrectPluginUiOption("balanced", "Balanced"),
                            ),
                            icon = AutocorrectPluginUiIcon.TUNE,
                            confirmation = "Apply?",
                        ),
                    ),
                ),
            ),
        )
        val dictionaryEntry = AutocorrectUserDictionaryEntry(
            id = 19L,
            word = "FlorisBoard",
            frequency = 200,
            languageTag = "en-US",
            shortcut = "fb",
        )

        val pipe = ParcelFileDescriptor.createPipe()
        val bundles = try {
            linkedMapOf(
                "startSession" to session.toBundle(),
                "suggest" to request.toBundle(),
                "suggestions" to suggestionResultToBundle(
                    11L,
                    AutocorrectSuggestionResult(
                        candidates = listOf(candidate),
                        boostedCodePoints = linkedSetOf('a'.code, 0x1F600),
                    ),
                ),
                "candidateAccepted" to candidateEventBundle(
                    7L,
                    "candidate-1",
                    AutocorrectAcceptanceKind.AUTO_CORRECTION,
                ),
                "candidateReverted" to candidateEventBundle(7L, "candidate-1"),
                "remove" to removalRequestBundle(7L, 12L, "candidate-1"),
                "removeResult" to Bundle().apply {
                    putLong("requestId", 12L)
                    putBoolean("removed", true)
                },
                "finishLegacy" to finishSessionBundle(7L),
                "finishWithSnapshot" to finishSessionBundle(7L, request),
                "cancel" to cancellationBundle(11L),
                "textEvent" to AutocorrectTextEvent(
                    7L,
                    "word",
                    AutocorrectTextEventKind.COMMIT_TYPED,
                ).toBundle(),
                "pluginUiRequest" to pluginUiRequestBundle(20L, listOf("en-US", "de-DE")),
                "pluginUiMutation" to pluginUiMutationBundle(21L, "mode", "balanced"),
                "pluginUiAction" to pluginUiMutationBundle(22L, "download"),
                "pluginUiDocument" to pluginUiDocumentBundle(
                    requestId = 23L,
                    itemId = "model",
                    displayName = "model.bin",
                    mimeType = "application/octet-stream",
                    write = false,
                    fileDescriptor = pipe[0],
                ),
                "pluginUiResult" to pluginUiResultBundle(20L, true, pluginUi),
                "pluginUiClosed" to Bundle(),
                "dictionaryQuery" to userDictionaryQueryBundle(30L, listOf("en-US"), 9L, 32),
                "dictionaryUpsert" to userDictionaryUpsertBundle(31L, 20L, dictionaryEntry),
                "dictionaryDelete" to userDictionaryDeleteBundle(32L, 20L, 19L),
                "dictionaryResult" to userDictionaryResultBundle(
                    30L,
                    AutocorrectUserDictionaryStatus.OK,
                    listOf(dictionaryEntry),
                    nextAfterId = 19L,
                ),
            )
        } finally {
            pipe[1].close()
        }

        return buildString {
            appendLine("# Autocorrect plugin protocol v5")
            appendLine(contractFields)
            appendLine(enums)
            bundles.forEach { (name, bundle) ->
                appendLine("bundle.$name=${bundle.canonicalValue()}")
            }
        }.trimEnd().also {
            pipe[0].close()
        }
    }

    private fun Bundle.canonicalValue(indent: String = ""): String {
        @Suppress("DEPRECATION")
        val entries = keySet().sorted().map { key -> key to get(key) }
        if (entries.isEmpty()) return "Bundle{}"
        val childIndent = "$indent  "
        return buildString {
            appendLine("Bundle{")
            entries.forEach { (key, value) ->
                append(childIndent)
                append(key)
                append('=')
                append(value.canonicalValue(childIndent))
                appendLine()
            }
            append(indent)
            append('}')
        }
    }

    private fun Any?.canonicalValue(indent: String): String = when (this) {
        null -> "null"
        is Bundle -> canonicalValue(indent)
        is List<*> -> if (isEmpty()) {
            "List[]"
        } else {
            val values = this
            buildString {
                appendLine("List[")
                values.forEach { value ->
                    append("$indent  ")
                    append(value.canonicalValue("$indent  "))
                    appendLine()
                }
                append(indent)
                append(']')
            }
        }
        is IntArray -> joinToString(prefix = "IntArray[", postfix = "]")
        is String -> "String(\"${replace("\\", "\\\\").replace("\"", "\\\"")}\")"
        is Long -> "Long($this)"
        is Int -> "Int($this)"
        is Boolean -> "Boolean($this)"
        is Float -> "Float($this)"
        is Double -> "Double($this)"
        is ParcelFileDescriptor -> "ParcelFileDescriptor"
        else -> error("Unsupported golden wire value ${javaClass.name}")
    }

    private fun writeReport(name: String, contents: String) {
        val reportDir = System.getProperty("autocorrect.api.reportDir") ?: return
        File(reportDir).mkdirs()
        File(reportDir, name).writeText("$contents\n")
    }

    private fun updateSnapshot(name: String, contents: String): Boolean {
        val snapshotDir = System.getProperty("autocorrect.api.snapshotDir") ?: return false
        File(snapshotDir).mkdirs()
        File(snapshotDir, name).writeText("$contents\n")
        return true
    }
}
