/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.sheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.ime.core.SelectSubtypePanel
import dev.patrickgold.florisboard.ime.keyboard.KeyboardState
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionsEditorPanel
import dev.patrickgold.florisboard.keyboardManager
import kotlin.getValue

@Composable
fun BottomSheetWindow() {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val state by keyboardManager.activeState.collectAsState()
    var actionsEditorClose by remember { mutableStateOf<(() -> Unit)?>(null) }

    BottomSheetHostUi(
        isShowing = state.isAnyBottomSheetVisible(),
        onHide = {
            if (state.isActionsEditorVisible) {
                actionsEditorClose?.invoke()
                    ?: run { keyboardManager.activeState.isActionsEditorVisible = false }
            }
            if (state.isSubtypeSelectionVisible) {
                keyboardManager.activeState.isSubtypeSelectionVisible = false
            }
        },
    ) {
        if (state.isActionsEditorVisible) {
            QuickActionsEditorPanel { actionsEditorClose = it }
        }
        if (state.isSubtypeSelectionVisible) {
            SelectSubtypePanel()
        }
    }
}

fun KeyboardState.isAnyBottomSheetVisible(): Boolean {
    return isActionsEditorVisible || isSubtypeSelectionVisible
}
