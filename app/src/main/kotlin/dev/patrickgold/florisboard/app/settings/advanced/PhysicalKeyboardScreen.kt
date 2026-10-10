/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.stringRes

@Composable
fun PhysicalKeyboardScreen() = FlorisScreen {
    title = stringRes(R.string.physical_keyboard__title)

    val physicalKeyboardAttached =
        LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS

    val activityForResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    content {
        if (physicalKeyboardAttached) {
            Preference(
                title = stringRes(R.string.physical_keyboard__system_settings__title),
                summary = stringRes(R.string.physical_keyboard__system_settings__summary),
                onClick = {
                    activityForResult.launch(Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS))
                }
            )
        } else {
            Preference(
                title = stringRes(R.string.physical_keyboard__system_settings__title),
                summary = stringRes(R.string.physical_keyboard__system_settings__summary_not_attached),
            )
        }
        SwitchPreference(
            pref = prefs.physicalKeyboard.showOnScreenKeyboard,
            title = stringRes(R.string.physical_keyboard__show_on_screen_keyboard__title),
            summary = stringRes(R.string.physical_keyboard__show_on_screen_keyboard__summary),
        )
    }
}
