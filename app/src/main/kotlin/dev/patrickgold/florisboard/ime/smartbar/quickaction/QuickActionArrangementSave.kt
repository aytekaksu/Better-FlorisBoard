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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PreferenceEdit<T>(val value: T, val epoch: Long)

/** Keeps a pending edit readable while one ordered writer commits it. */
internal class PendingPreferenceSave<T : Any>(
    private val scope: CoroutineScope,
    private val read: () -> T,
    private val write: suspend (T) -> Unit,
    private val onBackgroundFailure: (Exception) -> Unit,
) {
    private data class Pending<T>(val revision: Long, val value: T)

    private val stateLock = Any()
    private val writeLock = Mutex()
    private var revision = 0L
    private var epoch = 0L
    private var pending: Pending<T>? = null

    fun open(): PreferenceEdit<T> = synchronized(stateLock) {
        PreferenceEdit(pending?.value ?: read(), epoch)
    }

    private fun queue(value: T, editEpoch: Long): Long? = synchronized(stateLock) {
        if (editEpoch != epoch) null else (++revision).also { pending = Pending(it, value) }
    }

    /** Used when the IME or editor is torn down before an intentional close can finish. */
    fun saveInBackground(value: T, editEpoch: Long) {
        if (queue(value, editEpoch) == null) return
        scope.launch {
            repeat(3) { attempt ->
                try {
                    flush()
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (attempt == 2) runCatching { onBackgroundFailure(error) }
                    else delay((attempt + 1) * 250L)
                }
            }
        }
    }

    /** An intentional close is acknowledged only after the latest edit is durable. */
    suspend fun saveAndAwait(value: T, editEpoch: Long): Boolean {
        val savedRevision = queue(value, editEpoch) ?: return false
        flush()
        return synchronized(stateLock) {
            epoch == editEpoch && revision == savedRevision && pending == null
        }
    }

    suspend fun flush() = writeLock.withLock { flushLocked() }

    /** Backup sees every earlier edit and excludes edits queued during its snapshot. */
    suspend fun <R> withBarrier(block: suspend () -> R): R = writeLock.withLock {
        flushLocked()
        block()
    }

    /** A completed reset or restore supersedes drafts opened before it. */
    suspend fun <R> withReplacement(
        flushPending: Boolean = true,
        invalidateOnFailure: (Throwable) -> Boolean = { false },
        block: suspend () -> R,
    ): R = writeLock.withLock {
        if (flushPending) flushLocked()
        try {
            block().also { invalidateDrafts() }
        } catch (failure: Throwable) {
            if (invalidateOnFailure(failure)) invalidateDrafts()
            throw failure
        }
    }

    private fun invalidateDrafts() = synchronized(stateLock) {
        epoch++
        pending = null
    }

    private suspend fun flushLocked() {
        while (true) {
            val next = synchronized(stateLock) { pending } ?: return
            write(next.value)
            synchronized(stateLock) {
                if (pending?.revision == next.revision) pending = null
            }
        }
    }
}

internal object QuickActionArrangementSave {
    private val prefs by FlorisPreferenceStore
    private val queue = PendingPreferenceSave(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        read = { prefs.smartbar.actionArrangement.get() },
        write = { value ->
            withContext(Dispatchers.IO) { prefs.smartbar.actionArrangement.set(value).getOrThrow() }
        },
        onBackgroundFailure = { error ->
            flogError { "Quick action save failed: failureClass=${error.javaClass.simpleName}" }
        },
    )

    fun open() = queue.open()
    fun saveInBackground(value: QuickActionArrangement, editEpoch: Long) =
        queue.saveInBackground(value, editEpoch)
    suspend fun saveAndAwait(value: QuickActionArrangement, editEpoch: Long) =
        queue.saveAndAwait(value, editEpoch)
    suspend fun <R> withBarrier(block: suspend () -> R): R = queue.withBarrier(block)
    suspend fun <R> withReplacement(
        flushPending: Boolean = true,
        invalidateOnFailure: (Throwable) -> Boolean = { false },
        block: suspend () -> R,
    ): R = queue.withReplacement(flushPending, invalidateOnFailure, block)
}
