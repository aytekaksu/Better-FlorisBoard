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

package dev.patrickgold.florisboard.ime.input

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputEventDispatcherOrderingTest {
    @Test
    fun physicalStateAdvancesWhileSemanticEventsWaitInOrder() {
        val dispatcher = InputEventDispatcher.new()
        val events = mutableListOf<String>()
        var resume: (() -> Unit)? = null
        dispatcher.keyEventReceiver = recordingReceiver(dispatcher, events)
        dispatcher.deferInputEvents { resume = it }

        dispatcher.sendDown(TextKeyData.SHIFT, allowLongPress = false, allowRepeat = false)
        dispatcher.sendDown(letter, allowLongPress = false, allowRepeat = false)
        dispatcher.sendUp(letter)
        dispatcher.sendUp(TextKeyData.SHIFT)

        assertTrue(events.isEmpty())
        assertFalse(dispatcher.isAnyPressed())
        resume?.invoke()

        assertEquals(
            listOf(
                "down:-11:true",
                "down:97:true",
                "up:97:true",
                "up:-11:false",
            ),
            events,
        )
        dispatcher.close()
    }

    @Test
    fun delayedReplayUsesPhysicalDoubleTapTime() {
        val dispatcher = InputEventDispatcher.new()
        val consecutiveUps = mutableListOf<Boolean>()
        var resume: (() -> Unit)? = null
        dispatcher.keyEventReceiver = object : InputKeyEventReceiver {
            override fun onInputKeyDown(data: KeyData) = Unit

            override fun onInputKeyUp(data: KeyData) {
                consecutiveUps.add(dispatcher.isConsecutiveUp(data))
            }

            override fun onInputKeyRepeat(data: KeyData) = Unit

            override fun onInputKeyCancel(data: KeyData) = Unit
        }
        dispatcher.deferInputEvents { resume = it }

        dispatcher.sendDownUp(TextKeyData.SPACE)
        dispatcher.sendDownUp(TextKeyData.SPACE)
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50L)
        resume?.invoke()

        assertEquals(listOf(false, true), consecutiveUps)
        dispatcher.close()
    }

    @Test
    fun lifecycleInvalidationClearsDroppedDoubleTapHistory() {
        val dispatcher = InputEventDispatcher.new()
        val consecutiveEvents = mutableListOf<Boolean>()
        var invalidations = 0
        dispatcher.keyEventReceiver = object : InputKeyEventReceiver {
            override fun onInputKeyDown(data: KeyData) {
                consecutiveEvents.add(dispatcher.isConsecutiveDown(data))
            }

            override fun onInputKeyUp(data: KeyData) {
                consecutiveEvents.add(dispatcher.isConsecutiveUp(data))
            }

            override fun onInputKeyRepeat(data: KeyData) = Unit

            override fun onInputKeyCancel(data: KeyData) = Unit
        }
        dispatcher.deferInputEvents(
            onInvalidated = { invalidations += 1 },
            start = {},
        )
        dispatcher.sendDownUp(TextKeyData.SPACE)
        dispatcher.invalidatePendingInputEvents()

        dispatcher.sendDownUp(TextKeyData.SPACE)

        assertEquals(listOf(false, false), consecutiveEvents)
        assertEquals(1, invalidations)
        dispatcher.close()
    }

    @Test
    fun aQueuedRepeatCannotBlockTheOrdinaryReleaseAfterTheKeyIsLifted() {
        val prefs by FlorisPreferenceStore
        val previousLongPressDelay = prefs.keyboard.longPressDelay.get()
        runBlocking { prefs.keyboard.longPressDelay.set(100).getOrThrow() }
        val dispatcher = InputEventDispatcher.new(intArrayOf(letter.code))
        try {
            val events = mutableListOf<String>()
            val repeatTick = CountDownLatch(1)
            var resume: (() -> Unit)? = null
            dispatcher.keyEventReceiver = object : InputKeyEventReceiver {
                override fun onInputKeyDown(data: KeyData) {
                    events.add("down")
                }

                override fun onInputKeyUp(data: KeyData) {
                    events.add("up")
                }

                override fun onInputKeyRepeat(data: KeyData) {
                    events.add("repeat")
                }

                override fun onInputKeyCancel(data: KeyData) {
                    events.add("cancel")
                }
            }
            dispatcher.deferInputEvents { resume = it }

            dispatcher.sendDown(
                data = letter,
                allowLongPress = false,
                onRepeat = {
                    repeatTick.countDown()
                    true
                },
            )
            assertTrue(repeatTick.await(3, TimeUnit.SECONDS))
            SystemClock.sleep(100L)
            dispatcher.sendUp(letter)
            resume?.invoke()

            assertEquals(listOf("down", "up"), events)
        } finally {
            dispatcher.close()
            runBlocking {
                prefs.keyboard.longPressDelay.set(previousLongPressDelay).getOrThrow()
            }
        }
    }

    private fun recordingReceiver(
        dispatcher: InputEventDispatcher,
        events: MutableList<String>,
    ) = object : InputKeyEventReceiver {
        override fun onInputKeyDown(data: KeyData) {
            events.add("down:${data.code}:${dispatcher.isPressed(TextKeyData.SHIFT.code)}")
        }

        override fun onInputKeyUp(data: KeyData) {
            events.add("up:${data.code}:${dispatcher.isPressed(TextKeyData.SHIFT.code)}")
        }

        override fun onInputKeyRepeat(data: KeyData) = Unit

        override fun onInputKeyCancel(data: KeyData) = Unit
    }

    private companion object {
        val letter = TextKeyData(code = 'a'.code, label = "a")
    }
}
