/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.window

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.LayoutDirection
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.input.LocalInputFeedbackController
import dev.patrickgold.florisboard.ime.theme.FlorisImeTheme
import org.florisboard.lib.compose.ProvideLocalizedResources

/**
 * Provides the [ImeWindowController] instance this composition tree is associated with.
 */
val LocalWindowController = staticCompositionLocalOf<ImeWindowController> {
    error("This composition local provider is only available within an IME view")
}

/**
 * The main entry point and bridge between the IME dialog view and the composables. It will fill the maximum area
 * available within the accompanying dialog view, and also draw under system bars.
 *
 * The layout direction will be forced to [LayoutDirection.Ltr], to ensure the window positioning logic's left/right
 * corresponds to the physical left/right. For UI components that need to conform to the actual system layout
 * direction, the UI components should be wrapped with [org.florisboard.lib.compose.ProvideActualLayoutDirection].
 *
 * @see ImeRootWindow
 */
@SuppressLint("ViewConstructor")
class ImeRootView(val ims: FlorisImeService) : AbstractComposeView(ims) {
    init {
        isHapticFeedbackEnabled = true
        layoutParams = LayoutParams(
            /* width = */ LayoutParams.MATCH_PARENT,
            /* height = */ LayoutParams.MATCH_PARENT,
        )
    }

    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalInputFeedbackController provides ims.inputFeedbackController,
            LocalWindowController provides ims.windowController,
        ) {
            ProvideLocalizedResources(
                resourcesContext = ims.resourcesContext,
                appName = R.string.app_name,
                forceLayoutDirection = LayoutDirection.Ltr,
            ) {
                FlorisImeTheme {
                    ImeRootWindow()
                }
            }
        }
    }

    override fun getAccessibilityClassName(): CharSequence? {
        return this::class.simpleName
    }
}
