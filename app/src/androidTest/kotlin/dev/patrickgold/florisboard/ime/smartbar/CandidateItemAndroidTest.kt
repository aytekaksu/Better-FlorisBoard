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

package dev.patrickgold.florisboard.ime.smartbar

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import org.florisboard.lib.snygg.SnyggStylesheet
import org.florisboard.lib.snygg.ui.ProvideSnyggTheme
import org.florisboard.lib.snygg.ui.rememberSnyggTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidateItemAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun replacingCandidateDuringPressCancelsOldGesture() {
        var candidate by mutableStateOf(
            WordSuggestionCandidate("first"),
            referentialEqualityPolicy(),
        )
        val committed = mutableListOf<String>()
        val removed = mutableListOf<String>()
        composeRule.setContent {
            val displayed = candidate
            CandidateTheme {
                CandidateItem(
                    candidate = displayed,
                    displayMode = CandidatesDisplayMode.CLASSIC,
                    appearance = CandidateAppearance(false, 1f, false),
                    modifier = Modifier.size(180.dp, 48.dp).testTag("candidate"),
                    onClick = { committed += displayed.text.toString() },
                    onLongPress = { removed += displayed.text.toString(); true },
                    longPressDelay = 10_000,
                )
            }
        }

        val item = composeRule.onNodeWithTag("candidate")
        item.performTouchInput { down(center) }
        composeRule.runOnIdle { candidate = WordSuggestionCandidate("second") }
        item.performTouchInput { up() }
        composeRule.runOnIdle {
            assertEquals(emptyList<String>(), committed)
            assertEquals(emptyList<String>(), removed)
        }

        item.performTouchInput { down(center); up() }
        composeRule.runOnIdle { assertEquals(listOf("second"), committed) }
    }

    @Test
    fun equivalentNewCandidateAlsoCancelsPress() {
        var candidate by mutableStateOf(
            WordSuggestionCandidate("same"),
            referentialEqualityPolicy(),
        )
        var commits = 0
        composeRule.setContent {
            CandidateTheme {
                CandidateItem(
                    candidate = candidate,
                    displayMode = CandidatesDisplayMode.CLASSIC,
                    appearance = CandidateAppearance(false, 1f, false),
                    modifier = Modifier.size(180.dp, 48.dp).testTag("candidate"),
                    onClick = { commits++ },
                    longPressDelay = 10_000,
                )
            }
        }

        val item = composeRule.onNodeWithTag("candidate")
        item.performTouchInput { down(center) }
        composeRule.runOnIdle { candidate = WordSuggestionCandidate("same") }
        item.performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(0, commits) }
    }

    @Test
    fun stableCandidateUsesLatestCallbackAfterRecomposition() {
        val candidate = WordSuggestionCandidate("stable")
        var oldCalls = 0
        var newCalls = 0
        var onClick by mutableStateOf<() -> Unit>({ oldCalls++ })
        composeRule.setContent {
            CandidateTheme {
                CandidateItem(
                    candidate = candidate,
                    displayMode = CandidatesDisplayMode.CLASSIC,
                    appearance = CandidateAppearance(false, 1f, false),
                    modifier = Modifier.size(180.dp, 48.dp).testTag("candidate"),
                    onClick = onClick,
                    longPressDelay = 10_000,
                )
            }
        }

        val item = composeRule.onNodeWithTag("candidate")
        item.performTouchInput { down(center) }
        composeRule.runOnIdle { onClick = { newCalls++ } }
        item.performTouchInput { up() }
        composeRule.runOnIdle {
            assertEquals(0, oldCalls)
            assertEquals(1, newCalls)
        }
    }

    @Test
    fun successfulLongPressDoesNotCommit() {
        var commits = 0
        var removals = 0
        composeRule.setContent {
            CandidateTheme {
                CandidateItem(
                    candidate = WordSuggestionCandidate("held"),
                    displayMode = CandidatesDisplayMode.CLASSIC,
                    appearance = CandidateAppearance(false, 1f, false),
                    modifier = Modifier.size(180.dp, 48.dp).testTag("candidate"),
                    onClick = { commits++ },
                    onLongPress = { removals++; true },
                    longPressDelay = 100,
                )
            }
        }

        val item = composeRule.onNodeWithTag("candidate")
        item.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(150)
        item.performTouchInput { up() }
        composeRule.runOnIdle {
            assertEquals(0, commits)
            assertEquals(1, removals)
        }
    }

    @Test
    fun changedLongPressDelayAppliesOnNextPress() {
        val candidate = WordSuggestionCandidate("stable")
        var delay by mutableStateOf(10_000L)
        var commits = 0
        var removals = 0
        composeRule.setContent {
            CandidateTheme {
                CandidateItem(
                    candidate = candidate,
                    displayMode = CandidatesDisplayMode.CLASSIC,
                    appearance = CandidateAppearance(false, 1f, false),
                    modifier = Modifier.size(180.dp, 48.dp).testTag("candidate"),
                    onClick = { commits++ },
                    onLongPress = { removals++; true },
                    longPressDelay = delay,
                )
            }
        }

        val item = composeRule.onNodeWithTag("candidate")
        item.performTouchInput { down(center); up() }
        composeRule.runOnIdle { delay = 100L }
        item.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(150)
        item.performTouchInput { up() }
        composeRule.runOnIdle {
            assertEquals(1, commits)
            assertEquals(1, removals)
        }
    }
}

@Composable
private fun CandidateTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        ProvideSnyggTheme(rememberSnyggTheme(SnyggStylesheet.v2 {}), content = content)
    }
}
