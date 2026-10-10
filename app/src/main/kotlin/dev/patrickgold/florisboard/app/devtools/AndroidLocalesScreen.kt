/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.devtools

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import java.util.Locale
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile

@Composable
fun AndroidLocalesScreen() = FlorisScreen {
    title = stringRes(R.string.devtools__android_locales__title)
    scrollable = false

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val availableLocales = remember { Locale.getAvailableLocales().sortedBy { it.toLanguageTag() } }

    actions {
        FlorisIconButton(
            onClick = {
                try {
                    val devtoolsDir = context.noBackupFilesDir.subDir("devtools")
                    devtoolsDir.mkdirs()
                    val txtFile = devtoolsDir.subFile("system_locales.tsv")
                    txtFile.bufferedWriter().use { out ->
                        for (locale in availableLocales) {
                            out.append(locale.toLanguageTag())
                            out.append('\t')
                            out.append(locale.getDisplayName(Locale.ENGLISH))
                            out.append('\t')
                            out.append(locale.getDisplayName(locale))
                            out.appendLine()
                        }
                    }
                    scope.launch {
                        context.showLongToast("Exported available system locales to private app storage: devtools/${txtFile.name}")
                    }
                } catch (e: Exception) {
                    scope.launch {
                        context.showLongToast(
                            R.string.error__snackbar_message_template,
                            "error_message" to failureClassName(e),
                        )
                    }
                }
            },
            icon = Icons.Default.Save,
        )
    }

    content {
        val displayLanguageNamesIn by prefs.localization.displayLanguageNamesIn.collectAsState()

        SelectionContainer(modifier = Modifier.fillMaxWidth()) {
            LazyColumn {
                items(availableLocales) { locale ->
                    Row {
                        Text(
                            text = locale.toLanguageTag().padEnd(12),
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            modifier = Modifier.weight(1.0f),
                            text = when (displayLanguageNamesIn) {
                                DisplayLanguageNamesIn.SYSTEM_LOCALE -> locale.displayName
                                DisplayLanguageNamesIn.NATIVE_LOCALE -> locale.getDisplayName(locale)
                            },
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
