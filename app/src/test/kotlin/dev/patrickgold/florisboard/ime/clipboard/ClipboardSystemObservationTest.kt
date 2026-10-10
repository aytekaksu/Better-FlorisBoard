/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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

    test("stable polling applies set and clear observations exactly once") {
        runTest {
            listOf<String?>("existing", null).forEach { systemValue ->
                var internalValue: String? = "old"
                var observations = 0
                val appliedValues = mutableListOf<String?>()

                val converged = convergeSystemClipboardPoll<String?>(
                    isStable = { true },
                    awaitFence = {},
                    observe = {
                        observations += 1
                        systemValue
                    },
                    apply = { value ->
                        appliedValues += value
                        internalValue = value
                        true
                    },
                )

                converged shouldBe true
                observations shouldBe 1
                appliedValues shouldBe listOf(systemValue)
                internalValue shouldBe systemValue
            }
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

    test("foreign media cache matches identity and owner until invalidated") {
        val source = "content://example.provider/media/42"
        fun observation(uri: String) = checkNotNull(
            ForeignMediaObservationIdentity.create(
                sourceUri = uri,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                itemCount = 1,
                descriptionTimestamp = 123L,
                hasText = false,
                isSensitive = false,
                isRemoteDevice = false,
            ),
        )
        val identity = observation(source)
        val sameObservation = observation(source)
        val changedSource = observation("content://example.provider/media/43")
        val cache = ForeignMediaObservationCache<Long>()
        val owner = 42L

        cache.shouldSkip(identity, owner) shouldBe false
        cache.record(identity, owner)
        cache.shouldSkip(identity, owner) shouldBe true
        cache.shouldSkip(sameObservation, owner) shouldBe true
        cache.shouldSkip(identity, owner + 1L) shouldBe false
        cache.shouldSkip(changedSource, owner) shouldBe false
        identity.toString().contains(source) shouldBe false

        cache.invalidate()
        cache.shouldSkip(identity, owner) shouldBe false
    }

    test("overlong foreign URI is redacted from the cache identity") {
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
