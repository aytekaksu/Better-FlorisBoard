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
