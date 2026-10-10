/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import org.florisboard.lib.compose.conditional

private val SheetOutOfBoundsBgColorInactive = Color(0x00000000)
private val SheetOutOfBoundsBgColorActive = Color(0x52000000)

private val DialogContentEnterTransition = slideInVertically { it }
private val DialogContentExitTransition = slideOutVertically { it }

@Composable
fun BottomSheetHostUi(
    isShowing: Boolean,
    onHide: () -> Unit,
    content: @Composable () -> Unit,
) {
    val bgColorOutOfBounds by animateColorAsState(
        if (isShowing) SheetOutOfBoundsBgColorActive else SheetOutOfBoundsBgColorInactive
    )
    Column(Modifier.background(bgColorOutOfBounds)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .conditional(isShowing) {
                    pointerInput(Unit) {
                        detectTapGestures {
                            onHide()
                        }
                    }
                },
        )
        AnimatedVisibility(
            visible = isShowing,
            enter = DialogContentEnterTransition,
            exit = DialogContentExitTransition,
            content = { content() },
        )
    }
}
