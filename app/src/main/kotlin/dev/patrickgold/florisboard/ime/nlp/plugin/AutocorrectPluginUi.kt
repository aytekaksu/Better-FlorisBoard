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

package dev.patrickgold.florisboard.ime.nlp.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiSurface
import org.florisboard.lib.compose.stringRes

private data class PendingPluginAction(
    val itemId: String,
    val kind: AutocorrectPluginUiItemKind,
    val invoke: () -> Unit,
)

@Composable
fun AutocorrectPluginUiHost(
    surface: AutocorrectPluginUiSurface,
    ui: AutocorrectPluginUi?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    appPreferenceGroup: @Composable (String, @Composable () -> Unit) -> Unit =
        { _, content -> content() },
) {
    val context = LocalContext.current
    val manager by context.autocorrectPluginManager()
    val error by manager.pluginUiError.collectAsState()
    val providerId = manager.selectedProvider()?.id.orEmpty()
    val rootPageId = when (surface) {
        AutocorrectPluginUiSurface.APP -> ui?.appRootPageId
        AutocorrectPluginUiSurface.KEYBOARD -> ui?.keyboardRootPageId
        AutocorrectPluginUiSurface.BOTH -> null
    }
    val pageStack = rememberSaveable(
        surface,
        providerId,
        rootPageId,
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }
    LaunchedEffect(ui, providerId, rootPageId, surface) {
        if (ui == null) return@LaunchedEffect
        while (pageStack.isNotEmpty() && ui.page(pageStack.last(), surface) == null) {
            pageStack.removeAt(pageStack.lastIndex)
        }
        if (pageStack.isEmpty()) {
            rootPageId?.let(pageStack::add)
        }
    }
    val page = ui?.page(pageStack.lastOrNull() ?: rootPageId, surface)
    if (surface == AutocorrectPluginUiSurface.APP) {
        BackHandler(enabled = pageStack.size > 1) {
            pageStack.removeAt(pageStack.lastIndex)
        }
    }
    var pendingAction by remember(
        surface,
        providerId,
        rootPageId,
    ) { mutableStateOf<PendingPluginAction?>(null) }
    val pendingItem = pendingAction?.let { action ->
        page?.items?.firstOrNull {
            it.id == action.itemId &&
                it.kind == action.kind &&
                it.enabled &&
                it.confirmation != null
        }
    }
    LaunchedEffect(page, pendingAction?.itemId) {
        if (pendingAction != null && pendingItem == null) pendingAction = null
    }
    val requestAction: (AutocorrectPluginUiItem, () -> Unit) -> Unit = { item, action ->
        if (item.confirmation == null) {
            action()
        } else {
            pendingAction = PendingPluginAction(item.id, item.kind, action)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (surface != AutocorrectPluginUiSurface.APP || pageStack.size > 1 || onClose != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                when {
                    pageStack.size > 1 -> IconButton(
                        onClick = { pageStack.removeAt(pageStack.lastIndex) },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringRes(R.string.action__back),
                        )
                    }

                    onClose != null -> IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringRes(R.string.action__close),
                        )
                    }

                    else -> Spacer(Modifier.width(48.dp))
                }
                Text(
                    text = page?.title ?: manager.selectedProvider()?.label.orEmpty(),
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                Spacer(Modifier.width(48.dp))
            }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (error) {
            Text(
                text = stringRes(R.string.settings__autocorrect_plugins__request_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }
        when {
            pendingAction != null &&
                pendingItem != null &&
                surface == AutocorrectPluginUiSurface.KEYBOARD -> {
                val action = pendingAction!!
                val item = pendingItem
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(item.title)
                    Text(
                        item.confirmation.orEmpty(),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Row {
                        TextButton(onClick = { pendingAction = null }) {
                            Text(stringRes(R.string.action__cancel))
                        }
                        Button(onClick = {
                            pendingAction = null
                            action.invoke()
                        }) {
                            Text(stringRes(R.string.action__ok))
                        }
                    }
                }
            }

            page != null -> {
                val onNavigate: (String) -> Unit = { target ->
                    if (ui.page(target, surface) != null) pageStack.add(target)
                }
                if (surface == AutocorrectPluginUiSurface.APP) {
                    key(providerId, rootPageId) {
                        AppPluginUiPage(
                            page = page,
                            showPageTitle = pageStack.size <= 1,
                            preferenceGroup = appPreferenceGroup,
                            onNavigate = onNavigate,
                            onSetValue = manager::setPluginUiValue,
                            onInvoke = manager::invokePluginUiAction,
                            onAction = requestAction,
                            onDocument = manager::sendPluginUiDocument,
                            onDocumentPickerOpen = manager::acquirePluginUiPickerLease,
                            onDocumentPickerClosed = manager::releasePluginUiPickerLease,
                            onDocumentPickerFailed = manager::reportPluginUiFailure,
                        )
                    }
                } else {
                    KeyboardPluginUiPage(
                        page = page,
                        onNavigate = onNavigate,
                        onSetValue = manager::setPluginUiValue,
                        onAction = { item ->
                            requestAction(item) { manager.invokePluginUiAction(item.id) }
                        },
                    )
                }
            }

            !loading && !error -> Text(
                text = stringRes(R.string.settings__autocorrect_plugins__embedded_unavailable),
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    pendingAction
        ?.takeIf { surface != AutocorrectPluginUiSurface.KEYBOARD && pendingItem != null }
        ?.let { action ->
            val item = pendingItem!!
            JetPrefAlertDialog(
                title = item.title,
                confirmLabel = stringRes(R.string.action__ok),
                dismissLabel = stringRes(R.string.action__cancel),
                onConfirm = {
                    pendingAction = null
                    action.invoke()
                },
                onDismiss = { pendingAction = null },
            ) {
                Text(item.confirmation.orEmpty())
            }
        }
}
