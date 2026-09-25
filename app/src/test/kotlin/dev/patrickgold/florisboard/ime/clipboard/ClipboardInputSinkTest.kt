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

package dev.patrickgold.florisboard.ime.clipboard

import dev.patrickgold.florisboard.ime.input.InputEventDispatcher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipboardInputSinkTest : FunSpec({
    test("media paste started inside a candidate keeps later input behind it") {
        val dispatcher = InputEventDispatcher(
            repeatableKeyCodes = intArrayOf(),
            uptimeMillis = { 0L },
            doubleTapTimeout = 300L,
            keyRepeatDelay = 50L,
        )
        try {
            val sink = dispatcher.asClipboardInputSink()
            val events = mutableListOf<String>()
            var completeMedia: (() -> Unit)? = null

            sink.dispatchPaste {
                events.add("candidate")
                sink.deferMediaPaste(
                    onLaterInputQueued = {},
                    onInvalidated = {},
                    start = { completeMedia = it },
                )
            }
            sink.dispatchPaste { events.add("later input") }
            events shouldBe listOf("candidate")

            completeMedia?.invoke()
            events shouldBe listOf("candidate", "later input")
        } finally {
            dispatcher.close()
        }
    }

    test("clipboard paste uses the same ordered queue and stale work is invalidated") {
        val dispatcher = InputEventDispatcher(
            repeatableKeyCodes = intArrayOf(),
            uptimeMillis = { 0L },
            doubleTapTimeout = 300L,
            keyRepeatDelay = 50L,
        )
        try {
            val sink = dispatcher.asClipboardInputSink()
            val committed = mutableListOf<String>()
            var completion: (() -> Unit)? = null
            var laterInputSignals = 0
            var invalidations = 0

            sink.deferMediaPaste(
                onLaterInputQueued = { laterInputSignals++ },
                onInvalidated = { invalidations++ },
                start = { completion = it },
            )
            sink.dispatchPaste { committed.add("first") }
            committed shouldBe emptyList()
            laterInputSignals shouldBe 1

            completion?.invoke()
            committed shouldBe listOf("first")

            sink.deferMediaPaste(
                onLaterInputQueued = { laterInputSignals++ },
                onInvalidated = { invalidations++ },
                start = { completion = it },
            )
            sink.dispatchPaste { committed.add("stale") }
            dispatcher.invalidatePendingInputEvents()
            completion?.invoke()
            sink.dispatchPaste { committed.add("fresh") }

            invalidations shouldBe 1
            committed shouldBe listOf("first", "fresh")
        } finally {
            dispatcher.close()
        }
    }
})
