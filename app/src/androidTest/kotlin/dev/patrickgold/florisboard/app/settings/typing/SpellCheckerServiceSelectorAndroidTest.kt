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

package dev.patrickgold.florisboard.app.settings.typing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpellCheckerServiceSelectorAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun lookupAndIconRenderingRunOffMainOncePerSelection() {
        val loads = AtomicInteger()
        val lookupThread = AtomicReference<Thread>()
        val firstIcon = AtomicReference<ThreadRecordingDrawable>()
        val secondIcon = AtomicReference<ThreadRecordingDrawable>()
        val clicks = AtomicInteger()
        var selectedId by mutableStateOf("example.pkg/.SpellChecker")
        var unrelated by mutableIntStateOf(0)
        composeRule.setContent {
            val context = LocalContext.current
            ProvideLocalizedResources(context, R.string.app_name) {
                SpellCheckerServiceSelectorContent(
                    selectedId = selectedId,
                    enabled = "1",
                    loader = { _, _ ->
                        lookupThread.set(Thread.currentThread())
                        val load = loads.incrementAndGet()
                        val icon = ThreadRecordingDrawable()
                        if (load == 1) firstIcon.set(icon) else secondIcon.set(icon)
                        SpellCheckerPresentation("example.pkg", icon, "Provider $load")
                    },
                    onClick = { clicks.addAndGet(unrelated + 1) },
                )
            }
        }

        waitForText("Provider 1")
        composeRule.waitUntil(timeoutMillis = 10_000) { firstIcon.get()?.drawThread?.get() != null }
        assertNotSame(Looper.getMainLooper().thread, lookupThread.get())
        assertNotSame(Looper.getMainLooper().thread, firstIcon.get().drawThread.get())
        assertEquals(1, firstIcon.get().draws.get())
        composeRule.runOnIdle { unrelated++ }
        composeRule.waitForIdle()
        assertEquals(1, loads.get())
        assertEquals(1, firstIcon.get().draws.get())
        composeRule.onNodeWithText("Provider 1").performClick()
        assertEquals(2, clicks.get())
        composeRule.runOnIdle { selectedId = "example.pkg/.OtherSpellChecker" }
        waitForText("Provider 2")
        composeRule.waitUntil(timeoutMillis = 10_000) { secondIcon.get()?.drawThread?.get() != null }
        assertEquals(2, loads.get())
        assertNotSame(Looper.getMainLooper().thread, secondIcon.get().drawThread.get())
        assertEquals(1, secondIcon.get().draws.get())
    }

    @Test
    fun obsoleteSelectionCannotReplaceCurrentCardAfterLateLookup() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var selectedId by mutableStateOf("example.a/.SpellChecker")
        try {
            composeRule.setContent {
                val context = LocalContext.current
                ProvideLocalizedResources(context, R.string.app_name) {
                    SpellCheckerServiceSelectorContent(
                        selectedId = selectedId,
                        enabled = "1",
                        loader = { _, id ->
                            if (id == "example.a/.SpellChecker") {
                                started.countDown()
                                while (true) {
                                    try {
                                        check(release.await(10, TimeUnit.SECONDS))
                                        break
                                    } catch (_: InterruptedException) {
                                        // Model a package lookup that finishes despite cancellation.
                                    }
                                }
                                finished.countDown()
                                SpellCheckerPresentation("example.a", null, "Stale provider")
                            } else {
                                SpellCheckerPresentation("example.b", null, "Current provider")
                            }
                        },
                        onClick = {},
                    )
                }
            }

            assertTrue(started.await(10, TimeUnit.SECONDS))
            composeRule.runOnIdle { selectedId = "example.b/.SpellChecker" }
            waitForText("Current provider")
            assertEquals(1L, finished.count)
            release.countDown()
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Current provider").assertIsDisplayed()
            composeRule.onNodeWithText("Stale provider").assertDoesNotExist()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun disabledMissingAndUnknownCardsStayDistinct() {
        val loads = AtomicInteger()
        var enabled by mutableStateOf("0")
        var selectedId by mutableStateOf("example.pkg/.SpellChecker")
        lateinit var context: Context
        composeRule.setContent {
            context = LocalContext.current
            ProvideLocalizedResources(context, R.string.app_name) {
                SpellCheckerServiceSelectorContent(
                    selectedId = selectedId,
                    enabled = enabled,
                    loader = { _, _ ->
                        when (loads.incrementAndGet()) {
                            1 -> null
                            2 -> SpellCheckerPresentation("example.pkg", null, "Unknown")
                            else -> throw SecurityException("Synthetic lookup failure")
                        }
                    },
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.pref__spelling__active_spellchecker__summary_disabled),
        ).assertIsDisplayed()
        assertEquals(0, loads.get())
        composeRule.runOnIdle { enabled = "1" }
        waitForText(context.getString(R.string.pref__spelling__active_spellchecker__summary_none))
        composeRule.runOnIdle { selectedId = "example.pkg/.OtherSpellChecker" }
        waitForText("Unknown")
        composeRule.onNodeWithText("example.pkg").assertIsDisplayed()
        assertEquals(2, loads.get())
        composeRule.runOnIdle { selectedId = "example.pkg/.FailingSpellChecker" }
        waitForText(context.getString(R.string.pref__spelling__active_spellchecker__summary_none))
        assertEquals(3, loads.get())
    }

    @Test
    fun observedSystemSelectorRenders() {
        composeRule.setContent {
            val context = LocalContext.current
            ProvideLocalizedResources(context, R.string.app_name) {
                SpellCheckerServiceSelector()
            }
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class ThreadRecordingDrawable : ColorDrawable(Color.RED) {
        val draws = AtomicInteger()
        val drawThread = AtomicReference<Thread>()

        override fun draw(canvas: Canvas) {
            drawThread.set(Thread.currentThread())
            draws.incrementAndGet()
            super.draw(canvas)
        }
    }
}
