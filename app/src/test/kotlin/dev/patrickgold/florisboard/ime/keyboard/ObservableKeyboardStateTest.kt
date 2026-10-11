/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS

class ObservableKeyboardStateTest {
    // Bounded stress checks completed writes; barriers do not force an internal race.
    @Test(timeout = 55_000)
    fun concurrentFieldWritesPreserveBothCompletedUpdatesStress() {
        val rounds = 20_000
        val state = ObservableKeyboardState.new()
        val barrier = java.util.concurrent.CyclicBarrier(3)
        val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
        val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val deadline = System.nanoTime() + SECONDS.toNanos(45)
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "keyboard-state-stress").apply { isDaemon = true }
        }
        fun awaitBarrier() {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw java.util.concurrent.TimeoutException("stress deadline")
            barrier.await(minOf(remaining, SECONDS.toNanos(2)), java.util.concurrent.TimeUnit.NANOSECONDS)
        }
        var failure: Throwable? = null
        try {
            repeat(2) { writer ->
                executor.submit {
                    try {
                        repeat(rounds) {
                            if (stopping.get()) return@submit
                            awaitBarrier()
                            if (writer == 0) {
                                state.keyboardMode = KeyboardMode.NUMERIC
                            } else {
                                state.isIncognitoMode = true
                            }
                            awaitBarrier()
                        }
                    } catch (error: Throwable) {
                        if (!stopping.get()) workerFailure.compareAndSet(null, error)
                        barrier.reset()
                    }
                }
            }
            repeat(rounds) { round ->
                state.rawValue = 0uL
                awaitBarrier()
                awaitBarrier()
                workerFailure.get()?.let { throw it }
                // Both setters returned; workers cannot write again until the next start barrier.
                val published = state.snapshots.value
                assertTrue(
                    "STRESS_LOST_RAW_UPDATE round=${round + 1}",
                    state.keyboardMode == KeyboardMode.NUMERIC && state.isIncognitoMode,
                )
                assertTrue(
                    "STRESS_PUBLICATION_MISMATCH round=${round + 1}",
                    published.keyboardMode == KeyboardMode.NUMERIC && published.isIncognitoMode,
                )
            }
        } catch (error: Throwable) {
            val cause = if (error is AssertionError) error else workerFailure.get() ?: error
            failure = when (cause) {
                is AssertionError -> cause
                is java.util.concurrent.TimeoutException -> AssertionError("STRESS_TIMEOUT", cause)
                is java.util.concurrent.BrokenBarrierException -> AssertionError("STRESS_BARRIER_FAILURE", cause)
                is InterruptedException -> AssertionError("STRESS_INTERRUPTED", cause)
                else -> AssertionError("STRESS_OWNER_OR_WORKER_FAILURE", cause)
            }
        } finally {
            stopping.set(true)
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    val primary = failure
                    if (primary == null) {
                        failure = AssertionError("STRESS_CLEANUP_FAILURE", error)
                    } else {
                        primary.addSuppressed(error)
                    }
                }
            }
            cleanup { barrier.reset() }
            cleanup { executor.shutdownNow() }
            cleanup {
                assertTrue("STRESS_CLEANUP_WORKERS_DID_NOT_STOP", executor.awaitTermination(2, SECONDS))
            }
        }
        failure?.let { throw it }
    }

    @Test(timeout = 10_000)
    fun nestedAndOverlappingBatchesPublishOnlyAfterTheLastEnd() {
        val state = ObservableKeyboardState.new()
        val workerWrote = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "keyboard-state-overlap").apply { isDaemon = true }
        }
        var worker: Future<*>? = null
        val result = runCatching {
            state.batchEdit {
                state.keyboardMode = KeyboardMode.NUMERIC
                state.batchEdit { state.isSelectionMode = true }
                assertEquals(KeyboardMode.CHARACTERS, state.snapshots.value.keyboardMode)
                assertFalse(state.snapshots.value.isSelectionMode)
                worker = executor.submit {
                    state.batchEdit {
                        state.isIncognitoMode = true
                        workerWrote.countDown()
                        assertTrue("overlap worker was not released", releaseWorker.await(2, SECONDS))
                    }
                }
                // This also detects accidentally holding the monitor across a batch callback.
                assertTrue("overlap worker could not enter", workerWrote.await(2, SECONDS))
            }
            assertEquals(KeyboardMode.CHARACTERS, state.snapshots.value.keyboardMode)
            assertFalse(state.snapshots.value.isSelectionMode)
            assertFalse(state.snapshots.value.isIncognitoMode)
            releaseWorker.countDown()
            worker!!.get(2, SECONDS)
            assertEquals(KeyboardMode.NUMERIC, state.snapshots.value.keyboardMode)
            assertTrue(state.snapshots.value.isSelectionMode)
            assertTrue(state.snapshots.value.isIncognitoMode)
        }
        releaseWorker.countDown()
        executor.shutdownNow()
        val cleanup = runCatching {
            assertTrue("overlap worker did not stop", executor.awaitTermination(2, SECONDS))
        }
        result.exceptionOrNull()?.let { error ->
            cleanup.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        cleanup.getOrThrow()
    }

    @Test(timeout = 5_000)
    fun exceptionalNestedBatchPublishesAndRethrowsTheOriginalFailure() {
        val state = ObservableKeyboardState.new()
        val failure = IllegalStateException("synthetic batch failure")
        val result = runCatching {
            state.batchEdit {
                state.isIncognitoMode = true
                state.batchEdit {
                    state.keyboardMode = KeyboardMode.PHONE
                    throw failure
                }
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(KeyboardMode.PHONE, state.snapshots.value.keyboardMode)
        assertTrue(state.snapshots.value.isIncognitoMode)
        state.isIncognitoMode = false
        assertFalse(state.snapshots.value.isIncognitoMode)
    }

    @Test(timeout = 5_000)
    fun snapshotsAndPublishedValuesStayDetachedFromLaterWrites() {
        val state = ObservableKeyboardState.new(KeyboardState.F_IS_INCOGNITO_MODE)
        assertTrue(state.snapshots.value.isIncognitoMode)
        state.keyboardMode = KeyboardMode.PHONE
        val snapshot = state.snapshot()
        val published = state.snapshots.value
        state.batchEdit {
            state.keyboardMode = KeyboardMode.NUMERIC
            state.isIncognitoMode = false
        }
        assertEquals(KeyboardMode.PHONE, snapshot.keyboardMode)
        assertTrue(snapshot.isIncognitoMode)
        assertEquals(KeyboardMode.PHONE, published.keyboardMode)
        assertTrue(published.isIncognitoMode)
        snapshot.keyboardMode = KeyboardMode.SYMBOLS
        snapshot.isSelectionMode = true
        assertEquals(KeyboardMode.NUMERIC, state.keyboardMode)
        assertFalse(state.isIncognitoMode)
        assertFalse(state.isSelectionMode)
        assertEquals(KeyboardMode.NUMERIC, state.snapshots.value.keyboardMode)
        assertFalse(state.snapshots.value.isSelectionMode)
    }

    @Test(timeout = 5_000)
    fun synchronousCollectorCanReenterAfterARawReplacement(): Unit = runBlocking {
        val state = ObservableKeyboardState.new()
        val observed = mutableListOf<Pair<KeyboardMode, Boolean>>()
        val collector = launch(Dispatchers.Unconfined) {
            state.snapshots.collect { published ->
                observed += published.keyboardMode to published.isIncognitoMode
                if (published.keyboardMode == KeyboardMode.PHONE && !published.isIncognitoMode) {
                    state.batchEdit { state.isIncognitoMode = true }
                }
            }
        }
        try {
            state.rawValue = KeyboardMode.PHONE.toInt().toULong()
            assertEquals(
                listOf(
                    KeyboardMode.CHARACTERS to false,
                    KeyboardMode.PHONE to false,
                    KeyboardMode.PHONE to true,
                ),
                observed,
            )
            assertEquals(KeyboardMode.PHONE, state.snapshots.value.keyboardMode)
            assertTrue(state.snapshots.value.isIncognitoMode)
        } finally {
            collector.cancelAndJoin()
        }
    }
}
