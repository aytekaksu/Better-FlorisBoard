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

import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job

class InputEventDispatcherTest : FunSpec({
    test("physical state advances while semantic events wait in order") {
        val dispatcher = testDispatcher()
        val events = mutableListOf<String>()
        var resume: (() -> Unit)? = null
        dispatcher.keyEventReceiver = object : EmptyReceiver() {
            override fun onInputKeyDown(data: KeyData) {
                events.add("down:${data.code}:${dispatcher.isPressed(TextKeyData.SHIFT.code)}")
            }

            override fun onInputKeyUp(data: KeyData) {
                events.add("up:${data.code}:${dispatcher.isPressed(TextKeyData.SHIFT.code)}")
            }
        }
        dispatcher.deferInputEvents { resume = it }

        dispatcher.sendDown(TextKeyData.SHIFT, allowLongPress = false, allowRepeat = false)
        dispatcher.sendDown(letter, allowLongPress = false, allowRepeat = false)
        dispatcher.sendUp(letter)
        dispatcher.sendUp(TextKeyData.SHIFT)

        events.shouldBeEmpty()
        dispatcher.isAnyPressed() shouldBe false
        resume?.invoke()
        events shouldBe listOf(
            "down:-11:true",
            "down:97:true",
            "up:97:true",
            "up:-11:false",
        )
        dispatcher.close()
    }

    test("queued double taps retain their physical event times") {
        val clock = TestClock(100L)
        val dispatcher = testDispatcher(clock)
        val consecutiveUps = mutableListOf<Boolean>()
        var resume: (() -> Unit)? = null
        dispatcher.keyEventReceiver = object : EmptyReceiver() {
            override fun onInputKeyUp(data: KeyData) {
                consecutiveUps.add(dispatcher.isConsecutiveUp(data))
            }
        }
        dispatcher.deferInputEvents { resume = it }

        dispatcher.sendDownUp(TextKeyData.SPACE)
        clock.now = 150L
        dispatcher.sendDownUp(TextKeyData.SPACE)
        clock.now = 1_000L
        resume?.invoke()

        consecutiveUps shouldBe listOf(false, true)
        dispatcher.close()
    }

    test("lifecycle invalidation drops queued double-tap history") {
        val dispatcher = testDispatcher()
        val consecutiveEvents = mutableListOf<Boolean>()
        dispatcher.keyEventReceiver = object : EmptyReceiver() {
            override fun onInputKeyDown(data: KeyData) {
                consecutiveEvents.add(dispatcher.isConsecutiveDown(data))
            }

            override fun onInputKeyUp(data: KeyData) {
                consecutiveEvents.add(dispatcher.isConsecutiveUp(data))
            }
        }
        dispatcher.deferInputEvents(start = {})
        dispatcher.sendDownUp(TextKeyData.SPACE)
        dispatcher.invalidatePendingInputEvents()
        dispatcher.sendDownUp(TextKeyData.SPACE)

        consecutiveEvents shouldBe listOf(false, false)
        dispatcher.close()
    }

    test("a receiver may replace a key without giving its stale owner control") {
        val dispatcher = testDispatcher()
        val events = mutableListOf<String>()
        var shouldReplace = true
        var replacement: InputEventDispatcher.PressedKeyInfo? = null
        dispatcher.keyEventReceiver = object : EmptyReceiver() {
            override fun onInputKeyDown(data: KeyData) {
                events.add("down:${dispatcher.isPressed(data.code)}")
                if (shouldReplace) {
                    shouldReplace = false
                    dispatcher.sendCancel(data)
                    replacement = dispatcher.sendDown(data, false, false)
                }
            }

            override fun onInputKeyUp(data: KeyData) {
                events.add("up:${dispatcher.isPressed(data.code)}")
            }

            override fun onInputKeyCancel(data: KeyData) {
                events.add("cancel:${dispatcher.isPressed(data.code)}")
            }
        }

        val original = dispatcher.sendDown(letter, false, false) ?: error("missing owner")
        val current = replacement ?: error("missing replacement")
        dispatcher.sendUp(letter, original) shouldBe false
        dispatcher.sendCancel(letter, original) shouldBe false
        dispatcher.isPressed(letter, current) shouldBe true
        dispatcher.sendUp(letter, current) shouldBe true

        events shouldBe listOf("down:true", "cancel:false", "down:true", "up:false")
        dispatcher.close()
    }

    test("bulk cancellation retires its snapshot before reentrant input") {
        val dispatcher = testDispatcher()
        val second = TextKeyData(code = 'b'.code, label = "b")
        val jobs = listOf(Job(), Job())
        val events = mutableListOf<String>()
        var replacement: InputEventDispatcher.PressedKeyInfo? = null
        val jobsCancelledAtCallbacks = mutableListOf<Boolean>()
        dispatcher.keyEventReceiver = object : EmptyReceiver() {
            override fun onInputKeyDown(data: KeyData) {
                events.add("down:${data.code}")
            }

            override fun onInputKeyCancel(data: KeyData) {
                jobsCancelledAtCallbacks.add(jobs.all { it.isCancelled })
                events.add("cancel:${data.code}")
                if (data.code == letter.code) {
                    replacement = dispatcher.sendDown(letter, false, false)
                }
            }
        }
        val firstOwner = dispatcher.sendDown(letter, false, false) ?: error("missing owner")
        val secondOwner = dispatcher.sendDown(second, false, false) ?: error("missing owner")
        firstOwner.job = jobs[0]
        secondOwner.job = jobs[1]
        events.clear()

        dispatcher.cancelPressedKeys()

        jobsCancelledAtCallbacks shouldBe listOf(true, true)
        events shouldBe listOf("cancel:97", "down:97", "cancel:98")
        dispatcher.isPressed(letter, replacement ?: error("missing replacement")) shouldBe true
        dispatcher.isPressed(second.code) shouldBe false
        dispatcher.close()
    }

    test("close is idempotent and drops its old generation") {
        val dispatcher = testDispatcher()
        val events = mutableListOf<String>()
        val timerJob = Job()
        var completion: (() -> Unit)? = null
        var invalidations = 0
        dispatcher.keyEventReceiver = object : EmptyReceiver() {
            override fun onInputKeyDown(data: KeyData) {
                events.add("down")
            }

            override fun onInputKeyCancel(data: KeyData) {
                events.add("cancel")
            }
        }
        dispatcher.deferInputEvents(
            onInvalidated = { invalidations++ },
        ) { completion = it }
        val owner = dispatcher.sendDown(letter, false, false) ?: error("missing owner")
        owner.job = timerJob

        dispatcher.close()
        dispatcher.close()
        completion?.invoke()

        invalidations shouldBe 1
        timerJob.isCancelled shouldBe true
        dispatcher.isAnyPressed() shouldBe false
        events.shouldBeEmpty()
    }
})

private fun testDispatcher(clock: TestClock = TestClock()) = InputEventDispatcher(
    repeatableKeyCodes = intArrayOf(),
    uptimeMillis = clock::read,
    doubleTapTimeout = 300L,
    keyRepeatDelay = 50L,
)

private open class EmptyReceiver : InputKeyEventReceiver {
    override fun onInputKeyDown(data: KeyData) = Unit

    override fun onInputKeyUp(data: KeyData) = Unit

    override fun onInputKeyRepeat(data: KeyData) = Unit

    override fun onInputKeyCancel(data: KeyData) = Unit
}

private class TestClock(var now: Long = 1L) {
    fun read() = now
}

private val letter = TextKeyData(code = 'a'.code, label = "a")
