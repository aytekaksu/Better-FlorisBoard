/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.smartbar.ExtendedActionsPlacement
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState

internal val LocalKeyboardRowBaseHeight = compositionLocalOf { 65.dp }
internal val LocalSmartbarHeight = compositionLocalOf { 40.dp }

object FlorisImeSizing {
    val keyboardRowBaseHeight: Dp
        @Composable
        @ReadOnlyComposable
        get() = LocalKeyboardRowBaseHeight.current

    val smartbarHeight: Dp
        @Composable
        @ReadOnlyComposable
        get() = LocalSmartbarHeight.current

    @Composable
    fun keyboardUiHeight(): Dp {
        val context = LocalContext.current
        val keyboardManager by context.keyboardManager()
        val evaluator by keyboardManager.activeEvaluator.collectAsState()
        val lastCharactersEvaluator by keyboardManager.lastCharactersEvaluator.collectAsState()
        val rowCount = when (evaluator.keyboard.mode) {
            KeyboardMode.CHARACTERS,
            KeyboardMode.NUMERIC_ADVANCED,
            KeyboardMode.SYMBOLS,
            KeyboardMode.SYMBOLS2,
            -> lastCharactersEvaluator.keyboard

            else -> evaluator.keyboard
        }.rowCount.coerceAtLeast(4)
        return (keyboardRowBaseHeight * rowCount)
    }

    @Composable
    fun rowCountAsState(): State<Int> {
        val context = LocalContext.current
        val keyboardManager by context.keyboardManager()
        val lastCharactersEvaluator by keyboardManager.lastCharactersEvaluator.collectAsState()
        return remember { derivedStateOf { lastCharactersEvaluator.keyboard.rowCount } }
    }

    @Composable
    fun smartbarRowCountAsState(): State<Int> {
        val prefs by FlorisPreferenceStore
        val smartbarEnabled by prefs.smartbar.enabled.collectAsState()
        val smartbarLayout by prefs.smartbar.layout.collectAsState()
        val extendedActionsExpanded by prefs.smartbar.extendedActionsExpanded.collectAsState()
        val extendedActionsPlacement by prefs.smartbar.extendedActionsPlacement.collectAsState()
        return remember {
            derivedStateOf {
                if (smartbarEnabled) {
                    if (smartbarLayout == SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED && extendedActionsExpanded &&
                        extendedActionsPlacement != ExtendedActionsPlacement.OVERLAY_APP_UI
                    ) {
                        2
                    } else {
                        1
                    }
                } else {
                    0
                }
            }
        }
    }

    @Composable
    fun smartbarUiHeight(): Dp {
        val smartbarRowCount by smartbarRowCountAsState()
        return smartbarHeight * smartbarRowCount
    }

    @Composable
    fun imeUiHeight(): Dp = keyboardUiHeight() + smartbarUiHeight()
}
