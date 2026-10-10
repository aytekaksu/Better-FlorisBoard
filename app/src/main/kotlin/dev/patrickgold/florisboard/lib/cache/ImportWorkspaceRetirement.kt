/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.cache

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retires one import workspace after its current imports stop using its files. */
internal class ImportWorkspaceRetirement(
    private val cleanupScope: CoroutineScope,
    private val onTerminalFailure: (Throwable) -> Unit = {},
    private val cleanup: () -> Unit,
) {
    private companion object {
        const val RETRY_DELAY_MS = 250L
    }

    private val guard = Any()
    private var completion = CompletableDeferred<Unit>()
    private var activeImports = 0
    private var requested = false
    private var started = false

    fun retainForImport(): Closeable = synchronized(guard) {
        check(!requested) { "Import workspace is retiring." }
        activeImports++
        var released = false
        Closeable {
            synchronized(guard) {
                if (!released) {
                    released = true
                    activeImports--
                    startIfReady()
                }
            }
        }
    }

    fun request() = synchronized(guard) {
        requested = true
        startIfReady()
    }

    suspend fun retire() {
        withContext(NonCancellable) {
            val pending = synchronized(guard) {
                requested = true
                startIfReady()
                completion
            }
            pending.await()
        }
    }

    // Cleanup is injected; any failure must release waiters and permit a later retry.
    @Suppress("TooGenericExceptionCaught")
    private fun startIfReady() {
        if (!requested || activeImports != 0 || started) return
        started = true
        val pending = completion
        cleanupScope.launch {
            try {
                repeat(2) { attempt ->
                    try {
                        cleanup()
                        pending.complete(Unit)
                        return@launch
                    } catch (error: IOException) {
                        if (attempt == 1) throw error
                        delay(RETRY_DELAY_MS)
                    }
                }
            } catch (error: Throwable) {
                synchronized(guard) {
                    completion = CompletableDeferred()
                    started = false
                }
                runCatching { onTerminalFailure(error) }
                pending.completeExceptionally(error)
            }
        }
    }
}
