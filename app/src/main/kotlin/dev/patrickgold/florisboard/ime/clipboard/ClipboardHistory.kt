/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem

data class ClipboardHistory(val all: List<ClipboardItem>) {
    companion object {
        private const val RECENT_TIMESPAN_MS = 300_000 // 300 sec = 5 min

        val EMPTY = ClipboardHistory(emptyList())
    }

    private val now = System.currentTimeMillis()

    val pinned = all.filter { it.isPinned }
    val unpinned = all.filter { !it.isPinned }
    val recent = unpinned.filter { (now - it.creationTimestampMs) < RECENT_TIMESPAN_MS }
    val other = unpinned.filter { (now - it.creationTimestampMs) >= RECENT_TIMESPAN_MS }

    override fun toString(): String =
        "ClipboardHistory(itemCount=${all.size}, pinnedCount=${pinned.size})"
}
