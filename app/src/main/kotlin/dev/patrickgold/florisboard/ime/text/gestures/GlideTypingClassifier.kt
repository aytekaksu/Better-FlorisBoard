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

import dev.patrickgold.florisboard.ime.core.Subtype

data class GlideTypingKey(
    /** Stable layout-local identity of this physical key. */
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** NFC text emitted by this key; it may contain more than one Unicode code point. */
    val output: String,
) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) * 0.5f
    val centerY get() = (top + bottom) * 0.5f
}

/**
 * Inherit this to be able to handle gesture typing. Takes in raw pointer data, and
 * spits out what it thinks the gesture is.
 */
interface GlideTypingClassifier {
    /**
     * Called to notify gesture classifier that it can add a new point to the gesture.
     *
     * @param position The position to add
     */
    fun addGesturePoint(position: GlideTypingGesture.Detector.Position)

    /**
     * Change the layout of the gesture classifier.
     */
    suspend fun setLayout(keys: List<GlideTypingKey>, subtype: Subtype)

    /**
     * Generate suggestions to show to the user.
     *
     * @param maxSuggestionCount The maximum number of suggestions that are accepted.
     */
    suspend fun getSuggestions(maxSuggestionCount: Int): List<CharSequence>

    fun clear()
}
