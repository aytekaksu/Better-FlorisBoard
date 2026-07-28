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

package dev.patrickgold.florisboard.ime.nlp

import dev.patrickgold.florisboard.ime.core.Subtype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncPreloadCacheTest : FunSpec({
    test("glide lexicon keys ignore subtype identity but retain the suggestion source") {
        val first = Subtype.DEFAULT.copy(id = 1L)
        val equivalent = Subtype.DEFAULT.copy(id = 2L)
        val otherProvider = Subtype.DEFAULT.copy(
            nlpProviders = Subtype.DEFAULT.nlpProviders.copy(suggestion = "other"),
        )

        GlideTypingLexiconKey(first) shouldBe GlideTypingLexiconKey(equivalent)
        (GlideTypingLexiconKey(first) == GlideTypingLexiconKey(otherProvider)) shouldBe false
    }

    test("await remains pending until the actual preload completes") {
        runTest {
            val ready = CompletableDeferred<Unit>()
            val cache = AsyncPreloadCache<String, List<String>>(this) {
                ready.await()
                listOf("ready")
            }
            val waiting = async { cache.await("en") }
            runCurrent()

            waiting.isCompleted shouldBe false
            ready.complete(Unit)
            waiting.await().value shouldBe listOf("ready")
        }
    }

    test("same-key refreshes are serialized and advance the revision") {
        runTest {
            var loadCount = 0
            val cache = AsyncPreloadCache<String, Int>(this) { ++loadCount }

            val first = cache.preload("en")
            val refreshed = cache.preload("en")
            first.await().value shouldBe 1

            refreshed.await().value shouldBe 2
            refreshed.await().revision shouldBe first.await().revision + 1
            cache.await("en") shouldBe refreshed.await()
        }
    }

    test("equal refresh content keeps its stable revision") {
        runTest {
            val cache = AsyncPreloadCache<String, List<String>>(this) { listOf("same") }

            val first = cache.preload("en").await()
            val refreshed = cache.preload("en").await()

            refreshed shouldBe first
        }
    }

    test("least recently used entry is evicted while an accessed entry is retained") {
        runTest {
            val loadCounts = mutableMapOf<String, Int>()
            val cache = AsyncPreloadCache<String, String>(this, maxEntries = 2) { key ->
                loadCounts[key] = loadCounts.getOrDefault(key, 0) + 1
                key
            }

            cache.preload("en").await()
            cache.preload("tr").await()
            cache.await("en")
            cache.preload("de").await()

            cache.await("en").value shouldBe "en"
            loadCounts.getValue("en") shouldBe 1
            cache.await("tr").value shouldBe "tr"
            loadCounts.getValue("tr") shouldBe 2
            loadCounts.getValue("de") shouldBe 1
        }
    }

    test("eviction cancels the whole unfinished refresh chain") {
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            var enLoads = 0
            val cache = AsyncPreloadCache<String, String>(this, maxEntries = 1) { key ->
                if (key == "en") {
                    enLoads += 1
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                key
            }

            val first = cache.preload("en")
            started.await()
            val refresh = cache.preload("en")
            runCurrent()

            cache.preload("tr").await().value shouldBe "tr"
            cancelled.await()
            first.isCancelled shouldBe true
            refresh.isCancelled shouldBe true
            enLoads shouldBe 1
        }
    }

    test("await retries when its cache entry is evicted") {
        runTest {
            val started = CompletableDeferred<Unit>()
            var attempts = 0
            val cache = AsyncPreloadCache<String, String>(this, maxEntries = 1) { key ->
                if (key == "en" && ++attempts == 1) {
                    started.complete(Unit)
                    awaitCancellation()
                }
                key
            }
            val waiting = async { cache.await("en") }
            started.await()

            cache.preload("tr")
            runCurrent()

            waiting.await().value shouldBe "en"
            attempts shouldBe 2
        }
    }

    test("caller and cache-scope cancellation propagate independently") {
        runTest {
            val started = CompletableDeferred<Unit>()
            val scopeLoadStarted = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Unit>()
            val cacheScope = CoroutineScope(coroutineContext + SupervisorJob())
            val cache = AsyncPreloadCache<String, String>(cacheScope) { key ->
                if (key == "en") {
                    started.complete(Unit)
                    ready.await()
                    "ready"
                } else {
                    scopeLoadStarted.complete(Unit)
                    awaitCancellation()
                }
            }
            val waiting = async { cache.await("en") }
            started.await()

            waiting.cancelAndJoin()
            waiting.isCancelled shouldBe true

            ready.complete(Unit)
            cache.await("en").value shouldBe "ready"

            val scopeWaiting = async { cache.await("tr") }
            scopeLoadStarted.await()
            cacheScope.cancel()
            scopeWaiting.join()
            scopeWaiting.isCancelled shouldBe true
        }
    }

    test("cancelling a queued refresh propagates without running its loader") {
        runTest {
            val firstReady = CompletableDeferred<Unit>()
            var loadCount = 0
            val cacheScope = CoroutineScope(coroutineContext + SupervisorJob())
            val cache = AsyncPreloadCache<String, Int>(cacheScope) {
                loadCount += 1
                if (loadCount == 1) firstReady.await()
                loadCount
            }

            val first = cache.preload("en")
            runCurrent()
            val refreshed = cache.preload("en")
            runCurrent()

            refreshed.cancelAndJoin()
            loadCount shouldBe 1
            var cancellation: CancellationException? = null
            try {
                refreshed.await()
            } catch (error: CancellationException) {
                cancellation = error
            }
            (cancellation != null) shouldBe true

            firstReady.complete(Unit)
            first.await().value shouldBe 1
            cacheScope.cancel()
        }
    }

    test("await follows a refresh queued while an older preload is running") {
        runTest {
            val firstReady = CompletableDeferred<Unit>()
            val secondReady = CompletableDeferred<Unit>()
            var loadCount = 0
            val cache = AsyncPreloadCache<String, Int>(this) {
                loadCount += 1
                if (loadCount == 1) firstReady.await() else secondReady.await()
                loadCount
            }
            val waiting = async { cache.await("en") }
            runCurrent()
            cache.preload("en")

            firstReady.complete(Unit)
            runCurrent()
            waiting.isCompleted shouldBe false

            secondReady.complete(Unit)
            waiting.await().value shouldBe 2
        }
    }

    test("a failed preload is retried instead of becoming sticky") {
        runTest {
            var attempts = 0
            val cacheScope = CoroutineScope(coroutineContext + SupervisorJob())
            val cache = AsyncPreloadCache<String, Int>(cacheScope) {
                if (++attempts == 1) error("transient")
                attempts
            }

            var failed = false
            try {
                cache.await("en")
            } catch (_: IllegalStateException) {
                failed = true
            }
            failed shouldBe true
            cache.await("en").value shouldBe 2
            cacheScope.cancel()
        }
    }
})
