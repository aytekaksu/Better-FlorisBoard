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

package dev.patrickgold.florisboard.app.settings.typing

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.FlorisInfoCard
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.rippleClickable
import org.florisboard.lib.compose.stringRes

@Composable
fun AutocorrectPluginScreen() = FlorisScreen {
    title = stringRes(R.string.settings__autocorrect_plugins__title)

    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val manager by context.autocorrectPluginManager()
    val providers by manager.providers.collectAsState()
    val selectedProviderId by prefs.suggestion.autocorrectPluginComponent.collectAsState()
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        manager.refreshProviders()
    }

    content {
        FlorisWarningCard(
            text = stringRes(R.string.settings__autocorrect_plugins__privacy_warning),
        )
        FlorisInfoCard(
            text = stringRes(R.string.settings__autocorrect_plugins__battery_info),
        )

        PreferenceGroup(title = stringRes(R.string.settings__autocorrect_plugins__providers)) {
            JetPrefListItem(
                modifier = Modifier.rippleClickable {
                    scope.launch {
                        prefs.suggestion.autocorrectPluginComponent.set("")
                    }
                },
                icon = {
                    RadioButton(
                        selected = selectedProviderId.isBlank(),
                        onClick = null,
                    )
                },
                text = stringRes(R.string.settings__autocorrect_plugins__none),
                secondaryText = stringRes(R.string.settings__autocorrect_plugins__none_summary),
            )
            providers.forEach { provider ->
                JetPrefListItem(
                    modifier = Modifier.rippleClickable {
                        scope.launch {
                            prefs.suggestion.autocorrectPluginComponent.set(provider.id)
                        }
                    },
                    icon = {
                        RadioButton(
                            selected = provider.id == selectedProviderId,
                            onClick = null,
                        )
                    },
                    text = provider.label,
                    secondaryText = provider.componentName.packageName,
                )
            }
        }

        if (providers.isEmpty()) {
            FlorisInfoCard(
                text = stringRes(R.string.settings__autocorrect_plugins__none_installed),
            )
        }
        if (selectedProviderId.isNotBlank() && selectedProvider == null) {
            FlorisWarningCard(
                text = stringRes(R.string.settings__autocorrect_plugins__unavailable),
            )
        }

        selectedProvider?.settingsActivity?.let { settingsActivity ->
            PreferenceGroup(title = selectedProvider.label) {
                Preference(
                    icon = Icons.Default.Settings,
                    title = stringRes(R.string.settings__autocorrect_plugins__configure),
                    summary = selectedProvider.label,
                    onClick = {
                        runCatching {
                            context.startActivity(Intent().setComponent(settingsActivity))
                        }
                    },
                )
            }
        }
    }
}
