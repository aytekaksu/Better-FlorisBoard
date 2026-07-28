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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.lib.util.launchPluginHttpsUrl
import dev.patrickgold.florisboard.lib.util.safePluginHttpsUrlOrNull
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiPage

@Composable
internal fun KeyboardPluginUiPage(
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
                        ?.let(item::normalizedSliderValue)
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
                    steps = item.sliderSteps(),
                    onValueChange = { value = item.normalizedSliderValue(it) },
                    onValueChangeFinished = {
                        onSetValue(item.id, item.formattedSliderValue(value))
                    },
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
        AutocorrectPluginUiItemKind.INFO,
        -> PluginListItem(item = item)

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
