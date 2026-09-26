/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.localization

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.florisboard.lib.compose.FlorisDropdownLikeButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlorisDropdownLikeButtonAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun heldPressCallsOnceAcrossRecompositionAndNextPressUsesLatestCallback() {
        var firstCalls = 0
        var nextCalls = 0
        var item by mutableStateOf("First")
        var onClick by mutableStateOf<() -> Unit>({ firstCalls++ })
        composeRule.setContent {
            MaterialTheme {
                FlorisDropdownLikeButton(
                    item = item,
                    modifier = Modifier.testTag("dropdown"),
                    onClick = onClick,
                )
            }
        }

        val dropdown = composeRule.onNodeWithTag("dropdown")
        dropdown.performTouchInput { down(center) }
        composeRule.waitForIdle()
        assertEquals(1, firstCalls)

        composeRule.runOnIdle {
            item = "Second"
            onClick = { nextCalls++ }
        }
        composeRule.waitForIdle()
        assertEquals(1, firstCalls)
        assertEquals(0, nextCalls)

        dropdown.performTouchInput { up() }
        composeRule.waitForIdle()
        assertEquals(1, firstCalls)
        assertEquals(0, nextCalls)

        dropdown.performTouchInput { down(center) }
        composeRule.waitForIdle()
        assertEquals(1, firstCalls)
        assertEquals(1, nextCalls)
        dropdown.performTouchInput { up() }
    }
}
