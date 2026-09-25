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

package dev.patrickgold.florisboard.app.settings.typing

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.textservice.SpellCheckerService
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.util.launchActivity
import org.florisboard.lib.android.AndroidSettings
import org.florisboard.lib.compose.FlorisCanvasIcon
import org.florisboard.lib.compose.FlorisErrorCard
import org.florisboard.lib.compose.FlorisSimpleCard
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.observeAsState
import org.florisboard.lib.compose.stringRes

@Composable
fun SpellCheckerServiceSelector() {
    val context = LocalContext.current

    val systemSpellCheckerId by AndroidSettings.Secure.observeAsState(
        key = "selected_spell_checker",
        foregroundOnly = true,
    )
    val systemSpellCheckerEnabled by AndroidSettings.Secure.observeAsState(
        key = "spell_checker_enabled",
        foregroundOnly = true,
    )
    // Android may retain the selected ID after an app update removes its service.
    val selectedService = remember(systemSpellCheckerId, context) {
        val selected = ComponentName.unflattenFromString(systemSpellCheckerId.orEmpty())
        selected?.let { component ->
            val packageManager = context.packageManager
            val intent = Intent(SpellCheckerService.SERVICE_INTERFACE)
            val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentServices(intent, 0)
            }
            services.mapNotNull { it.serviceInfo }.firstOrNull { service ->
                ComponentName(service.packageName, service.name) == component &&
                    service.exported && service.permission == Manifest.permission.BIND_TEXT_SERVICE
            }
        }
    }
    val openSystemSpellCheckerSettings = {
        val componentToLaunch = ComponentName(
            "com.android.settings",
            "com.android.settings.Settings\$SpellCheckersSettingsActivity",
        )
        context.launchActivity {
            it.addCategory(Intent.CATEGORY_DEFAULT)
            it.component = componentToLaunch
        }
    }
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        if (systemSpellCheckerEnabled == "1") {
            if (selectedService == null) {
                FlorisWarningCard(
                    text = stringRes(R.string.pref__spelling__active_spellchecker__summary_none),
                    onClick = openSystemSpellCheckerSettings,
                )
            } else {
                var spellCheckerIcon: Drawable?
                var spellCheckerLabel = "Unknown"
                try {
                    val pm = context.packageManager
                    val remoteAppInfo = pm.getApplicationInfo(selectedService.packageName, 0)
                    spellCheckerIcon = pm.getApplicationIcon(remoteAppInfo)
                    spellCheckerLabel = pm.getApplicationLabel(remoteAppInfo).toString()
                } catch (e: Exception) {
                    spellCheckerIcon = null
                }
                FlorisSimpleCard(
                    icon = {
                        if (spellCheckerIcon != null) {
                            FlorisCanvasIcon(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .requiredSize(32.dp),
                                drawable = spellCheckerIcon,
                            )
                        } else {
                            Icon(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .requiredSize(32.dp),
                                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = null,
                            )
                        }
                    },
                    text = spellCheckerLabel,
                    secondaryText = selectedService.packageName,
                    contentPadding = PaddingValues(all = 8.dp),
                    onClick = openSystemSpellCheckerSettings,
                )
            }
        } else {
            FlorisErrorCard(
                text = stringRes(R.string.pref__spelling__active_spellchecker__summary_disabled),
                onClick = openSystemSpellCheckerSettings,
            )
        }
    }
}
