/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.text.gestures

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
