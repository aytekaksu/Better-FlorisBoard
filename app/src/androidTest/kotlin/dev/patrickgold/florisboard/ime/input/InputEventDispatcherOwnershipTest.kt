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

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputEventDispatcherOwnershipTest {
    @Test
    fun staleSameCodeOwnerCannotReleaseReplacementPress() {
        val dispatcher = InputEventDispatcher.new()
        val events = mutableListOf<String>()
        val data = TextKeyData(code = 'n'.code, label = "n")
        dispatcher.keyEventReceiver = RecordingReceiver(events)

        try {
            val firstOwner = dispatcher.sendDown(
                data = data,
                allowLongPress = false,
                allowRepeat = false,
            )
            assertNotNull(firstOwner)
            assertTrue(dispatcher.sendCancel(data, firstOwner))

            val replacementOwner = dispatcher.sendDown(
                data = data,
                allowLongPress = false,
                allowRepeat = false,
            )
            assertNotNull(replacementOwner)

            assertFalse(dispatcher.sendUp(data, firstOwner))
            assertFalse(dispatcher.sendCancel(data, firstOwner))
            assertTrue(dispatcher.isPressed(data, replacementOwner!!))
            assertTrue(dispatcher.sendUp(data, replacementOwner))
            assertFalse(dispatcher.isPressed(data.code))

            assertEquals(
                listOf("down:n", "cancel:n", "down:n", "up:n"),
                events,
            )
        } finally {
            dispatcher.close()
        }
    }

    private class RecordingReceiver(
        private val events: MutableList<String>,
    ) : InputKeyEventReceiver {
        override fun onInputKeyDown(data: KeyData) {
            events.add("down:${data.asString(isForDisplay = false)}")
        }

        override fun onInputKeyUp(data: KeyData) {
            events.add("up:${data.asString(isForDisplay = false)}")
        }

        override fun onInputKeyRepeat(data: KeyData) {
            events.add("repeat:${data.asString(isForDisplay = false)}")
        }

        override fun onInputKeyCancel(data: KeyData) {
            events.add("cancel:${data.asString(isForDisplay = false)}")
        }
    }
}
