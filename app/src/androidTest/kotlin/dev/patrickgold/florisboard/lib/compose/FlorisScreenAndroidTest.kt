/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.ApplicationBootstrapState
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlorisScreenAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun previewOnlyChangesShowAndHideSharedField(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<FlorisApplication>()
        assertEquals(ApplicationBootstrapState.READY, withTimeout(20_000L) {
            context.applicationBootstrapState.first { it != ApplicationBootstrapState.LOADING }
        })
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var showPreview by mutableStateOf(false)

        composeRule.setContent {
            val preview = rememberPreviewFieldController()
            ProvideLocalizedResources(context, R.string.app_name) {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalPreviewFieldController provides preview,
                        LocalLayoutDirection provides direction,
                    ) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                            Box(Modifier.weight(1f)) {
                                FlorisScreen {
                                    title = "Preview screen"
                                    navigationIconVisible = false
                                    previewFieldVisible = showPreview
                                }
                            }
                            PreviewKeyboardField(preview, hint = "Screen preview")
                        }
                    }
                }
            }
        }

        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            composeRule.runOnIdle { direction = layoutDirection }
            composeRule.onNodeWithText("Screen preview").assertDoesNotExist()

            composeRule.runOnIdle { showPreview = true }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onNodeWithText("Screen preview").isDisplayed()
            }
            composeRule.onNodeWithText("Screen preview").assertIsDisplayed()

            composeRule.runOnIdle { showPreview = false }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Screen preview").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithText("Screen preview").assertDoesNotExist()
        }
    }
}
