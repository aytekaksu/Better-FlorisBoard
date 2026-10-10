/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupStepLayoutAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun headersUseDisplayedOrderAndOnlyReachedStepsCanBeSelected() {
        val state = SetupStepState.new(initial = 2)
        composeRule.setContent {
            MaterialTheme {
                SetupStepLayout(
                    stepState = state,
                    steps = listOf(
                        SetupStep(1, "Enable") { StepText("Enable content") },
                        SetupStep(2, "Select") { StepText("Select content") },
                        SetupStep(4, "Finish") { StepText("Finish content") },
                    ),
                )
            }
        }

        listOf("1", "2", "3", "Enable", "Select", "Finish").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithText("4").assertDoesNotExist()
        composeRule.onNodeWithText("Finish content").assertDoesNotExist()

        composeRule.onNodeWithText("Finish").performTouchInput {
            down(center)
            up()
        }
        composeRule.runOnIdle { assertEquals(2, state.current) }

        composeRule.onNodeWithText("Enable").performClick()
        composeRule.onNodeWithText("Enable content").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, state.current) }

        composeRule.runOnIdle { state.updateAutomatic(4) }
        composeRule.onNodeWithText("Finish").performClick()
        composeRule.runOnIdle { assertEquals(4, state.current) }
    }

    @Test
    fun manualAndAutomaticProgressSurviveStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        lateinit var state: SetupStepState
        restoration.setContent {
            state = rememberSaveable(saver = SetupStepState.Saver) {
                SetupStepState.new(initial = 1)
            }
            Text("Current ${state.current}")
        }

        composeRule.runOnIdle {
            state.updateAutomatic(4)
            state.select(2)
        }
        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Current 2").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(4, state.automatic)
            assertEquals(2, state.manual)
        }
    }
}
