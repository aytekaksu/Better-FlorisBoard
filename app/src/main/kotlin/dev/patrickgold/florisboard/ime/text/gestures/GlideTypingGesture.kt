/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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
import android.view.MotionEvent
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.isWordInput
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.lib.util.ViewUtils
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Wrapper class which holds all enums, interfaces and classes for detecting a gesture.
 */
class GlideTypingGesture {
    /**
     * Class which detects swipes based on given [MotionEvent]s. Only supports single-finger swipes
     * and cancels detection for the rest of a touch sequence if another pointer appears.
     */
    class Detector(context: Context) {
        private val prefs by FlorisPreferenceStore
        private var pointerData: PointerData = PointerData(mutableListOf())
        private val keySize = ViewUtils.px2dp(context.resources.getDimension(R.dimen.key_width))
        private val listeners: ArrayList<Listener> = arrayListOf()
        private var pointerId: Int = -1
        private var thresholdScale = 1f
        private var suppressedUntilNextDown = false
        val activePointerId: Int get() = pointerId

        companion object {
            private const val MAX_DETECT_TIME = 500
            private const val VELOCITY_THRESHOLD = 0.10 // dp per ms
        }

        /**
         * Method which evaluates if a given [event] is a gesture.
         *
         * @return whether or not the event was interpreted as part of a gesture.
         */
        fun onTouchEvent(event: MotionEvent, initialKeyData: KeyData?, currentKey: TextKey?): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        cancel()
                        suppressedUntilNextDown = false
                        thresholdScale = if (prefs.glide.sensitive.get()) 0.5f else 1f
                    } else if (pointerId != -1) {
                        cancel()
                        suppressedUntilNextDown = true
                        return false
                    }
                    if (suppressedUntilNextDown || pointerId != -1) {
                        // if we already have another pointer, we don't care
                        return false
                    }
                    val pointerIndex = event.actionIndex
                    pointerId = event.getPointerId(pointerIndex)
                    pointerData.positions.add(
                        Position(
                            event.getX(pointerIndex),
                            event.getY(pointerIndex),
                            motionEventElapsedTimeMillis(event.eventTime, event.downTime),
                        ),
                    )
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (suppressedUntilNextDown) return false
                    val pointerIndex = event.findPointerIndex(pointerId)
                    if (pointerIndex < 0) {
                        return false
                    }
                    for (i in 0..event.historySize) {
                        val pos = when (i) {
                            event.historySize -> Position(
                                event.getX(pointerIndex),
                                event.getY(pointerIndex),
                                motionEventElapsedTimeMillis(event.eventTime, event.downTime),
                            )
                            else -> Position(
                                event.getHistoricalX(pointerIndex, i),
                                event.getHistoricalY(pointerIndex, i),
                                motionEventElapsedTimeMillis(
                                    event.getHistoricalEventTime(i),
                                    event.downTime,
                                ),
                            )
                        }
                        pointerData.positions.add(pos)
                        if (pointerData.isActuallyGesture == null) {
                            // evaluate whether is actually a gesture
                            val dist = ViewUtils.px2dp(pointerData.positions[0].dist(pos))
                            val time = pos.elapsedTimeMillis.toLong() + 1L
                            if (
                                dist > keySize * thresholdScale &&
                                (dist / time) > VELOCITY_THRESHOLD * thresholdScale &&
                                initialKeyData?.isWordInput(KeyboardMode.CHARACTERS) == true &&
                                currentKey.isGlideKey()
                            ) {
                                pointerData.isActuallyGesture = true
                                // Let listener know all those points need to be added.
                                pointerData.positions.take(pointerData.positions.size - 1).forEach { point ->
                                    listeners.forEach {
                                        it.onGlideAddPoint(point)
                                    }
                                }
                            } else if (time > MAX_DETECT_TIME) {
                                pointerData.isActuallyGesture = false
                            }

                        }

                        if (pointerData.isActuallyGesture == true) {
                            pointerData.positions.last()
                                .let { point -> listeners.forEach { it.onGlideAddPoint(point) } }
                        }
                    }
                    return pointerData.isActuallyGesture ?: false
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    if (pointerId != event.getPointerId(event.actionIndex)) {
                        // not our pointer.
                        return false
                    }
                    if (pointerData.isActuallyGesture == true) {
                        listeners.forEach { listener -> listener.onGlideComplete(pointerData) }
                    }
                    resetState()
                    return false
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancel()
                }
                else -> return false
            }
            return false
        }

        fun registerListener(listener: Listener) {
            listeners.add(listener)
        }

        fun unregisterListener(listener: Listener) {
            listeners.remove(listener)
        }

        fun cancel() {
            if (pointerData.isActuallyGesture == true) {
                listeners.forEach { it.onGlideCancelled() }
            }
            resetState()
        }

        private fun resetState() {
            pointerData.apply {
                positions.clear()
                isActuallyGesture = null
            }
            pointerId = -1
        }

        private fun TextKey?.isGlideKey(): Boolean {
            val key = this ?: return false
            return key.isEnabled &&
                key.isVisible &&
                key.computedData.isWordInput(KeyboardMode.CHARACTERS)
        }

        data class PointerData(
            val positions: MutableList<Position>,
            var isActuallyGesture: Boolean? = null,
        )

        data class Position(val x: Float, val y: Float, val elapsedTimeMillis: Int = 0) {
            fun dist(p2: Position): Float {
                return sqrt((p2.x - x).pow(2) + (p2.y - y).pow(2))
            }
        }
    }

    interface Listener {
        /**
         * Called when a gesture is complete.
         */
        fun onGlideComplete(data: Detector.PointerData) {}

        /**
         * Called when a point is added to a gesture.
         * Will not be called before a series of events is detected as a gesture.
         */
        fun onGlideAddPoint(point: Detector.Position) {}

        /**
         * Called to cancel a gesture.
         */
        fun onGlideCancelled() {}
    }
}

internal fun motionEventElapsedTimeMillis(eventTime: Long, downTime: Long): Int {
    return (eventTime - downTime).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
