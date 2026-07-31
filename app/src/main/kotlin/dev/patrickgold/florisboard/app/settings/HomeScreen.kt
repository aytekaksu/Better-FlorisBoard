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

package dev.patrickgold.florisboard.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.jetpref.datastore.ui.Preference
import org.florisboard.lib.compose.FlorisErrorCard
import org.florisboard.lib.compose.FlorisWarningCard
import org.florisboard.lib.compose.stringRes

private class HomeDestination(
    val icon: ImageVector,
    @param:StringRes val titleRes: Int,
    val navigate: NavController.() -> Unit,
)

private fun <T : Any> homeDestination(
    icon: ImageVector,
    @StringRes titleRes: Int,
    route: T,
) = HomeDestination(icon, titleRes) { navigate(route) }

private val HomeDestinations = listOf(
    homeDestination(Icons.Default.Language, R.string.settings__localization__title, Routes.Settings.Localization),
    homeDestination(Icons.Outlined.Palette, R.string.settings__theme__title, Routes.Settings.Theme),
    homeDestination(Icons.Outlined.Keyboard, R.string.settings__keyboard__title, Routes.Settings.Keyboard),
    homeDestination(Icons.Default.SmartButton, R.string.settings__smartbar__title, Routes.Settings.Smartbar),
    homeDestination(Icons.Default.Spellcheck, R.string.settings__typing__title, Routes.Settings.Typing),
    homeDestination(Icons.Default.Gesture, R.string.settings__gestures__title, Routes.Settings.Gestures),
    homeDestination(
        Icons.AutoMirrored.Outlined.Assignment,
        R.string.settings__clipboard__title,
        Routes.Settings.Clipboard,
    ),
    homeDestination(Icons.Default.SentimentSatisfiedAlt, R.string.settings__media__title, Routes.Settings.Media),
    homeDestination(Icons.Default.Extension, R.string.ext__home__title, Routes.Ext.Home),
    homeDestination(Icons.Outlined.Build, R.string.settings__other__title, Routes.Settings.Other),
    homeDestination(Icons.Outlined.Info, R.string.about__title, Routes.Settings.About),
)

@Composable
fun HomeScreen() = FlorisScreen {
    title = stringRes(R.string.settings__home__title)
    navigationIconVisible = false
    previewFieldVisible = true

    val navController = LocalNavController.current
    val context = LocalContext.current

    content {
        val isFlorisBoardEnabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
        val isFlorisBoardSelected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
        if (!isFlorisBoardEnabled) {
            FlorisErrorCard(
                modifier = Modifier.padding(8.dp),
                showIcon = false,
                text = stringRes(R.string.settings__home__ime_not_enabled),
                onClick = { InputMethodUtils.showImeEnablerActivity(context) },
            )
        } else if (!isFlorisBoardSelected) {
            FlorisWarningCard(
                modifier = Modifier.padding(8.dp),
                showIcon = false,
                text = stringRes(R.string.settings__home__ime_not_selected),
                onClick = { InputMethodUtils.showImePicker(context) },
            )
        }

        HomeDestinations.forEach { destination ->
            Preference(
                icon = destination.icon,
                title = stringRes(destination.titleRes),
                onClick = { destination.navigate(navController) },
            )
        }
    }
}
