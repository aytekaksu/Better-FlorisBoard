/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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
import androidx.collection.SparseArrayCompat
import androidx.collection.isNotEmpty
import androidx.collection.set
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import org.florisboard.lib.android.removeAndReturn
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.florisboard.lib.kotlin.guardedByLock

internal fun interface PendingInputBarrier {
    fun cancel()
}

/**
 * Keeps semantic input callbacks ordered around asynchronous input operations without delaying
 * raw touch handling, feedback, long presses, or key-repeat timers.
 */
internal class OrderedInputEventQueue {
    private sealed interface Entry

    private class Event(val action: () -> Unit) : Entry

    private class Barrier(
        val onLaterInputQueued: () -> Unit,
        val start: ((() -> Unit) -> Unit),
    ) : Entry {
        var isComplete = false
        var wasLaterInputSignalled = false
    }

    private val entries = ArrayDeque<Entry>()
    private var activeBarrier: Barrier? = null
    private var isDraining = false

    @Synchronized
    fun dispatch(action: () -> Unit) {
        if (activeBarrier == null && (entries.isEmpty() || isDraining)) {
            action()
        } else {
            signalLaterInput()
            entries.addLast(Event(action))
            drain()
        }
    }

    @Synchronized
    fun defer(
        onLaterInputQueued: () -> Unit = {},
        start: ((() -> Unit) -> Unit),
    ): PendingInputBarrier {
        val barrier = Barrier(onLaterInputQueued, start)
        if (isDraining && activeBarrier == null) {
            startBarrier(barrier)
        } else {
            signalLaterInput()
            entries.addLast(barrier)
            drain()
        }
        return PendingInputBarrier { complete(barrier) }
    }

    @Synchronized
    fun invalidate() {
        activeBarrier?.isComplete = true
        activeBarrier = null
        entries.clear()
    }

    private fun signalLaterInput() {
        val barrier = activeBarrier ?: return
        if (barrier.wasLaterInputSignalled) return
        barrier.wasLaterInputSignalled = true
        // Hooks must be fast and non-blocking. They may complete this barrier reentrantly.
        barrier.onLaterInputQueued()
    }

    @Synchronized
    private fun complete(barrier: Barrier) {
        if (barrier.isComplete) return
        barrier.isComplete = true
        if (activeBarrier === barrier) activeBarrier = null
        drain()
    }

    private fun drain() {
        if (isDraining || activeBarrier != null) return
        isDraining = true
        var firstError: Throwable? = null
        try {
            while (activeBarrier == null && entries.isNotEmpty()) {
                try {
                    when (val entry = entries.removeFirst()) {
                        is Event -> entry.action()
                        is Barrier -> startBarrier(entry)
                    }
                } catch (error: Throwable) {
                    if (firstError == null) {
                        firstError = error
                    } else {
                        firstError.addSuppressed(error)
                    }
                }
            }
        } finally {
            isDraining = false
        }
        firstError?.let { throw it }
    }

    private fun startBarrier(barrier: Barrier) {
        if (barrier.isComplete) return
        activeBarrier = barrier
        try {
            barrier.start { complete(barrier) }
        } catch (error: Throwable) {
            complete(barrier)
            throw error
        }
        if (activeBarrier === barrier && entries.isNotEmpty()) {
            signalLaterInput()
        }
    }
}

class InputEventDispatcher private constructor(private val repeatableKeyCodes: IntArray) {
    companion object {
        private val DoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        private val KeyRepeatDelay = ViewConfiguration.getKeyRepeatDelay().toLong()

        fun new(repeatableKeyCodes: IntArray = intArrayOf()) = InputEventDispatcher(repeatableKeyCodes.clone())
    }

    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val pressedKeys = guardedByLock { SparseArrayCompat<PressedKeyInfo>() }
    private var lastKeyEventDown: EventData = EventData(0L, TextKeyData.UNSPECIFIED)
    private var lastKeyEventUp: EventData = EventData(0L, TextKeyData.UNSPECIFIED)
    private val receiverQueue = OrderedInputEventQueue()
    private val activeReceiverState = ThreadLocal<ReceiverState?>()

    /**
     * The input key event register. If null, the dispatcher will still process input, but won't dispatch them to an
     * event receiver.
     */
    var keyEventReceiver: InputKeyEventReceiver? = null
    var keyRepeatFeedbackReceiver: ((KeyData) -> Unit)? = null

    internal fun deferInputEvents(
        onLaterInputQueued: () -> Unit = {},
        start: ((() -> Unit) -> Unit),
    ): PendingInputBarrier {
        return receiverQueue.defer(onLaterInputQueued, start)
    }

    internal fun dispatchInputEvent(action: () -> Unit) {
        receiverQueue.dispatch(action)
    }

    internal fun invalidatePendingInputEvents() {
        receiverQueue.invalidate()
        lastKeyEventDown = EventData(0L, TextKeyData.UNSPECIFIED)
        lastKeyEventUp = EventData(0L, TextKeyData.UNSPECIFIED)
        activeReceiverState.remove()
    }

    private suspend fun dispatchToReceiver(
        eventTime: Long = SystemClock.uptimeMillis(),
        isStillValid: () -> Boolean = { true },
        action: InputKeyEventReceiver.() -> Unit,
    ) {
        val receiver = keyEventReceiver ?: return
        val state = ReceiverState(
            pressedKeyCodes = pressedKeys.withLock { keys ->
                IntArray(keys.size()) { index -> keys.keyAt(index) }
            },
            lastKeyEventDown = lastKeyEventDown,
            lastKeyEventUp = lastKeyEventUp,
            eventTime = eventTime,
        )
        receiverQueue.dispatch receiverDispatch@ {
            if (!isStillValid()) return@receiverDispatch
            val previousState = activeReceiverState.get()
            activeReceiverState.set(state)
            try {
                receiver.action()
            } finally {
                if (previousState == null) {
                    activeReceiverState.remove()
                } else {
                    activeReceiverState.set(previousState)
                }
            }
        }
    }

    private fun determineLongPressDelay(data: KeyData): Long {
        val delayMillis = prefs.keyboard.longPressDelay.get().toLong()
        val factor = when (data.code) {
            KeyCode.SPACE, KeyCode.CJK_SPACE, KeyCode.SHIFT -> 2.5f
            KeyCode.LANGUAGE_SWITCH -> 2.0f
            else -> 1.0f
        }
        return (delayMillis * factor).toLong()
    }

    private fun determineRepeatDelay(data: KeyData): Long {
        val factor = when (data.code) {
            KeyCode.DELETE_WORD, KeyCode.FORWARD_DELETE_WORD, KeyCode.UNDO, KeyCode.REDO -> 5.0f
            else -> 1.0f
        }
        return (KeyRepeatDelay * factor).toLong()
    }

    private fun determineRepeatData(data: KeyData): KeyData {
        return when (data.code) {
            KeyCode.DELETE -> when (prefs.gestures.deleteKeyLongPress.get()) {
                SwipeAction.DELETE_WORD -> TextKeyData.DELETE_WORD
                else -> TextKeyData.DELETE
            }
            KeyCode.FORWARD_DELETE -> when (prefs.gestures.deleteKeyLongPress.get()) {
                SwipeAction.DELETE_WORD -> TextKeyData.FORWARD_DELETE_WORD
                else -> TextKeyData.FORWARD_DELETE
            }
            else -> data
        }
    }

    fun sendDown(
        data: KeyData,
        allowLongPress: Boolean = true,
        allowRepeat: Boolean = true,
        onLongPress: () -> Boolean = { false },
        onRepeat: () -> Boolean = { true },
    ) = runBlocking {
        flogDebug { data.toString() }
        val eventTime = SystemClock.uptimeMillis()
        val result = pressedKeys.withLock { pressedKeys ->
            if (pressedKeys.containsKey(data.code)) return@withLock null
            val pressedKeyInfo = PressedKeyInfo(eventTime, data).also { pressedKeyInfo ->
                if (allowLongPress || allowRepeat) pressedKeyInfo.job = scope.launch {
                    val longPressDelay = determineLongPressDelay(data)
                    delay(longPressDelay)
                    val longPressResult = if (allowLongPress) {
                        withContext(Dispatchers.Main.immediate) {
                            if (!isPressed(data, pressedKeyInfo)) return@withContext false
                            onLongPress().also { handled ->
                                if (handled) pressedKeyInfo.blockUp = true
                            }
                        }
                    } else {
                        false
                    }
                    if (!longPressResult && allowRepeat && repeatableKeyCodes.contains(data.code)) {
                        val repeatData = determineRepeatData(data)
                        val repeatDelay = determineRepeatDelay(repeatData)
                        while (isActive) {
                            withContext(Dispatchers.Main.immediate) {
                                if (
                                    isPressed(data, pressedKeyInfo) &&
                                    onRepeat() &&
                                    isPressed(data, pressedKeyInfo)
                                ) {
                                    keyRepeatFeedbackReceiver?.invoke(repeatData)
                                    dispatchToReceiver(
                                        isStillValid = {
                                            isPressed(data, pressedKeyInfo)
                                        },
                                    ) {
                                        pressedKeyInfo.blockUp = true
                                        onInputKeyRepeat(repeatData)
                                    }
                                }
                            }
                            delay(repeatDelay)
                        }
                    }
                }
            }
            pressedKeys[data.code] = pressedKeyInfo
            return@withLock pressedKeyInfo
        }
        if (result != null) {
            dispatchToReceiver(eventTime) { onInputKeyDown(data) }
            lastKeyEventDown = EventData(eventTime, data)
        }
        result
    }

    fun sendUp(
        data: KeyData,
        expected: PressedKeyInfo? = null,
    ): Boolean = runBlocking {
        flogDebug { data.toString() }
        val (result, isBlocked) = pressedKeys.withLock { pressedKeys ->
            val pressedKeyInfo = pressedKeys[data.code]
            if (pressedKeyInfo != null && (expected == null || pressedKeyInfo === expected)) {
                pressedKeys.remove(data.code)
                pressedKeyInfo.cancelJobs()
                return@withLock true to pressedKeyInfo.blockUp
            }
            return@withLock false to false
        }
        if (result) {
            if (!isBlocked) {
                val eventTime = SystemClock.uptimeMillis()
                dispatchToReceiver(eventTime) { onInputKeyUp(data) }
                lastKeyEventUp = EventData(eventTime, data)
            } else {
                dispatchToReceiver { onInputKeyCancel(data) }
            }
        }
        result
    }

    fun sendDownUp(data: KeyData) = runBlocking {
        flogDebug { data.toString() }
        pressedKeys.withLock { pressedKeys ->
            pressedKeys.removeAndReturn(data.code)?.also { it.cancelJobs() }
        }
        val eventData = EventData(SystemClock.uptimeMillis(), data)
        dispatchToReceiver(eventData.time) { onInputKeyDown(data) }
        lastKeyEventDown = eventData
        dispatchToReceiver(eventData.time) { onInputKeyUp(data) }
        lastKeyEventUp = eventData
    }

    fun sendCancel(
        data: KeyData,
        expected: PressedKeyInfo? = null,
    ): Boolean = runBlocking {
        flogDebug { data.toString() }
        val result = pressedKeys.withLock { pressedKeys ->
            val pressedKeyInfo = pressedKeys[data.code]
            if (pressedKeyInfo != null && (expected == null || pressedKeyInfo === expected)) {
                pressedKeys.remove(data.code)
                pressedKeyInfo.cancelJobs()
                return@withLock true
            }
            return@withLock false
        }
        if (result) {
            dispatchToReceiver { onInputKeyCancel(data) }
        }
        result
    }

    /**
     * Checks if there's currently a key down with given [code].
     *
     * @param code The key code to check for.
     *
     * @return True if the given [code] is currently down, false otherwise.
     */
    fun isPressed(code: Int): Boolean = runBlocking {
        activeReceiverState.get()?.pressedKeyCodes?.contains(code)
            ?: pressedKeys.withLock { it.containsKey(code) }
    }

    fun isAnyPressed(): Boolean = runBlocking {
        activeReceiverState.get()?.pressedKeyCodes?.isNotEmpty()
            ?: pressedKeys.withLock { it.isNotEmpty() }
    }

    fun isConsecutiveDown(data: KeyData): Boolean {
        val state = activeReceiverState.get()
        val event = state?.lastKeyEventDown ?: lastKeyEventDown
        val eventTime = state?.eventTime ?: SystemClock.uptimeMillis()
        return event.data.code == data.code && (eventTime - event.time) < DoubleTapTimeout
    }

    fun isConsecutiveUp(data: KeyData): Boolean {
        val state = activeReceiverState.get()
        val event = state?.lastKeyEventUp ?: lastKeyEventUp
        val eventTime = state?.eventTime ?: SystemClock.uptimeMillis()
        return event.data.code == data.code && (eventTime - event.time) < DoubleTapTimeout
    }

    fun isUninterruptedEventSequence(data: KeyData): Boolean {
        return (activeReceiverState.get()?.lastKeyEventDown ?: lastKeyEventDown).data.code == data.code
    }

    fun isRepeatable(data: KeyData): Boolean {
        return repeatableKeyCodes.contains(data.code)
    }

    fun isRepeatableCodeLastDown(): Boolean {
        val event = activeReceiverState.get()?.lastKeyEventDown ?: lastKeyEventDown
        return repeatableKeyCodes.contains(event.data.code)
    }

    internal fun isPressed(data: KeyData, expected: PressedKeyInfo): Boolean = runBlocking {
        pressedKeys.withLock { it[data.code] === expected }
    }

    internal fun resetPressedKeys() = runBlocking {
        pressedKeys.withLock { keys ->
            for (index in 0 until keys.size()) {
                keys.valueAt(index).cancelJobs()
            }
            keys.clear()
        }
    }

    internal fun cancelPressedKeys() = runBlocking {
        val cancelledData = pressedKeys.withLock { keys ->
            List(keys.size()) { index ->
                keys.valueAt(index).also { it.cancelJobs() }.data
            }.also { keys.clear() }
        }
        cancelledData.forEach { data ->
            dispatchToReceiver { onInputKeyCancel(data) }
        }
    }

    /**
     * Closes this dispatcher and cancels the local coroutine scope.
     */
    fun close() {
        keyEventReceiver = null
        keyRepeatFeedbackReceiver = null
        receiverQueue.invalidate()
        resetPressedKeys()
        scope.cancel()
    }

    data class PressedKeyInfo(
        val eventTimeDown: Long,
        val data: KeyData,
        var job: Job? = null,
        var blockUp: Boolean = false,
    ) {
        fun cancelJobs() {
            job?.cancel()
        }
    }

    data class EventData(
        val time: Long,
        val data: KeyData,
    )

    private data class ReceiverState(
        val pressedKeyCodes: IntArray,
        val lastKeyEventDown: EventData,
        val lastKeyEventUp: EventData,
        val eventTime: Long,
    )
}

/**
 * Interface which represents an input key event receiver.
 */
interface InputKeyEventReceiver {
    /**
     * Event method which gets called when a key went down.
     *
     * @param data The associated input key data.
     */
    fun onInputKeyDown(data: KeyData)

    /**
     * Event method which gets called when a key went up.
     *
     * @param data The associated input key data.
     */
    fun onInputKeyUp(data: KeyData)

    /**
     * Event method which gets called when a key is called repeatedly while being pressed down.
     *
     * @param data The associated input key data.
     */
    fun onInputKeyRepeat(data: KeyData)

    /**
     * Event method which gets called when a key press is cancelled.
     *
     * @param data The associated input key data.
     */
    fun onInputKeyCancel(data: KeyData)
}
