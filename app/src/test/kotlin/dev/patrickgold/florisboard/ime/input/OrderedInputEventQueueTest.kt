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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class OrderedInputEventQueueTest : FunSpec({
    test("events retain exact FIFO order across multiple asynchronous barriers") {
        val started = mutableListOf<String>()
        val completions = ArrayDeque<() -> Unit>()
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer {
            started.add("first")
            completions.addLast(it)
        }
        queue.dispatch { consumed.add(1) }
        queue.dispatch { consumed.add(2) }
        queue.defer {
            started.add("second")
            completions.addLast(it)
        }
        queue.dispatch { consumed.add(3) }
        queue.dispatch { consumed.add(4) }
        queue.defer {
            started.add("third")
            completions.addLast(it)
        }
        queue.dispatch { consumed.add(5) }

        started shouldBe listOf("first")
        consumed.shouldBeEmpty()

        completions.removeFirst().invoke()
        started shouldBe listOf("first", "second")
        consumed shouldBe listOf(1, 2)

        completions.removeFirst().invoke()
        started shouldBe listOf("first", "second", "third")
        consumed shouldBe listOf(1, 2, 3, 4)

        completions.removeFirst().invoke()
        consumed shouldBe listOf(1, 2, 3, 4, 5)
    }

    test("more than 64 events are retained without loss or duplication") {
        var complete: (() -> Unit)? = null
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()
        val events = (0 until 257).toList()

        queue.defer { complete = it }
        events.forEach { event -> queue.dispatch { consumed.add(event) } }
        consumed.shouldBeEmpty()

        complete?.invoke()
        consumed shouldBe events
    }

    test("later input signals an active barrier exactly once") {
        var firstCompletion: (() -> Unit)? = null
        var secondCompletion: (() -> Unit)? = null
        var signals = 0
        val queue = OrderedInputEventQueue()

        queue.defer(
            onLaterInputQueued = { signals++ },
        ) { firstCompletion = it }
        queue.dispatch {}
        queue.defer { secondCompletion = it }
        queue.dispatch {}

        signals shouldBe 1
        firstCompletion?.invoke()
        secondCompletion?.invoke()
        signals shouldBe 1
    }

    test("a later-input hook may complete its barrier reentrantly") {
        var completion: (() -> Unit)? = null
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer(
            onLaterInputQueued = { completion?.invoke() },
        ) { completion = it }
        queue.dispatch { consumed.add(1) }

        consumed shouldBe listOf(1)
    }

    test("a barrier sees work which was queued before it became active") {
        var firstCompletion: (() -> Unit)? = null
        var secondCompletion: (() -> Unit)? = null
        var signals = 0
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer { firstCompletion = it }
        queue.defer(
            onLaterInputQueued = { signals++ },
        ) { secondCompletion = it }
        queue.dispatch { consumed.add(1) }

        firstCompletion?.invoke()
        signals shouldBe 1
        consumed.shouldBeEmpty()

        secondCompletion?.invoke()
        consumed shouldBe listOf(1)
    }

    test("synchronous completion resumes the same drain in FIFO order") {
        var firstComplete: (() -> Unit)? = null
        val consumed = mutableListOf<String>()
        val queue = OrderedInputEventQueue()

        queue.defer { firstComplete = it }
        queue.dispatch { consumed.add("before") }
        queue.defer { complete ->
            consumed.add("start")
            complete()
            consumed.add("return")
        }
        queue.dispatch { consumed.add("after") }

        firstComplete?.invoke()
        consumed shouldBe listOf("before", "start", "return", "after")
    }

    test("cancelling a barrier releases events but does not invalidate later work") {
        var cancelledCompletion: (() -> Unit)? = null
        var nextCompletion: (() -> Unit)? = null
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        val cancelledBarrier = queue.defer { cancelledCompletion = it }
        queue.dispatch { consumed.add(1) }
        cancelledBarrier.cancel()
        consumed shouldBe listOf(1)

        queue.defer { nextCompletion = it }
        queue.dispatch { consumed.add(2) }
        cancelledCompletion?.invoke()
        consumed shouldBe listOf(1)

        nextCompletion?.invoke()
        consumed shouldBe listOf(1, 2)
    }

    test("lifecycle invalidation drops old work without affecting a new generation") {
        var oldCompletion: (() -> Unit)? = null
        var newCompletion: (() -> Unit)? = null
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer { oldCompletion = it }
        queue.dispatch { consumed.add(1) }
        val queuedBarrier = queue.defer { error("invalidated barrier must not start") }
        queue.dispatch { consumed.add(2) }
        queue.invalidate()

        queue.dispatch { consumed.add(3) }
        queue.defer { newCompletion = it }
        queue.dispatch { consumed.add(4) }
        oldCompletion?.invoke()
        queuedBarrier.cancel()

        consumed shouldBe listOf(3)
        newCompletion?.invoke()
        consumed shouldBe listOf(3, 4)
    }

    test("stale and duplicate completion callbacks are idempotent") {
        val completions = ArrayDeque<() -> Unit>()
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer { completions.addLast(it) }
        queue.dispatch { consumed.add(1) }
        queue.defer { completions.addLast(it) }
        queue.dispatch { consumed.add(2) }

        val firstCompletion = completions.removeFirst()
        firstCompletion()
        consumed shouldBe listOf(1)

        firstCompletion()
        consumed shouldBe listOf(1)

        val secondCompletion = completions.removeFirst()
        secondCompletion()
        secondCompletion()
        firstCompletion()
        consumed shouldBe listOf(1, 2)
    }

    test("nested dispatch runs at its causal position before previously queued siblings") {
        var complete: (() -> Unit)? = null
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer { complete = it }
        queue.dispatch {
            consumed.add(1)
            queue.dispatch { consumed.add(2) }
            consumed.add(3)
        }
        queue.dispatch { consumed.add(4) }

        complete?.invoke()
        consumed shouldBe listOf(1, 2, 3, 4)
    }

    test("dispatch after a reentrant barrier cannot bypass that barrier") {
        var firstCompletion: (() -> Unit)? = null
        var nestedCompletion: (() -> Unit)? = null
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer { firstCompletion = it }
        queue.dispatch {
            consumed.add(1)
            queue.defer { nestedCompletion = it }
            queue.dispatch { consumed.add(2) }
            consumed.add(3)
        }

        firstCompletion?.invoke()
        consumed shouldBe listOf(1, 3)

        nestedCompletion?.invoke()
        consumed shouldBe listOf(1, 3, 2)
    }

    test("a throwing barrier does not wedge events behind it") {
        var firstCompletion: (() -> Unit)? = null
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer { firstCompletion = it }
        queue.dispatch { consumed.add(1) }
        val failedBarrier = queue.defer { throw IllegalStateException("failed to start") }
        queue.dispatch { consumed.add(2) }

        shouldThrow<IllegalStateException> {
            firstCompletion?.invoke()
        }.message shouldBe "failed to start"
        consumed shouldBe listOf(1, 2)

        failedBarrier.cancel()
        queue.dispatch { consumed.add(3) }
        consumed shouldBe listOf(1, 2, 3)
    }

    test("a throwing event does not discard queued siblings") {
        var complete: (() -> Unit)? = null
        val consumed = mutableListOf<Int>()
        val queue = OrderedInputEventQueue()

        queue.defer { complete = it }
        queue.dispatch {
            consumed.add(1)
            throw IllegalArgumentException("failed event")
        }
        queue.dispatch { consumed.add(2) }

        shouldThrow<IllegalArgumentException> {
            complete?.invoke()
        }.message shouldBe "failed event"

        queue.dispatch { consumed.add(3) }
        consumed shouldBe listOf(1, 2, 3)
    }
})
