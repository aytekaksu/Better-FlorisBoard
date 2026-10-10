/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard

import dev.patrickgold.florisboard.ime.input.InputEventDispatcher

/** Orders clipboard commits with keys without giving clipboard access to keyboard state. */
internal interface ClipboardInputSink {
    fun dispatchPaste(action: () -> Unit)

    fun deferMediaPaste(
        onLaterInputQueued: () -> Unit,
        onInvalidated: () -> Unit,
        start: ((() -> Unit) -> Unit),
    )
}

internal fun InputEventDispatcher.asClipboardInputSink(): ClipboardInputSink =
    object : ClipboardInputSink {
        override fun dispatchPaste(action: () -> Unit) = dispatchInputEvent(action)

        override fun deferMediaPaste(
            onLaterInputQueued: () -> Unit,
            onInvalidated: () -> Unit,
            start: ((() -> Unit) -> Unit),
        ) = deferInputEvents(onLaterInputQueued, onInvalidated, start)
    }
