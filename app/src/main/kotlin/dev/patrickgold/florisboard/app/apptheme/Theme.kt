/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.apptheme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.color.systemAccentOrDefault

@Composable
fun getColorScheme(
    theme: AppTheme,
): ColorScheme {
    val prefs by FlorisPreferenceStore
    val accentColor by prefs.other.accentColor.collectAsState()

    val seedColor = systemAccentOrDefault(accentColor)

    val isDark = when (theme) {
        AppTheme.AUTO, AppTheme.AUTO_AMOLED -> isSystemInDarkTheme()
        AppTheme.DARK, AppTheme.AMOLED_DARK -> true
        AppTheme.LIGHT -> false
    }
    return dynamicColorScheme(
        primary = seedColor,
        isDark = isDark,
        isAmoled = theme == AppTheme.AUTO_AMOLED || theme == AppTheme.AMOLED_DARK,
        style = PaletteStyle.Neutral,
    )
}

@Composable
fun FlorisAppTheme(
    theme: AppTheme,
    content: @Composable () -> Unit,
) {
    val colors = getColorScheme(theme = theme)

    val darkTheme =
        theme == AppTheme.DARK
            || theme == AppTheme.AMOLED_DARK
            || (theme == AppTheme.AUTO && isSystemInDarkTheme())
            || (theme == AppTheme.AUTO_AMOLED && isSystemInDarkTheme())

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content,
    )
}
