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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps semantic input callbacks ordered around asynchronous input operations without delaying
 * raw touch handling, feedback, long presses, or key-repeat timers.
 */
internal class OrderedInputEventQueue {
    private sealed interface Entry

    private class Event(val action: () -> Unit) : Entry

    private class Barrier(
        val onLaterInputQueued: () -> Unit,
        val onInvalidated: () -> Unit,
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
        onInvalidated: () -> Unit = {},
        start: ((() -> Unit) -> Unit),
    ) {
        val barrier = Barrier(onLaterInputQueued, onInvalidated, start)
        if (isDraining && activeBarrier == null) {
            startBarrier(barrier)
        } else {
            signalLaterInput()
            entries.addLast(barrier)
            drain()
        }
    }

    @Synchronized
    fun invalidate() {
        val invalidatedBarrier = activeBarrier
        invalidatedBarrier?.isComplete = true
        activeBarrier = null
        entries.clear()
        runCatching { invalidatedBarrier?.onInvalidated?.invoke() }
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

class InputEventDispatcher internal constructor(
    repeatableKeyCodes: IntArray,
    private val uptimeMillis: () -> Long,
    private val doubleTapTimeout: Long,
    private val keyRepeatDelay: Long,
) {
    companion object {
        fun new(repeatableKeyCodes: IntArray = intArrayOf()) = InputEventDispatcher(
            repeatableKeyCodes = repeatableKeyCodes,
            uptimeMillis = SystemClock::uptimeMillis,
            doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong(),
            keyRepeatDelay = ViewConfiguration.getKeyRepeatDelay().toLong(),
        )
    }

    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val repeatableKeyCodes = repeatableKeyCodes.clone()
    private val pressedKeysLock = Any()
    private val pressedKeys = SparseArrayCompat<PressedKeyInfo>()
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
        onInvalidated: () -> Unit = {},
        start: ((() -> Unit) -> Unit),
    ) {
        receiverQueue.defer(onLaterInputQueued, onInvalidated, start)
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

    private fun dispatchToReceiver(
        eventTime: Long = uptimeMillis(),
        isStillValid: () -> Boolean = { true },
        action: InputKeyEventReceiver.() -> Unit,
    ) {
        val receiver = keyEventReceiver ?: return
        val state = ReceiverState(
            pressedKeyCodes = synchronized(pressedKeysLock) {
                IntArray(pressedKeys.size()) { index -> pressedKeys.keyAt(index) }
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
        return (keyRepeatDelay * factor).toLong()
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
    ): PressedKeyInfo? {
        val eventTime = uptimeMillis()
        val result = synchronized(pressedKeysLock) {
            if (pressedKeys.containsKey(data.code)) return@synchronized null
            val pressedKeyInfo = PressedKeyInfo(data).also { pressedKeyInfo ->
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
            pressedKeyInfo
        }
        if (result != null) {
            dispatchToReceiver(eventTime) { onInputKeyDown(data) }
            lastKeyEventDown = EventData(eventTime, data)
        }
        return result
    }

    fun sendUp(
        data: KeyData,
        expected: PressedKeyInfo? = null,
    ): Boolean {
        val isBlocked = removePressedKey(data, expected) ?: return false
        if (!isBlocked) {
            val eventTime = uptimeMillis()
            dispatchToReceiver(eventTime) { onInputKeyUp(data) }
            lastKeyEventUp = EventData(eventTime, data)
        } else {
            dispatchToReceiver { onInputKeyCancel(data) }
        }
        return true
    }

    fun sendDownUp(data: KeyData) {
        removePressedKey(data)
        val eventData = EventData(uptimeMillis(), data)
        dispatchToReceiver(eventData.time) { onInputKeyDown(data) }
        lastKeyEventDown = eventData
        dispatchToReceiver(eventData.time) { onInputKeyUp(data) }
        lastKeyEventUp = eventData
    }

    fun sendCancel(
        data: KeyData,
        expected: PressedKeyInfo? = null,
    ): Boolean {
        if (removePressedKey(data, expected) == null) return false
        dispatchToReceiver { onInputKeyCancel(data) }
        return true
    }

    /**
     * Checks if there's currently a key down with given [code].
     *
     * @param code The key code to check for.
     *
     * @return True if the given [code] is currently down, false otherwise.
     */
    fun isPressed(code: Int): Boolean {
        activeReceiverState.get()?.pressedKeyCodes?.contains(code)
            ?.let { return it }
        return synchronized(pressedKeysLock) { pressedKeys.containsKey(code) }
    }

    fun isAnyPressed(): Boolean {
        activeReceiverState.get()?.pressedKeyCodes?.isNotEmpty()
            ?.let { return it }
        return synchronized(pressedKeysLock) { pressedKeys.isNotEmpty() }
    }

    fun isConsecutiveDown(data: KeyData): Boolean {
        val state = activeReceiverState.get()
        val event = state?.lastKeyEventDown ?: lastKeyEventDown
        val eventTime = state?.eventTime ?: uptimeMillis()
        return event.data.code == data.code && (eventTime - event.time) < doubleTapTimeout
    }

    fun isConsecutiveUp(data: KeyData): Boolean {
        val state = activeReceiverState.get()
        val event = state?.lastKeyEventUp ?: lastKeyEventUp
        val eventTime = state?.eventTime ?: uptimeMillis()
        return event.data.code == data.code && (eventTime - event.time) < doubleTapTimeout
    }

    fun isUninterruptedEventSequence(data: KeyData): Boolean {
        return (activeReceiverState.get()?.lastKeyEventDown ?: lastKeyEventDown).data.code == data.code
    }

    fun isRepeatable(data: KeyData): Boolean {
        return repeatableKeyCodes.contains(data.code)
    }

    internal fun isPressed(data: KeyData, expected: PressedKeyInfo): Boolean {
        return synchronized(pressedKeysLock) { pressedKeys[data.code] === expected }
    }

    internal fun cancelPressedKeys() {
        drainPressedKeys().forEach { pressedKey ->
            dispatchToReceiver { onInputKeyCancel(pressedKey.data) }
        }
    }

    private fun removePressedKey(data: KeyData, expected: PressedKeyInfo? = null): Boolean? {
        var removedKey: PressedKeyInfo? = null
        val blockUp = synchronized(pressedKeysLock) {
            val pressedKey = pressedKeys[data.code]
            if (pressedKey == null || expected != null && pressedKey !== expected) {
                null
            } else {
                pressedKeys.remove(data.code)
                removedKey = pressedKey
                pressedKey.blockUp
            }
        }
        removedKey?.cancelJobs()
        return blockUp
    }

    private fun drainPressedKeys(): List<PressedKeyInfo> {
        val removedKeys = synchronized(pressedKeysLock) {
            List(pressedKeys.size()) { index -> pressedKeys.valueAt(index) }
                .also { pressedKeys.clear() }
        }
        removedKeys.forEach(PressedKeyInfo::cancelJobs)
        return removedKeys
    }

    /**
     * Closes this dispatcher and cancels the local coroutine scope.
     */
    fun close() {
        keyEventReceiver = null
        keyRepeatFeedbackReceiver = null
        invalidatePendingInputEvents()
        drainPressedKeys()
        scope.cancel()
    }

    class PressedKeyInfo(
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
