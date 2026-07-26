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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.autocorrectPluginManager
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectPluginUiIcon
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiSurface
import org.florisboard.lib.compose.stringRes
import kotlin.math.roundToInt

@Composable
fun AutocorrectPluginUiHost(
    surface: AutocorrectPluginUiSurface,
    ui: AutocorrectPluginUi?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val manager by context.autocorrectPluginManager()
    val rootPageId = when (surface) {
        AutocorrectPluginUiSurface.APP -> ui?.appRootPageId
        AutocorrectPluginUiSurface.KEYBOARD -> ui?.keyboardRootPageId
        AutocorrectPluginUiSurface.BOTH -> null
    }
    val pageStack = remember(surface) {
        mutableStateListOf<String>()
    }
    LaunchedEffect(ui, rootPageId, surface) {
        while (pageStack.isNotEmpty() && ui?.page(pageStack.last(), surface) == null) {
            pageStack.removeLast()
        }
        if (pageStack.isEmpty()) {
            rootPageId?.let(pageStack::add)
        }
    }
    val page = ui?.page(pageStack.lastOrNull() ?: rootPageId, surface)
    var pendingAction by remember { mutableStateOf<AutocorrectPluginUiItem?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            when {
                pageStack.size > 1 -> IconButton(onClick = { pageStack.removeLast() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                onClose != null -> IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
                else -> Spacer(Modifier.width(48.dp))
            }
            Text(
                text = page?.title ?: manager.selectedProvider()?.label.orEmpty(),
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            Spacer(Modifier.width(48.dp))
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        when {
            pendingAction != null && surface == AutocorrectPluginUiSurface.KEYBOARD -> {
                val item = pendingAction!!
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(item.title)
                    Text(item.confirmation.orEmpty(), modifier = Modifier.padding(vertical = 12.dp))
                    Row {
                        TextButton(onClick = { pendingAction = null }) {
                            Text(stringRes(R.string.action__cancel))
                        }
                        Button(onClick = {
                            pendingAction = null
                            performPluginAction(context, manager, item)
                        }) {
                            Text(stringRes(R.string.action__ok))
                        }
                    }
                }
            }
            page != null -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    page.summary?.let { summary ->
                        item { Text(summary, modifier = Modifier.padding(16.dp)) }
                    }
                    items(page.items, key = AutocorrectPluginUiItem::id) { item ->
                        PluginUiItem(
                            item = item,
                            surface = surface,
                            onNavigate = { target ->
                                if (ui.page(target, surface) != null) pageStack.add(target)
                            },
                            onSetValue = manager::setPluginUiValue,
                            onAction = { action ->
                                if (action.confirmation == null) {
                                    performPluginAction(context, manager, action)
                                } else {
                                    pendingAction = action
                                }
                            },
                        )
                    }
                }
            }
            !loading -> Text(
                text = stringRes(R.string.settings__autocorrect_plugins__embedded_unavailable),
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    pendingAction?.takeIf { surface != AutocorrectPluginUiSurface.KEYBOARD }?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(item.title) },
            text = { Text(item.confirmation.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    performPluginAction(context, manager, item)
                }) {
                    Text(stringRes(R.string.action__ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringRes(R.string.action__cancel))
                }
            },
        )
    }
}

@Composable
private fun PluginUiItem(
    item: AutocorrectPluginUiItem,
    surface: AutocorrectPluginUiSurface,
    onNavigate: (String) -> Unit,
    onSetValue: (String, String) -> Unit,
    onAction: (AutocorrectPluginUiItem) -> Unit,
) {
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
                        onCheckedChange = { onSetValue(item.id, it.toString()) },
                    )
                },
                onClick = { onSetValue(item.id, (!checked).toString()) },
            )
        }
        AutocorrectPluginUiItemKind.SLIDER -> {
            val minimum = item.minimum.toFloat()
            val maximum = item.maximum.toFloat().coerceAtLeast(minimum)
            var value by remember(item.id, item.value) {
                mutableFloatStateOf(
                    item.value?.toFloatOrNull()?.coerceIn(minimum, maximum) ?: minimum,
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(item.title)
                item.summary?.let { Text(it) }
                Slider(
                    value = value,
                    enabled = item.enabled,
                    valueRange = minimum..maximum,
                    steps = if (item.step > 0.0) {
                        (((maximum - minimum) / item.step.toFloat()).roundToInt() - 1).coerceAtLeast(0)
                            .coerceAtMost(1_000)
                    } else {
                        0
                    },
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
                                onClick = null,
                            )
                        },
                        modifier = Modifier.clickable(enabled = item.enabled) {
                            onSetValue(item.id, option.value)
                        },
                    )
                }
            }
        }
        AutocorrectPluginUiItemKind.TEXT -> {
            if (surface == AutocorrectPluginUiSurface.KEYBOARD) {
                PluginListItem(item = item)
            } else {
                var value by remember(item.id, item.value) { mutableStateOf(item.value.orEmpty()) }
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(
                        value = value,
                        enabled = item.enabled,
                        label = { Text(item.title) },
                        supportingText = item.summary?.let { { Text(it) } },
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = item.enabled,
                        onClick = { onSetValue(item.id, value) },
                    ) {
                        Text(stringRes(R.string.action__apply))
                    }
                }
            }
        }
        AutocorrectPluginUiItemKind.ACTION,
        AutocorrectPluginUiItemKind.ACTIVITY -> PluginListItem(
            item = item,
            onClick = { onAction(item) },
        )
        AutocorrectPluginUiItemKind.INFO -> PluginListItem(item = item)
        AutocorrectPluginUiItemKind.PROGRESS -> {
            val progress = item.value?.toFloatOrNull()?.coerceIn(0f, 1f)
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
private fun PluginListItem(
    item: AutocorrectPluginUiItem,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = item.summary?.let { summary -> { Text(summary) } },
        leadingContent = item.icon.imageVector()?.let { icon ->
            { Icon(icon, contentDescription = null) }
        },
        trailingContent = trailing,
        modifier = if (onClick != null) {
            Modifier.clickable(enabled = item.enabled, onClick = onClick)
        } else {
            Modifier
        },
    )
}

private fun performPluginAction(
    context: Context,
    manager: AutocorrectPluginManager,
    item: AutocorrectPluginUiItem,
) {
    if (item.kind == AutocorrectPluginUiItemKind.ACTIVITY) {
        val provider = manager.selectedProvider() ?: return
        val target = item.target ?: return
        val component = ComponentName(
            provider.componentName.packageName,
            if (target.startsWith(".")) provider.componentName.packageName + target else target,
        )
        val activity = runCatching {
            context.packageManager.getActivityInfo(component, 0)
        }.getOrNull()
        if (activity?.exported == true && component.packageName == provider.componentName.packageName) {
            context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } else {
        manager.invokePluginUiAction(item.id)
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
