/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.localization

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.app.ext.ExtensionImportScreenType
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.lib.compose.FlorisConfirmDeleteDialog
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.jetpref.datastore.ui.ExperimentalJetPrefDatastoreUi
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.FlorisTextButton
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.stringRes

enum class LanguagePackManagerScreenAction {
    MANAGE;
}

@OptIn(ExperimentalJetPrefDatastoreUi::class)
@Composable
fun LanguagePackManagerScreen(action: LanguagePackManagerScreenAction?) = FlorisScreen {
    if (action != LanguagePackManagerScreenAction.MANAGE) {
        error("LanguagePack manager screen action must not be null")
    }
    title = stringRes(R.string.settings__localization__language_pack_title)

    val navController = LocalNavController.current
    val context = LocalContext.current
    val extensionManager by context.extensionManager()
    val operationScope = rememberCoroutineScope()

    val indexedLanguagePackExtensions by extensionManager.languagePacks.collectAsState()
    val indexedLanguagePacksById = remember(indexedLanguagePackExtensions) {
        indexedLanguagePackExtensions.associateBy { it.meta.id }
    }

    var languagePackExtToDelete by remember { mutableStateOf<Extension?>(null) }

    content {
        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
        ) {
            Preference(
                onClick = { navController.navigate(
                    Routes.Ext.Import(ExtensionImportScreenType.EXT_LANGUAGEPACK, null)
                ) },
                icon = Icons.AutoMirrored.Filled.Input,
                title = stringRes(R.string.action__import),
            )
        }
        for ((extensionId, ext) in indexedLanguagePacksById) key(extensionId) {
            val configs = remember(ext) { ext.items.sortedBy { it.label } }
            FlorisOutlinedBox(
                modifier = Modifier.defaultFlorisOutlinedBox(),
                title = ext.meta.title,
                onTitleClick = { navController.navigate(Routes.Ext.View(extensionId)) },
                subtitle = extensionId,
                onSubtitleClick = { navController.navigate(Routes.Ext.View(extensionId)) },
            ) {
                Column(
                    // Allowing horizontal scroll to fit translations in descriptions.
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .width(intrinsicSize = IntrinsicSize.Max),
                ) {
                    for (config in configs) key(extensionId, config.id) {
                        JetPrefListItem(
                            text = config.label,
                        )
                    }
                }
                if (extensionManager.canDelete(ext)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                    ) {
                        FlorisTextButton(
                            onClick = {
                                languagePackExtToDelete = ext
                            },
                            icon = Icons.Default.Delete,
                            text = stringRes(R.string.action__delete),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (languagePackExtToDelete != null) {
            FlorisConfirmDeleteDialog(
                onConfirm = {
                    val extension = languagePackExtToDelete!!
                    languagePackExtToDelete = null
                    operationScope.launch {
                        runCatching {
                            extensionManager.delete(extension)
                        }.onFailure {
                            context.showLongToast(R.string.error__snackbar_message)
                        }
                    }
                },
                onDismiss = { languagePackExtToDelete = null },
                what = languagePackExtToDelete!!.meta.title,
            )
        }
    }
}
