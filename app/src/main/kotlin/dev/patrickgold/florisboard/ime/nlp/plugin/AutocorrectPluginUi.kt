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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.lib.util.launchPluginHttpsUrl
import dev.patrickgold.florisboard.lib.util.safePluginHttpsUrlOrNull
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectPluginUiIcon
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiPage
import org.florisboard.autocorrect.api.AutocorrectPluginUiSurface
import org.florisboard.lib.compose.florisVerticalScroll
import org.florisboard.lib.compose.stringRes
import kotlin.math.roundToInt

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
    val rootPageId = when (surface) {
        AutocorrectPluginUiSurface.APP -> ui?.appRootPageId
        AutocorrectPluginUiSurface.KEYBOARD -> ui?.keyboardRootPageId
        AutocorrectPluginUiSurface.BOTH -> null
    }
    val pageStack = rememberSaveable(
        surface,
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }
    LaunchedEffect(ui, rootPageId, surface) {
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
    var pendingAction by remember { mutableStateOf<PendingPluginAction?>(null) }
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
                    AppPluginUiPage(
                        page = page,
                        showPageTitle = pageStack.size <= 1,
                        preferenceGroup = appPreferenceGroup,
                        onNavigate = onNavigate,
                        onSetValue = manager::setPluginUiValue,
                        onInvoke = manager::invokePluginUiAction,
                        onAction = requestAction,
                        onDocument = manager::sendPluginUiDocument,
                        onDocumentPickerOpen = manager::acquirePluginUi,
                        onDocumentPickerClosed = manager::releasePluginUi,
                    )
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

@Composable
private fun AppPluginUiPage(
    page: AutocorrectPluginUiPage,
    showPageTitle: Boolean,
    preferenceGroup: @Composable (String, @Composable () -> Unit) -> Unit,
    onNavigate: (String) -> Unit,
    onSetValue: (String, String) -> Unit,
    onInvoke: (String) -> Unit,
    onAction: (AutocorrectPluginUiItem, () -> Unit) -> Unit,
    onDocument: (String, android.net.Uri, Boolean) -> Unit,
    onDocumentPickerOpen: () -> Unit,
    onDocumentPickerClosed: () -> Unit,
) {
    var editorId by remember(page.id) { mutableStateOf<String?>(null) }
    val editor = editorId?.let { id ->
        page.items.firstOrNull {
            it.id == id &&
                it.enabled &&
                (
                    it.kind == AutocorrectPluginUiItemKind.SLIDER ||
                        it.kind == AutocorrectPluginUiItemKind.CHOICE ||
                        it.kind == AutocorrectPluginUiItemKind.TEXT
                    )
        }
    }
    LaunchedEffect(page.items, editorId) {
        if (editorId != null && editor == null) editorId = null
    }

    Column(modifier = Modifier.fillMaxSize().florisVerticalScroll()) {
        page.summary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        preferenceGroup(page.title.takeIf { showPageTitle }.orEmpty()) {
            page.items.forEach { item ->
                key(item.id) {
                    AppPluginUiItem(
                        item = item,
                        onNavigate = onNavigate,
                        onSetValue = onSetValue,
                        onInvoke = onInvoke,
                        onEdit = { editorId = it.id },
                        onAction = onAction,
                        onDocument = onDocument,
                        onDocumentPickerOpen = onDocumentPickerOpen,
                        onDocumentPickerClosed = onDocumentPickerClosed,
                    )
                }
            }
        }
    }

    editor?.let { item ->
        AppPluginValueDialog(
            item = item,
            onDismiss = { editorId = null },
            onSetValue = { value ->
                editorId = null
                onSetValue(item.id, value)
            },
        )
    }
}

@Composable
private fun AppPluginUiItem(
    item: AutocorrectPluginUiItem,
    onNavigate: (String) -> Unit,
    onSetValue: (String, String) -> Unit,
    onInvoke: (String) -> Unit,
    onEdit: (AutocorrectPluginUiItem) -> Unit,
    onAction: (AutocorrectPluginUiItem, () -> Unit) -> Unit,
    onDocument: (String, android.net.Uri, Boolean) -> Unit,
    onDocumentPickerOpen: () -> Unit,
    onDocumentPickerClosed: () -> Unit,
) {
    val context = LocalContext.current
    when (item.kind) {
        AutocorrectPluginUiItemKind.NAVIGATION -> AppPluginListItem(
            item = item,
            trailing = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            onClick = { item.target?.let(onNavigate) },
        )
        AutocorrectPluginUiItemKind.SWITCH -> {
            val checked = item.value.toBoolean()
            AppPluginListItem(
                item = item,
                trailing = {
                    Switch(
                        checked = checked,
                        enabled = item.enabled,
                        onCheckedChange = null,
                    )
                },
                onToggle = { onSetValue(item.id, it.toString()) },
            )
        }
        AutocorrectPluginUiItemKind.SLIDER,
        AutocorrectPluginUiItemKind.CHOICE,
        AutocorrectPluginUiItemKind.TEXT -> AppPluginListItem(
            item = item,
            secondaryText = when (item.kind) {
                AutocorrectPluginUiItemKind.CHOICE ->
                    item.options.firstOrNull { it.value == item.value }?.label ?: item.summary
                AutocorrectPluginUiItemKind.SLIDER,
                AutocorrectPluginUiItemKind.TEXT -> item.value ?: item.summary
                else -> item.summary
            },
            trailing = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            onClick = { onEdit(item) },
        )
        AutocorrectPluginUiItemKind.ACTION -> AppPluginListItem(
            item = item,
            onClick = {
                onAction(item) { onInvoke(item.id) }
            },
        )
        AutocorrectPluginUiItemKind.DOCUMENT_IMPORT -> {
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                try {
                    uri?.let { onDocument(item.id, it, false) }
                } finally {
                    onDocumentPickerClosed()
                }
            }
            AppPluginListItem(
                item = item,
                onClick = {
                    onAction(item) {
                        onDocumentPickerOpen()
                        try {
                            launcher.launch(
                                item.documentMimeTypes.ifEmpty { listOf("*/*") }.toTypedArray(),
                            )
                        } catch (error: RuntimeException) {
                            onDocumentPickerClosed()
                            throw error
                        }
                    }
                },
            )
        }
        AutocorrectPluginUiItemKind.DOCUMENT_EXPORT -> {
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument(
                    item.documentMimeTypes.firstOrNull() ?: "*/*",
                ),
            ) { uri ->
                try {
                    uri?.let { onDocument(item.id, it, true) }
                } finally {
                    onDocumentPickerClosed()
                }
            }
            AppPluginListItem(
                item = item,
                onClick = {
                    onAction(item) {
                        onDocumentPickerOpen()
                        try {
                            launcher.launch(item.documentSuggestedName ?: item.title)
                        } catch (error: RuntimeException) {
                            onDocumentPickerClosed()
                            throw error
                        }
                    }
                },
            )
        }
        AutocorrectPluginUiItemKind.INFO -> AppPluginListItem(item = item)
        AutocorrectPluginUiItemKind.EXTERNAL_LINK -> {
            val target = item.target?.safePluginHttpsUrlOrNull()
            AppPluginListItem(
                item = item,
                trailing = if (target != null) {
                    {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                } else {
                    null
                },
                onClick = target?.let { { context.launchPluginHttpsUrl(it) } },
            )
        }
        AutocorrectPluginUiItemKind.PROGRESS -> {
            val progress = item.value?.toFloatOrNull()
                ?.takeIf(Float::isFinite)
                ?.coerceIn(0f, 1f)
            Column(modifier = Modifier.fillMaxWidth()) {
                AppPluginListItem(item = item)
                if (progress == null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPluginValueDialog(
    item: AutocorrectPluginUiItem,
    onDismiss: () -> Unit,
    onSetValue: (String) -> Unit,
) {
    when (item.kind) {
        AutocorrectPluginUiItemKind.SLIDER -> {
            val minimum = item.minimum.toFloat()
            val maximum = item.maximum.toFloat().coerceAtLeast(minimum)
            var value by remember(item.id, item.value) {
                mutableFloatStateOf(
                    item.value?.toFloatOrNull()
                        ?.takeIf(Float::isFinite)
                        ?.coerceIn(minimum, maximum)
                        ?: minimum,
                )
            }
            JetPrefAlertDialog(
                title = item.title,
                confirmLabel = stringRes(R.string.action__apply),
                dismissLabel = stringRes(R.string.action__cancel),
                onConfirm = { onSetValue(value.toString()) },
                onDismiss = onDismiss,
            ) {
                Column {
                    item.summary?.let { Text(it) }
                    Slider(
                        value = value,
                        valueRange = minimum..maximum,
                        steps = item.sliderSteps(minimum, maximum),
                        onValueChange = { value = it },
                    )
                    Text(value.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        AutocorrectPluginUiItemKind.CHOICE -> {
            var value by remember(item.id, item.value) { mutableStateOf(item.value.orEmpty()) }
            JetPrefAlertDialog(
                title = item.title,
                confirmLabel = stringRes(R.string.action__apply),
                dismissLabel = stringRes(R.string.action__cancel),
                onConfirm = { onSetValue(value) },
                onDismiss = onDismiss,
            ) {
                Column {
                    item.summary?.let {
                        Text(it, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    item.options.forEach { option ->
                        JetPrefListItem(
                            text = option.label,
                            icon = {
                                RadioButton(
                                    selected = option.value == value,
                                    onClick = null,
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = AlertDialogDefaults.containerColor,
                            ),
                            modifier = Modifier.selectable(
                                selected = option.value == value,
                                role = Role.RadioButton,
                                onClick = { value = option.value },
                            ),
                        )
                    }
                }
            }
        }
        AutocorrectPluginUiItemKind.TEXT -> {
            var value by remember(item.id, item.value) { mutableStateOf(item.value.orEmpty()) }
            JetPrefAlertDialog(
                title = item.title,
                confirmLabel = stringRes(R.string.action__apply),
                dismissLabel = stringRes(R.string.action__cancel),
                onConfirm = { onSetValue(value) },
                onDismiss = onDismiss,
            ) {
                Column {
                    item.summary?.let { Text(it) }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(item.title) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun KeyboardPluginUiPage(
    page: AutocorrectPluginUiPage,
    onNavigate: (String) -> Unit,
    onSetValue: (String, String) -> Unit,
    onAction: (AutocorrectPluginUiItem) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        page.summary?.let { summary ->
            item { Text(summary, modifier = Modifier.padding(16.dp)) }
        }
        items(page.items, key = AutocorrectPluginUiItem::id) { item ->
            KeyboardPluginUiItem(
                item = item,
                onNavigate = onNavigate,
                onSetValue = onSetValue,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun KeyboardPluginUiItem(
    item: AutocorrectPluginUiItem,
    onNavigate: (String) -> Unit,
    onSetValue: (String, String) -> Unit,
    onAction: (AutocorrectPluginUiItem) -> Unit,
) {
    val context = LocalContext.current
    when (item.kind) {
        AutocorrectPluginUiItemKind.NAVIGATION -> PluginListItem(
            item = item,
            trailing = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            onClick = { item.target?.let(onNavigate) },
        )
        AutocorrectPluginUiItemKind.SWITCH -> {
            val checked = item.value.toBoolean()
            PluginListItem(
                item = item,
                trailing = {
                    Switch(
                        checked = checked,
                        enabled = item.enabled,
                        onCheckedChange = null,
                    )
                },
                onToggle = { onSetValue(item.id, it.toString()) },
            )
        }
        AutocorrectPluginUiItemKind.SLIDER -> {
            val minimum = item.minimum.toFloat()
            val maximum = item.maximum.toFloat().coerceAtLeast(minimum)
            var value by remember(item.id, item.value) {
                mutableFloatStateOf(
                    item.value?.toFloatOrNull()
                        ?.takeIf(Float::isFinite)
                        ?.coerceIn(minimum, maximum)
                        ?: minimum,
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(item.title)
                item.summary?.let { Text(it) }
                Slider(
                    value = value,
                    enabled = item.enabled,
                    valueRange = minimum..maximum,
                    steps = item.sliderSteps(minimum, maximum),
                    onValueChange = { value = it },
                    onValueChangeFinished = { onSetValue(item.id, value.toString()) },
                )
            }
        }
        AutocorrectPluginUiItemKind.CHOICE -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(item.title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                item.summary?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp))
                }
                item.options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(option.label) },
                        leadingContent = {
                            RadioButton(
                                selected = option.value == item.value,
                                enabled = item.enabled,
                                onClick = null,
                            )
                        },
                        modifier = Modifier.selectable(
                            selected = option.value == item.value,
                            enabled = item.enabled,
                            role = Role.RadioButton,
                            onClick = { onSetValue(item.id, option.value) },
                        ),
                    )
                }
            }
        }
        AutocorrectPluginUiItemKind.TEXT -> PluginListItem(
            item = item,
            secondaryText = item.value ?: item.summary,
        )
        AutocorrectPluginUiItemKind.ACTION -> PluginListItem(
            item = item,
            onClick = { onAction(item) },
        )
        AutocorrectPluginUiItemKind.DOCUMENT_IMPORT,
        AutocorrectPluginUiItemKind.DOCUMENT_EXPORT,
        AutocorrectPluginUiItemKind.INFO -> PluginListItem(item = item)
        AutocorrectPluginUiItemKind.EXTERNAL_LINK -> {
            val target = item.target?.safePluginHttpsUrlOrNull()
            PluginListItem(
                item = item,
                trailing = if (target != null) {
                    {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    }
                } else {
                    null
                },
                onClick = target?.let { { context.launchPluginHttpsUrl(it) } },
            )
        }
        AutocorrectPluginUiItemKind.PROGRESS -> {
            val progress = item.value?.toFloatOrNull()
                ?.takeIf(Float::isFinite)
                ?.coerceIn(0f, 1f)
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(item.title)
                item.summary?.let { Text(it) }
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPluginListItem(
    item: AutocorrectPluginUiItem,
    secondaryText: String? = item.summary,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    JetPrefListItem(
        text = item.title,
        secondaryText = secondaryText,
        icon = item.icon.imageVector()?.let { icon ->
            { Icon(icon, contentDescription = null) }
        },
        trailing = trailing,
        modifier = when {
            onToggle != null -> Modifier.toggleable(
                value = item.value.toBoolean(),
                enabled = item.enabled,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            onClick != null -> Modifier.clickable(enabled = item.enabled, onClick = onClick)
            else -> Modifier
        },
    )
}

@Composable
private fun PluginListItem(
    item: AutocorrectPluginUiItem,
    secondaryText: String? = item.summary,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = secondaryText?.let { text -> { Text(text) } },
        leadingContent = item.icon.imageVector()?.let { icon ->
            { Icon(icon, contentDescription = null) }
        },
        trailingContent = trailing,
        modifier = when {
            onToggle != null -> Modifier.toggleable(
                value = item.value.toBoolean(),
                enabled = item.enabled,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            onClick != null -> Modifier.clickable(enabled = item.enabled, onClick = onClick)
            else -> Modifier
        },
    )
}

private fun AutocorrectPluginUiItem.sliderSteps(minimum: Float, maximum: Float): Int {
    val ratio = (maximum - minimum) / step.toFloat()
    return if (step > 0.0 && ratio.isFinite()) {
        (ratio.roundToInt() - 1)
            .coerceIn(0, 1_000)
    } else {
        0
    }
}

private fun AutocorrectPluginUiIcon.imageVector(): ImageVector? = when (this) {
    AutocorrectPluginUiIcon.NONE -> null
    AutocorrectPluginUiIcon.SETTINGS -> Icons.Default.Settings
    AutocorrectPluginUiIcon.MODEL -> Icons.Default.Memory
    AutocorrectPluginUiIcon.DICTIONARY -> Icons.AutoMirrored.Filled.MenuBook
    AutocorrectPluginUiIcon.TUNE -> Icons.Default.Tune
    AutocorrectPluginUiIcon.DOWNLOAD -> Icons.Default.Download
    AutocorrectPluginUiIcon.UPLOAD -> Icons.Default.Upload
    AutocorrectPluginUiIcon.DELETE -> Icons.Default.Delete
    AutocorrectPluginUiIcon.REFRESH -> Icons.Default.Refresh
    AutocorrectPluginUiIcon.INFO -> Icons.Default.Info
    AutocorrectPluginUiIcon.ADD -> Icons.Default.Add
}
