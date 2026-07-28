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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import org.florisboard.autocorrect.api.AutocorrectPluginUiIcon
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem

@Composable
internal fun PluginListItem(
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
        modifier = pluginUiItemModifier(item, onClick, onToggle),
    )
}

internal fun pluginUiItemModifier(
    item: AutocorrectPluginUiItem,
    onClick: (() -> Unit)?,
    onToggle: ((Boolean) -> Unit)?,
): Modifier = when {
    onToggle != null -> Modifier.toggleable(
        value = item.value.toBoolean(),
        enabled = item.enabled,
        role = Role.Switch,
        onValueChange = onToggle,
    )

    onClick != null -> Modifier.clickable(enabled = item.enabled, onClick = onClick)

    else -> Modifier
}

internal fun AutocorrectPluginUiIcon.imageVector(): ImageVector? = when (this) {
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
