/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.media.emoji

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

class EmojiAssetIoTest : FunSpec({
    test("asset work leaves the caller thread") {
        val caller = Thread.currentThread()
        val worker = runBlocking { runEmojiAssetIo { Thread.currentThread() } }
        (worker !== caller) shouldBe true
    }

    test("cancelling an asset read interrupts work without publishing a result") {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val interrupted = AtomicBoolean(false)
            val published = mutableListOf<String>()
            val read = launch {
                val value = runEmojiAssetIo {
                    started.complete(Unit)
                    try {
                        Thread.sleep(5_000)
                        "loaded"
                    } catch (_: InterruptedException) {
                        interrupted.set(true)
                        "interrupted"
                    }
                }
                published += value
            }

            started.await()
            withTimeout(2_000) { read.cancelAndJoin() }
            interrupted.get() shouldBe true
            published shouldBe emptyList()
        }
    }
})
