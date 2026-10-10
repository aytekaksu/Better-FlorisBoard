/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggButton
import org.florisboard.lib.snygg.ui.SnyggText

@Composable
fun QuickActionsOverflowPanel() {
    val prefs by FlorisPreferenceStore
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()

    val actionArrangement by prefs.smartbar.actionArrangement.collectAsState()
    val evaluator by keyboardManager.activeSmartbarEvaluator.collectAsState()

    val shownDynamicActions = keyboardManager.smartbarVisibleDynamicActionsCount
    val visibleActions = remember(actionArrangement, shownDynamicActions) {
        actionArrangement.overflowActions(shownDynamicActions)
    }
    val minimumTileWidth = quickActionOverflowMinimumWidth(
        smartbarHeight = FlorisImeSizing.smartbarHeight,
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
    )

    SnyggBox(
        elementName = FlorisImeUi.SmartbarActionsOverflow.elementName,
        modifier = Modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.keyboardUiHeight()),
    ) {
        LazyVerticalGrid(
            modifier = Modifier
                .fillMaxWidth(),
            columns = GridCells.Adaptive(minimumTileWidth),
        ) {
            items(visibleActions) { action ->
                QuickActionButton(
                    action = action,
                    evaluator = evaluator,
                    type = QuickActionBarType.INTERACTIVE_TILE,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SnyggButton(
                    elementName = FlorisImeUi.SmartbarActionsOverflowCustomizeButton.elementName,
                    onClick = { keyboardManager.activeState.isActionsEditorVisible = true },
                    modifier = Modifier
                        .wrapContentWidth(),
                ) {
                    SnyggText(
                        text = stringRes(R.string.quick_actions_overflow__customize_actions_button),
                    )
                }
            }
        }
    }
}

internal fun quickActionOverflowMinimumWidth(smartbarHeight: Dp, isLandscape: Boolean): Dp {
    val responsiveHeight = if (isLandscape) {
        smartbarHeight.coerceAtLeast(48.dp)
    } else {
        smartbarHeight
    }
    return responsiveHeight.coerceAtLeast(1.dp) * 2.2f
}
