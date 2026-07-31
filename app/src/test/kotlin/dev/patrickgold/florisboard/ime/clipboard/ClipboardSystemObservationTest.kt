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

import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest

class ClipboardSystemObservationTest : FunSpec({
    test("empty clipboard is exact only with current read authority") {
        canConfirmEmptySystemClipboard(
            isFlorisboardSelected = true,
            isDeviceLocked = false,
            isKeyguardLocked = false,
            isReadAllowed = true,
            hasPrimaryClip = false,
        ) shouldBe true

        listOf(
            canConfirmEmptySystemClipboard(false, false, false, true, false),
            canConfirmEmptySystemClipboard(true, true, false, true, false),
            canConfirmEmptySystemClipboard(true, false, true, true, false),
            canConfirmEmptySystemClipboard(true, false, false, false, false),
            canConfirmEmptySystemClipboard(true, false, false, true, true),
        ).forEach { it shouldBe false }
    }

    test("startup polling applies an existing system clipboard value") {
        runTest {
            var internalValue: String? = null
            var observations = 0

            val converged = convergeSystemClipboardPoll(
                isStable = { true },
                awaitFence = {},
                observe = {
                    observations += 1
                    "existing"
                },
                apply = { value ->
                    internalValue = value
                    true
                },
            )

            converged shouldBe true
            internalValue shouldBe "existing"
            observations shouldBe 1
        }
    }

    test("maintenance polling applies a set event missed by the callback") {
        runTest {
            var internalValue: String? = "old"
            val systemValue = "new"

            val converged = convergeSystemClipboardPoll(
                isStable = { true },
                awaitFence = {},
                observe = { systemValue },
                apply = { value ->
                    internalValue = value
                    true
                },
            )

            converged shouldBe true
            internalValue shouldBe "new"
        }
    }

    test("maintenance polling applies a clear event missed by the callback") {
        runTest {
            var internalValue: String? = "old"
            val systemValue: String? = null

            val converged = convergeSystemClipboardPoll(
                isStable = { true },
                awaitFence = {},
                observe = { systemValue },
                apply = { value ->
                    internalValue = value
                    true
                },
            )

            converged shouldBe true
            internalValue shouldBe null
        }
    }

    test("polling does not read through a callback generation change at the fence") {
        runTest {
            var generation = 1L
            var observed = false
            var applied = false

            val converged = convergeSystemClipboardPoll(
                isStable = { generation == 1L },
                awaitFence = { generation = 2L },
                observe = {
                    observed = true
                    "stale"
                },
                apply = {
                    applied = true
                    true
                },
            )

            converged shouldBe false
            observed shouldBe false
            applied shouldBe false
        }
    }

    test("polling does not apply an observation invalidated while it is read") {
        runTest {
            var generation = 1L
            var applied = false

            val converged = convergeSystemClipboardPoll(
                isStable = { generation == 1L },
                awaitFence = {},
                observe = {
                    generation = 2L
                    "stale"
                },
                apply = {
                    applied = true
                    true
                },
            )

            converged shouldBe false
            applied shouldBe false
        }
    }

    test("a generation change during apply is retried by the next stable poll") {
        runTest {
            var generation = 1L
            var internalValue = "old"

            val staleConvergence = convergeSystemClipboardPoll(
                isStable = { generation == 1L },
                awaitFence = {},
                observe = { "stale" },
                apply = { value ->
                    internalValue = value
                    generation = 2L
                    true
                },
            )

            staleConvergence shouldBe false
            internalValue shouldBe "stale"

            val currentConvergence = convergeSystemClipboardPoll(
                isStable = { generation == 2L },
                awaitFence = {},
                observe = { "current" },
                apply = { value ->
                    internalValue = value
                    true
                },
            )

            currentConvergence shouldBe true
            internalValue shouldBe "current"
        }
    }

    test("sustained callbacks yield to queued actor work without losing the final signal") {
        runTest {
            val drain = BoundedCoalescingDrain<Int>(
                lock = Any(),
                maxBatchSize = 16,
            )
            val actorQueue = ArrayDeque<Boolean>()
            val observedSignals = mutableListOf<Int>()
            var queuedCommandObservedCount: Int? = null

            fun offerCallback(signal: Int) {
                if (drain.offer(signal)) {
                    actorQueue.addLast(true)
                }
            }

            offerCallback(1)
            while (actorQueue.isNotEmpty()) {
                if (actorQueue.removeFirst()) {
                    val enqueueNextDrain = drain.drainBatch { signal ->
                        observedSignals += signal
                        if (signal == 1) {
                            actorQueue.addLast(false)
                        }
                        if (signal < 1_024) {
                            offerCallback(signal + 1)
                        }
                    }
                    if (enqueueNextDrain) {
                        actorQueue.addLast(true)
                    }
                } else {
                    queuedCommandObservedCount = observedSignals.size
                }
            }

            queuedCommandObservedCount shouldBe 16
            observedSignals shouldBe (1..1_024).toList()
            drain.isIdle() shouldBe true
        }
    }

    test("stable foreign media polls reuse one import and callbacks bypass the cache") {
        val source = "content://example.provider/media/42"
        val identity = checkNotNull(
            ForeignMediaObservationIdentity.create(
                sourceUri = source,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                itemCount = 1,
                descriptionTimestamp = 123L,
                hasText = false,
                isSensitive = false,
                isRemoteDevice = false,
            ),
        )
        val cache = ForeignMediaObservationCache<Long>()
        val history = mutableSetOf<Long>()
        val files = mutableSetOf<Long>()
        var currentOwner: Long? = null
        var importCount = 0

        fun synchronize(isPlatformCallback: Boolean) {
            if (isPlatformCallback) {
                cache.invalidate()
            }
            if (cache.shouldSkip(identity, currentOwner)) return
            importCount += 1
            currentOwner = importCount.toLong()
            history += checkNotNull(currentOwner)
            files += checkNotNull(currentOwner)
            cache.record(identity, currentOwner)
        }

        synchronize(isPlatformCallback = false)
        repeat(1_024) {
            synchronize(isPlatformCallback = false)
        }

        importCount shouldBe 1
        history.size shouldBe 1
        files.size shouldBe 1
        identity.toString().contains(source) shouldBe false

        synchronize(isPlatformCallback = true)

        importCount shouldBe 2
        history.size shouldBe 2
        files.size shouldBe 2
    }

    test("overlong foreign URI has a bounded identity and is not repeatedly imported") {
        val source = "content://example.provider/" + "segment".repeat(100_000)
        val identity = checkNotNull(
            ForeignMediaObservationIdentity.create(
                sourceUri = source,
                type = ItemType.VIDEO,
                mimeTypes = listOf("video/mp4"),
                itemCount = 1,
                descriptionTimestamp = 456L,
                hasText = false,
                isSensitive = false,
                isRemoteDevice = false,
            ),
        )
        val cache = ForeignMediaObservationCache<Long>()

        cache.record(identity, 1L)

        cache.shouldSkip(identity, 1L) shouldBe true
        identity.toString().contains(source.take(64)) shouldBe false
    }
})
