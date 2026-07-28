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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.util.launchPluginHttpsUrl
import dev.patrickgold.florisboard.lib.util.safePluginHttpsUrlOrNull
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiPage
import org.florisboard.lib.compose.florisVerticalScroll
import org.florisboard.lib.compose.stringRes

@Composable
internal fun AppPluginUiPage(
    page: AutocorrectPluginUiPage,
    showPageTitle: Boolean,
    preferenceGroup: @Composable (String, @Composable () -> Unit) -> Unit,
    onNavigate: (String) -> Unit,
    onSetValue: (String, String) -> Unit,
    onInvoke: (String) -> Unit,
    onAction: (AutocorrectPluginUiItem, () -> Unit) -> Unit,
    onDocument: (String, Uri, Boolean, PluginUiPickerLease) -> Unit,
    onDocumentPickerOpen: () -> PluginUiPickerLease?,
    onDocumentPickerClosed: (PluginUiPickerLease) -> Unit,
    onDocumentPickerFailed: () -> Unit,
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
                        onDocumentPickerFailed = onDocumentPickerFailed,
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
    onDocument: (String, Uri, Boolean, PluginUiPickerLease) -> Unit,
    onDocumentPickerOpen: () -> PluginUiPickerLease?,
    onDocumentPickerClosed: (PluginUiPickerLease) -> Unit,
    onDocumentPickerFailed: () -> Unit,
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
        AutocorrectPluginUiItemKind.TEXT,
        -> AppPluginListItem(
            item = item,
            secondaryText = when (item.kind) {
                AutocorrectPluginUiItemKind.SLIDER -> item.formattedCurrentSliderValue() ?: item.summary
                AutocorrectPluginUiItemKind.TEXT -> item.value ?: item.summary
                else -> item.summary
            },
            trailing = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            onClick = { onEdit(item) },
        )

        AutocorrectPluginUiItemKind.CHOICE -> AppPluginChoiceListItem(
            item = item,
            onClick = { onEdit(item) },
        )

        AutocorrectPluginUiItemKind.ACTION -> AppPluginListItem(
            item = item,
            onClick = {
                onAction(item) { onInvoke(item.id) }
            },
        )

        AutocorrectPluginUiItemKind.DOCUMENT_IMPORT -> AppPluginDocumentItem(
            item = item,
            write = false,
            contract = ActivityResultContracts.OpenDocument(),
            input = item.documentMimeTypes.ifEmpty { listOf("*/*") }.toTypedArray(),
            onAction = onAction,
            onDocument = onDocument,
            onDocumentPickerOpen = onDocumentPickerOpen,
            onDocumentPickerClosed = onDocumentPickerClosed,
            onDocumentPickerFailed = onDocumentPickerFailed,
        )

        AutocorrectPluginUiItemKind.DOCUMENT_EXPORT -> AppPluginDocumentItem(
            item = item,
            write = true,
            contract = ActivityResultContracts.CreateDocument(
                item.documentMimeTypes.firstOrNull() ?: "*/*",
            ),
            input = item.documentSuggestedName ?: item.title,
            onAction = onAction,
            onDocument = onDocument,
            onDocumentPickerOpen = onDocumentPickerOpen,
            onDocumentPickerClosed = onDocumentPickerClosed,
            onDocumentPickerFailed = onDocumentPickerFailed,
        )

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
private fun <I> AppPluginDocumentItem(
    item: AutocorrectPluginUiItem,
    write: Boolean,
    contract: ActivityResultContract<I, Uri?>,
    input: I,
    onAction: (AutocorrectPluginUiItem, () -> Unit) -> Unit,
    onDocument: (String, Uri, Boolean, PluginUiPickerLease) -> Unit,
    onDocumentPickerOpen: () -> PluginUiPickerLease?,
    onDocumentPickerClosed: (PluginUiPickerLease) -> Unit,
    onDocumentPickerFailed: () -> Unit,
) {
    val pickerLease = remember(item.id, item.kind) {
        mutableStateOf<PluginUiPickerLease?>(null)
    }
    val releasePickerLease by rememberUpdatedState(onDocumentPickerClosed)
    DisposableEffect(item.id, item.kind) {
        onDispose {
            pickerLease.value?.let { lease ->
                pickerLease.value = null
                releasePickerLease(lease)
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        val lease = pickerLease.value
        pickerLease.value = null
        if (lease != null) {
            try {
                uri?.let { onDocument(item.id, it, write, lease) }
            } finally {
                onDocumentPickerClosed(lease)
            }
        }
    }
    AppPluginListItem(
        item = item,
        onClick = {
            onAction(item) {
                if (pickerLease.value == null) {
                    val lease = onDocumentPickerOpen()
                    if (lease == null) {
                        onDocumentPickerFailed()
                    } else {
                        pickerLease.value = lease
                        try {
                            launcher.launch(input)
                        } catch (_: RuntimeException) {
                            pickerLease.value = null
                            onDocumentPickerClosed(lease)
                            onDocumentPickerFailed()
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AppPluginValueDialog(item: AutocorrectPluginUiItem, onDismiss: () -> Unit, onSetValue: (String) -> Unit) {
    when (item.kind) {
        AutocorrectPluginUiItemKind.SLIDER -> {
            val minimum = item.minimum.toFloat()
            val maximum = item.maximum.toFloat().coerceAtLeast(minimum)
            var value by remember(item.id, item.value) {
                mutableFloatStateOf(
                    item.value?.toFloatOrNull()
                        ?.takeIf(Float::isFinite)
                        ?.let(item::normalizedSliderValue)
                        ?: minimum,
                )
            }
            JetPrefAlertDialog(
                title = item.title,
                confirmLabel = stringRes(R.string.action__apply),
                dismissLabel = stringRes(R.string.action__cancel),
                onConfirm = { onSetValue(item.formattedSliderValue(value)) },
                onDismiss = onDismiss,
            ) {
                Column {
                    item.summary?.let { Text(it) }
                    Slider(
                        value = value,
                        valueRange = minimum..maximum,
                        steps = item.sliderSteps(),
                        onValueChange = { value = item.normalizedSliderValue(it) },
                    )
                    Text(
                        item.formattedSliderValue(value),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
        modifier = pluginUiItemModifier(item, onClick, onToggle),
    )
}

@Composable
private fun AppPluginChoiceListItem(item: AutocorrectPluginUiItem, onClick: () -> Unit) {
    val supportingText = item.appChoiceSupportingText()
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = if (supportingText.label != null || supportingText.summary != null) {
            {
                Column {
                    supportingText.label?.let {
                        Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    supportingText.summary?.let {
                        Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            null
        },
        leadingContent = item.icon.imageVector()?.let { icon ->
            { Icon(icon, contentDescription = null) }
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.clickable(enabled = item.enabled, onClick = onClick),
    )
}
