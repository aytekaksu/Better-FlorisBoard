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
                while (true) {
                    val batch = synchronized(guard) {
                        pending.take(16).also { selected ->
                            pending.removeAll(selected.toSet())
                        }
                    }
                    if (batch.isEmpty()) break
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
                    if (synchronized(guard) { pending.isEmpty() }) {
                        break
                    }
                    // A newly failed path should not wait behind an old path's retry interval.
                    withTimeoutOrNull(intervalMs) { wakeups.receive() }
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
