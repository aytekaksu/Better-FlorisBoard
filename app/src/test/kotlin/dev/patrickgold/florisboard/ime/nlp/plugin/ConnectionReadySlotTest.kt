/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ConnectionReadySlotTest : FunSpec({
    test("replacing a session wakes its old waiter and rejects a late START") {
        runBlocking {
            val ready = ConnectionReadySlot<String>()
            ready.replace() // Session A begins.
            val a = ready.current()
            val waitingForA = async(start = CoroutineStart.UNDISPATCHED) { awaitProviderResult(a) }

            ready.replace() // Configuration changes to session B before A binds.
            withTimeout(1_000) { waitingForA.await() } shouldBe null
            ready.completeIfCurrent(a, "stale A") shouldBe false

            val b = ready.current()
            ready.completeIfCurrent(b, "B") shouldBe true
            withTimeout(1_000) { b.await() } shouldBe "B"
        }
    }

    test("restarting one session on a new binding rejects the old epoch's readiness") {
        runBlocking {
            val ready = ConnectionReadySlot<String>()
            val oldEpoch = ready.current()
            ready.replace()
            val newEpoch = ready.current()

            withTimeout(1_000) { oldEpoch.await() } shouldBe null
            ready.completeIfCurrent(oldEpoch, "old") shouldBe false
            ready.completeIfCurrent(newEpoch, "new") shouldBe true
            withTimeout(1_000) { newEpoch.await() } shouldBe "new"
        }
    }
})
