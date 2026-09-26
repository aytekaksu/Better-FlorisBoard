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
