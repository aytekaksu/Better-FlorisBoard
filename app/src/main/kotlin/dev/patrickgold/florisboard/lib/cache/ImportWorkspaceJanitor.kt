/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.cache

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One app-owned worker retries only importer paths whose own cleanup failed. */
internal class ImportWorkspaceJanitor(
    scope: CoroutineScope,
    private val intervalMs: Long = 60_000L,
    private val cleanup: (File) -> Boolean,
) {
    private val guard = Any()
    private val pending = LinkedHashSet<File>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    init {
        require(intervalMs > 0L)
        scope.launch {
            while (true) {
                wakeups.receive()
                while (synchronized(guard) { pending.isNotEmpty() }) {
                    val batch = synchronized(guard) {
                        pending.take(16).also { selected ->
                            pending.removeAll(selected.toSet())
                        }
                    }
                    for (directory in batch) {
                        val cleaned = try {
                            cleanup(directory)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            false
                        }
                        if (!cleaned) {
                            synchronized(guard) { pending.add(directory) }
                        }
                    }
                    if (synchronized(guard) { pending.isNotEmpty() }) {
                        // A newly failed path should not wait behind an old path's retry interval.
                        withTimeoutOrNull(intervalMs) { wakeups.receive() }
                    }
                }
            }
        }
    }

    fun enqueue(directory: File) {
        if (synchronized(guard) { pending.add(directory) }) {
            wakeups.trySend(Unit)
        }
    }
}
