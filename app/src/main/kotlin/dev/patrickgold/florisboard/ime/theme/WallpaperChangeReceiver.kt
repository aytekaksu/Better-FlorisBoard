/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.themeManager
import kotlinx.coroutines.flow.update

class WallpaperChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        if (context == null) return
        @Suppress("DEPRECATION") // We do not retrieve the wallpaper but only listen to changes
        if (intent.action == Intent.ACTION_WALLPAPER_CHANGED) {
            flogDebug { "Wallpaper changed" }
            val themeManager by context.themeManager()
            themeManager.configurationChangeCounter.update { it + 1 }
        }
    }
}
