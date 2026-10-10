/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.theme

import kotlinx.coroutines.CompletableDeferred
import org.florisboard.lib.kotlin.io.FsDir
import java.io.Closeable

/**
 * Keeps extracted theme assets alive while the manager or a UI consumer still uses them.
 */
class ThemeMaterialization internal constructor(val directory: FsDir, private val dispose: (FsDir) -> Unit) {
    private val guard = Any()
    private var leaseCount = 0
    private var retired = false
    private var disposed = false
    private val disposedSignal = CompletableDeferred<Unit>()

    internal fun acquire(): Lease = checkNotNull(tryAcquire()) {
        "Theme assets are no longer available."
    }

    internal fun tryAcquire(): Lease? = synchronized(guard) {
        if (retired || disposed) return@synchronized null
        leaseCount++
        Lease(this, directory)
    }

    internal fun retire() {
        val directoryToDispose = synchronized(guard) {
            if (retired) return
            retired = true
            takeDirectoryToDispose()
        }
        dispose(directoryToDispose)
    }

    internal suspend fun retireAndAwaitRelease() {
        retire()
        disposedSignal.await()
    }

    private fun release() {
        val directoryToDispose = synchronized(guard) {
            check(leaseCount > 0) { "Theme asset lease is already closed." }
            leaseCount--
            takeDirectoryToDispose()
        }
        dispose(directoryToDispose)
    }

    private fun dispose(directory: FsDir?) {
        if (directory == null) return
        try {
            dispose.invoke(directory)
        } finally {
            disposedSignal.complete(Unit)
        }
    }

    private fun takeDirectoryToDispose(): FsDir? {
        if (!retired || leaseCount != 0 || disposed) return null
        disposed = true
        return directory
    }

    override fun toString() = "ThemeMaterialization(retired=$retired)"

    internal class Lease(private val owner: ThemeMaterialization, val directory: FsDir) : Closeable {
        private val guard = Any()
        private var closed = false

        override fun close() {
            synchronized(guard) {
                if (closed) return
                closed = true
                owner.release()
            }
        }
    }
}
