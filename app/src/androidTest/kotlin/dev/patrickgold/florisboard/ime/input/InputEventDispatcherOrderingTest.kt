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
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputEventDispatcherOrderingTest {
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

    private companion object {
        val letter = TextKeyData(code = 'a'.code, label = "a")
    }
}
