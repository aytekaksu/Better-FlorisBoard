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

package dev.patrickgold.florisboard.app.devtools

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.PreferenceStoreInitializationState
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.keyboard.CachedLayout
import dev.patrickgold.florisboard.ime.keyboard.DebugLayoutComputationResult
import dev.patrickgold.florisboard.ime.nlp.NlpInlineAutofill
import dev.patrickgold.florisboard.ime.theme.ThemeManager
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.themeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.snygg.SnyggMissingSchemaException

private val CardBackground = Color.Black.copy(0.6f)

@Composable
fun DevtoolsOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val appContext by context.appContext()
    val keyboardManager by context.keyboardManager()
    val themeManager by context.themeManager()

    val devtoolsEnabled by prefs.devtools.enabled.collectAsState()
    val showPrimaryClip by prefs.devtools.showPrimaryClip.collectAsState()
    val showInputStateOverlay by prefs.devtools.showInputStateOverlay.collectAsState()
    val showSpellingOverlay by prefs.devtools.showSpellingOverlay.collectAsState()
    val showInlineAutofillOverlay by prefs.devtools.showInlineAutofillOverlay.collectAsState()
    val preferenceStoreInitializationState by
        appContext.preferenceStoreInitializationState.collectAsState()

    val debugLayoutResult by keyboardManager.layoutManager.debugLayoutComputationResultFlow.collectAsState()
    val themeInfo by themeManager.activeThemeInfo.collectAsState()

    CompositionLocalProvider(
        LocalContentColor provides Color.White,
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            if (devtoolsEnabled && showPrimaryClip) {
                DevtoolsClipboardOverlay()
            }
            if (devtoolsEnabled && showInputStateOverlay) {
                DevtoolsInputStateOverlay()
            }
            if (debugLayoutResult?.allLayoutsSuccess() == false) {
                DevtoolsLastLayoutComputationOverlay(debugLayoutResult)
            }
            if (devtoolsEnabled && showSpellingOverlay) {
                DevtoolsSpellingOverlay()
            }
            if (devtoolsEnabled && showInlineAutofillOverlay && AndroidVersion.ATLEAST_API30_R) {
                DevtoolsInlineAutofillOverlay()
            }
            val loadFailure = themeInfo.loadFailure
            if (
                loadFailure != null &&
                preferenceStoreInitializationState == PreferenceStoreInitializationState.READY
            ) {
                DevtoolsStylesheetFailedToLoadOverlay(loadFailure)
            }
        }
    }
}

@Composable
private fun DevtoolsClipboardOverlay() {
    val context = LocalContext.current
    val clipboardManager by context.clipboardManager()

    DevtoolsOverlayBox(title = "Clipboard overlay") {
        val primaryClip by clipboardManager.primaryClipFlow.collectAsState()
        val lines = primaryClip?.let { clip ->
            clipboardDebugLines(
                type = when (clip.type) {
                    ItemType.TEXT -> ClipboardDebugType.TEXT
                    ItemType.IMAGE -> ClipboardDebugType.IMAGE
                    ItemType.VIDEO -> ClipboardDebugType.VIDEO
                },
                isPinned = clip.isPinned,
                isSensitive = clip.isSensitive,
                isRemoteDevice = clip.isRemoteDevice,
                mimeTypes = clip.mimeTypes,
            )
        } ?: clipboardDebugLines()
        lines.forEach { line ->
            DevtoolsText(text = line)
        }
    }
}

@Composable
private fun DevtoolsInputStateOverlay() {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()

    val content by editorInstance.activeContentFlow.collectAsState()
    val lines = editorDebugLines(
        cachedText = content.text,
        textBeforeSelection = content.textBeforeSelection,
        selectedText = content.selectedText,
        textAfterSelection = content.textAfterSelection,
        composingText = content.composingText,
        currentWordText = content.currentWordText,
        selectionIsValid = content.localSelection.isValid,
        composingIsValid = content.localComposing.isValid,
        currentWordIsValid = content.localCurrentWord.isValid,
        lastCommitKnown = editorInstance.lastCommitPosition.pos >= 0,
    )

    DevtoolsOverlayBox(title = "Input state overlay") {
        DevtoolsSubGroup(title = "Editor content") {
            lines.forEach { line ->
                DevtoolsText(text = line)
            }
        }
    }
}

@Composable
private fun DevtoolsLastLayoutComputationOverlay(debugLayoutResult: DebugLayoutComputationResult?) {
    @Composable
    fun PrintResult(result: Result<CachedLayout?>) {
        if (result.isSuccess) {
            DevtoolsText(text = "loaded: ${result.getOrNull()?.name}")
        } else {
            DevtoolsText(text = "error: ${failureClassName(result.exceptionOrNull())}")
        }
    }

    DevtoolsOverlayBox(title = "Last layout computation") {
        if (debugLayoutResult == null) {
            DevtoolsText(text = "No layout computation result available.")
            return@DevtoolsOverlayBox
        }
        DevtoolsSubGroup(title = "main") {
            PrintResult(debugLayoutResult.main)
        }
        DevtoolsSubGroup(title = "mod") {
            PrintResult(debugLayoutResult.mod)
        }
        DevtoolsSubGroup(title = "ext") {
            PrintResult(debugLayoutResult.ext)
        }
    }
}

@Composable
private fun DevtoolsSpellingOverlay() {
    val context = LocalContext.current
    val nlpManager by context.nlpManager()

    val diagnosticsVersion by nlpManager.spellingDiagnosticsVersion.collectAsState()
    val snapshot = remember(diagnosticsVersion) { nlpManager.spellingDiagnosticsSnapshot() }
    val records = snapshot.records.asReversed()

    DevtoolsOverlayBox(title = "Spelling overlay (${records.size})") {
        DevtoolsText(text = "Dropped records: ${snapshot.droppedRecordCount}")
        for (record in records) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(
                    text = "Request #${record.sequence}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Text(
                    text = "  State: ${record.state.name.lowercase()} | Suggestions: ${record.suggestionCount}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun DevtoolsInlineAutofillOverlay() {
    val inlineSuggestions by NlpInlineAutofill.suggestions.collectAsState()

    DevtoolsOverlayBox(title = "Inline autofill overlay (${inlineSuggestions.size})") {
        for (inlineSuggestion in inlineSuggestions) {
            DevtoolsSubGroup(title = "NlpInlineSuggestion") {
                val info = inlineSuggestion.info
                DevtoolsText(text = "info.type:     ${info.type}")
                DevtoolsText(text = "info.source:   ${info.source}")
                DevtoolsText(text = "info.isPinned: ${info.isPinned}")
                val view = inlineSuggestion.view
                DevtoolsText(text = "view: ${view?.javaClass?.name}")
            }
        }
    }
}

@Composable
private fun DevtoolsStylesheetFailedToLoadOverlay(loadFailure: ThemeManager.LoadFailure) {
    DevtoolsOverlayBox(title = "Failed to load stylesheet, fell back to base style") {
        DevtoolsSubGroup(title = "Extension") {
            DevtoolsText(text = "id:       ${loadFailure.extension.id}")
            DevtoolsText(text = "title:    ${loadFailure.extension.title}")
            DevtoolsText(text = "version:  ${loadFailure.extension.version}")
        }
        DevtoolsSubGroup(title = "Component") {
            DevtoolsText(text = "id:       ${loadFailure.component.id}")
            DevtoolsText(text = "label:    ${loadFailure.component.label}")
            DevtoolsText(text = "path:     ${loadFailure.component.stylesheetPath()}")
        }
        val cause = loadFailure.cause
        DevtoolsSubGroup(title = "Cause") {
            DevtoolsText(text = failureClassName(cause))
        }
        if (cause is SnyggMissingSchemaException) {
            DevtoolsSubGroup(title = "Explanation") {
                DevtoolsText(
                    text = """
                    It appears you’re trying to load a theme designed for FlorisBoard v0.4 (Snygg v1), which isn’t compatible with the latest release using Snygg v2.

                    If you are the theme author, please update your theme to support Snygg v2.

                    If you’re a user, please update your theme via the Addons Store. If an updated version isn’t available yet, please select one of the built-in themes during this transition period.
                """.trimIndent()
                )
            }
        }
    }
}

@Composable
private fun DevtoolsOverlayBox(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(all = 8.dp)
            .fillMaxWidth()
            .background(CardBackground),
    ) {
        Text(
            modifier = Modifier.padding(all = 8.dp),
            text = title,
            fontSize = 14.sp,
        )
        content()
    }
}

@Composable
private fun DevtoolsSubGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Text(
        modifier = Modifier.padding(start = 8.dp),
        text = title,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
    )
    Column(modifier = Modifier.padding(start = 12.dp, bottom = 8.dp), content = content)
}

@Composable
private fun DevtoolsText(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
}
