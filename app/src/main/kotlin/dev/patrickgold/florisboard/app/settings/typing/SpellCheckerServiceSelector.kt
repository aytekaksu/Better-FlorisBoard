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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.textservice.SpellCheckerService
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.util.launchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.florisboard.lib.android.AndroidSettings
import org.florisboard.lib.compose.FlorisErrorCard
import org.florisboard.lib.compose.FlorisSimpleCard
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.observeAsState
import org.florisboard.lib.compose.rasterizeDrawable
import org.florisboard.lib.compose.stringRes
import kotlin.coroutines.cancellation.CancellationException

internal data class SpellCheckerPresentation(
    val packageName: String,
    val icon: Drawable?,
    val label: String,
)

private sealed interface SpellCheckerLoadState {
    data object Loading : SpellCheckerLoadState
    data object Missing : SpellCheckerLoadState
    data class Found(
        val label: String,
        val packageName: String,
        val icon: ImageBitmap?,
    ) : SpellCheckerLoadState
}

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
    SpellCheckerServiceSelectorContent(
        selectedId = systemSpellCheckerId,
        enabled = systemSpellCheckerEnabled,
        onClick = openSystemSpellCheckerSettings,
    )
}

@Composable
internal fun SpellCheckerServiceSelectorContent(
    selectedId: String?,
    enabled: String?,
    loader: (Context, String) -> SpellCheckerPresentation? = ::loadSpellChecker,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val configuration = LocalConfiguration.current
    val fallbackSizePx = with(LocalDensity.current) { 48.dp.roundToPx() }
    val state = if (enabled == "1" && ComponentName.unflattenFromString(selectedId.orEmpty()) != null) {
        key(context, configuration, selectedId, fallbackSizePx) {
            produceState<SpellCheckerLoadState>(SpellCheckerLoadState.Loading) {
                value = try {
                    runInterruptible(Dispatchers.IO) {
                        loader(appContext, selectedId.orEmpty())?.let { presentation ->
                            val icon = try {
                                presentation.icon?.let { rasterizeDrawable(it, fallbackSizePx) }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                null
                            }
                            SpellCheckerLoadState.Found(presentation.label, presentation.packageName, icon)
                        }
                    } ?: SpellCheckerLoadState.Missing
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    SpellCheckerLoadState.Missing
                }
            }.value
        }
    } else {
        SpellCheckerLoadState.Missing
    }
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        if (enabled == "1") {
            when (state) {
                SpellCheckerLoadState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp).requiredSize(32.dp),
                )
                SpellCheckerLoadState.Missing -> {
                    FlorisWarningCard(
                        text = stringRes(R.string.pref__spelling__active_spellchecker__summary_none),
                        onClick = onClick,
                    )
                }
                is SpellCheckerLoadState.Found -> {
                    FlorisSimpleCard(
                        icon = {
                            if (state.icon != null) {
                                Image(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .requiredSize(32.dp),
                                    bitmap = state.icon,
                                    contentDescription = null,
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
                        text = state.label,
                        secondaryText = state.packageName,
                        contentPadding = PaddingValues(all = 8.dp),
                        onClick = onClick,
                    )
                }
            }
        } else {
            FlorisErrorCard(
                text = stringRes(R.string.pref__spelling__active_spellchecker__summary_disabled),
                onClick = onClick,
            )
        }
    }
}

// A selected ID can outlive its service after an app update or uninstall.
private fun loadSpellChecker(context: Context, selectedId: String): SpellCheckerPresentation? {
    val selected = ComponentName.unflattenFromString(selectedId) ?: return null
    val pm = context.packageManager
    val intent = Intent(SpellCheckerService.SERVICE_INTERFACE)
    val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentServices(intent, 0)
    }
    val service = services.mapNotNull { it.serviceInfo }.firstOrNull {
        ComponentName(it.packageName, it.name) == selected &&
            it.exported && it.permission == Manifest.permission.BIND_TEXT_SERVICE
    } ?: return null
    return try {
        val appInfo = pm.getApplicationInfo(service.packageName, 0)
        SpellCheckerPresentation(
            packageName = service.packageName,
            icon = pm.getApplicationIcon(appInfo),
            label = pm.getApplicationLabel(appInfo).toString(),
        )
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        SpellCheckerPresentation(service.packageName, null, "Unknown")
    }
}
