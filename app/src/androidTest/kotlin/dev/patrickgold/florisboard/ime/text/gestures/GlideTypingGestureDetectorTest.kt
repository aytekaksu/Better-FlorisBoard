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

package dev.patrickgold.florisboard.ime.text.gestures

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.DefaultComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [GlideTypingGesture.Detector] with framework [MotionEvent] instances. These cases stay
 * below the keyboard layout so failures identify detector state handling rather than Compose event
 * dispatch or editor behavior.
 */
@RunWith(AndroidJUnit4::class)
class GlideTypingGestureDetectorTest {
    private lateinit var context: Context
    private var previousSensitive = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs by FlorisPreferenceStore
        previousSensitive = prefs.glide.sensitive.get()
        runBlocking { prefs.glide.sensitive.set(false).getOrThrow() }
    }

    @After
    fun tearDown() {
        val prefs by FlorisPreferenceStore
        runBlocking { prefs.glide.sensitive.set(previousSensitive).getOrThrow() }
    }

    @Test
    fun nonzeroPointerIdCompletesAWordKeyGlide() {
        val detector = GlideTypingGesture.Detector(context)
        val listener = RecordingListener()
        val letter = key('a')
        detector.registerListener(listener)

        val downTime = 1_000L
        assertFalse(detector.onTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN, 17, 0f), letter, letter))
        assertEquals(17, detector.activePointerId)
        assertTrue(detector.onTouchEvent(event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, 17, glideDistancePx()), letter, letter))
        assertFalse(detector.onTouchEvent(event(downTime, downTime + 20, MotionEvent.ACTION_UP, 17, glideDistancePx()), letter, letter))

        assertTrue(listener.points > 0)
        assertEquals(1, listener.completions)
        assertEquals(0, listener.cancellations)
        assertEquals(-1, detector.activePointerId)
    }

    @Test
    fun glideRequiresWordKeysAtTheDetectionThreshold() {
        val letter = key('a')

        fun verifyNotDetected(initialKey: TextKey, currentKey: TextKey) {
            val detector = GlideTypingGesture.Detector(context)
            val listener = RecordingListener()
            detector.registerListener(listener)
            val downTime = 2_000L

            detector.onTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN, 7, 0f), initialKey, initialKey)
            assertFalse(
                detector.onTouchEvent(
                    event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, 7, glideDistancePx()),
                    initialKey,
                    currentKey,
                ),
            )
            detector.onTouchEvent(
                event(downTime, downTime + 20, MotionEvent.ACTION_UP, 7, glideDistancePx()),
                initialKey,
                currentKey,
            )

            assertEquals(0, listener.points)
            assertEquals(0, listener.completions)
            assertEquals(0, listener.cancellations)
        }

        for (control in listOf(
            TextKeyData.SPACE,
            TextKeyData(code = KeyCode.CJK_SPACE, label = "cjk space"),
            TextKeyData.DELETE,
            TextKeyData.SHIFT,
        ).map(::key)) {
            verifyNotDetected(initialKey = control, currentKey = letter)
            verifyNotDetected(initialKey = letter, currentKey = control)
        }
    }

    @Test
    fun secondPointerCancelsAndSuppressesTheRemainderOfTheTouchSequence() {
        val detector = GlideTypingGesture.Detector(context)
        val listener = RecordingListener()
        val letter = key('a')
        detector.registerListener(listener)

        val downTime = 3_000L
        detector.onTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN, 7, 0f), letter, letter)
        assertTrue(
            detector.onTouchEvent(
                event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, 7, glideDistancePx()),
                letter,
                letter,
            ),
        )
        val pointCountBeforeCancellation = listener.points

        assertFalse(
            detector.onTouchEvent(
                pointerDownEvent(
                    downTime = downTime,
                    eventTime = downTime + 20,
                    existingPointerId = 7,
                    newPointerId = 11,
                    existingX = glideDistancePx(),
                ),
                letter,
                letter,
            ),
        )
        assertEquals(1, listener.cancellations)
        assertEquals(-1, detector.activePointerId)

        assertFalse(
            detector.onTouchEvent(
                event(downTime, downTime + 30, MotionEvent.ACTION_MOVE, 7, glideDistancePx() * 1.5f),
                letter,
                letter,
            ),
        )
        detector.onTouchEvent(
            event(downTime, downTime + 40, MotionEvent.ACTION_UP, 7, glideDistancePx() * 1.5f),
            letter,
            letter,
        )
        assertEquals(pointCountBeforeCancellation, listener.points)
        assertEquals(0, listener.completions)
        assertEquals(1, listener.cancellations)

        // A genuinely new touch sequence clears suppression and can glide normally.
        val nextDownTime = 4_000L
        detector.onTouchEvent(event(nextDownTime, nextDownTime, MotionEvent.ACTION_DOWN, 13, 0f), letter, letter)
        assertTrue(
            detector.onTouchEvent(
                event(nextDownTime, nextDownTime + 10, MotionEvent.ACTION_MOVE, 13, glideDistancePx()),
                letter,
                letter,
            ),
        )
    }

    @Test
    fun actionCancelNotifiesOnceForAnActiveGesture() {
        val detector = GlideTypingGesture.Detector(context)
        val listener = RecordingListener()
        val letter = key('a')
        detector.registerListener(listener)

        val downTime = 5_000L
        detector.onTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN, 23, 0f), letter, letter)
        assertTrue(
            detector.onTouchEvent(
                event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, 23, glideDistancePx()),
                letter,
                letter,
            ),
        )
        detector.onTouchEvent(
            event(downTime, downTime + 20, MotionEvent.ACTION_CANCEL, 23, glideDistancePx()),
            letter,
            letter,
        )
        detector.onTouchEvent(
            event(downTime, downTime + 30, MotionEvent.ACTION_CANCEL, 23, glideDistancePx()),
            letter,
            letter,
        )

        assertEquals(1, listener.cancellations)
        assertEquals(0, listener.completions)
        assertEquals(-1, detector.activePointerId)
    }

    @Test
    fun explicitCancellationPreventsAChangedLayoutSequenceFromCompleting() {
        val detector = GlideTypingGesture.Detector(context)
        val listener = RecordingListener()
        val letter = key('a')
        detector.registerListener(listener)

        val downTime = 6_000L
        detector.onTouchEvent(event(downTime, downTime, MotionEvent.ACTION_DOWN, 29, 0f), letter, letter)
        assertTrue(
            detector.onTouchEvent(
                event(downTime, downTime + 10, MotionEvent.ACTION_MOVE, 29, glideDistancePx()),
                letter,
                letter,
            ),
        )
        val pointCountBeforeCancellation = listener.points

        detector.cancel()
        assertFalse(
            detector.onTouchEvent(
                event(downTime, downTime + 20, MotionEvent.ACTION_MOVE, 29, glideDistancePx() * 1.5f),
                letter,
                letter,
            ),
        )
        detector.onTouchEvent(
            event(downTime, downTime + 30, MotionEvent.ACTION_UP, 29, glideDistancePx() * 1.5f),
            letter,
            letter,
        )

        assertEquals(pointCountBeforeCancellation, listener.points)
        assertEquals(0, listener.completions)
        assertEquals(1, listener.cancellations)
        assertEquals(-1, detector.activePointerId)
    }

    private fun key(character: Char): TextKey {
        return key(TextKeyData(code = character.code, label = character.toString()))
    }

    private fun key(data: TextKeyData): TextKey {
        val key = TextKey(data)
        val keyboard = TextKeyboard(
            arrangement = arrayOf(arrayOf(key)),
            mode = KeyboardMode.CHARACTERS,
            extendedPopupMapping = null,
            extendedPopupMappingDefault = null,
        )
        val evaluator = object : ComputingEvaluator by DefaultComputingEvaluator {
            override val keyboard = keyboard
        }
        key.compute(evaluator)
        return key
    }

    private fun GlideTypingGesture.Detector.onTouchEvent(
        event: MotionEvent,
        initialKey: TextKey,
        currentKey: TextKey,
    ) = onTouchEvent(event, initialKey.computedData, currentKey)

    private fun glideDistancePx(): Float {
        return 240f * context.resources.displayMetrics.density
    }

    private fun event(
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointerId: Int,
        x: Float,
    ): MotionEvent {
        return multiPointerEvent(
            downTime = downTime,
            eventTime = eventTime,
            action = action,
            pointerIds = intArrayOf(pointerId),
            pointerXs = floatArrayOf(x),
        )
    }

    private fun pointerDownEvent(
        downTime: Long,
        eventTime: Long,
        existingPointerId: Int,
        newPointerId: Int,
        existingX: Float,
    ): MotionEvent {
        return multiPointerEvent(
            downTime = downTime,
            eventTime = eventTime,
            action = MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            pointerIds = intArrayOf(existingPointerId, newPointerId),
            pointerXs = floatArrayOf(existingX, existingX + 1f),
        )
    }

    private fun multiPointerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointerIds: IntArray,
        pointerXs: FloatArray,
    ): MotionEvent {
        val properties = Array(pointerIds.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointerIds[index]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(pointerIds.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = pointerXs[index]
                y = 0f
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointerIds.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private class RecordingListener : GlideTypingGesture.Listener {
        var points = 0
        var completions = 0
        var cancellations = 0

        override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
            points++
        }

        override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
            completions++
        }

        override fun onGlideCancelled() {
            cancellations++
        }
    }
}
