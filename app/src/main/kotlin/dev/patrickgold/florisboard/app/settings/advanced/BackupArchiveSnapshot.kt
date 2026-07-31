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

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.conservativeUsableSpace
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.min

internal enum class ArchiveSnapshotFailure {
    SOURCE_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    ARCHIVE_TOO_LARGE,
    IO_FAILURE,
}

internal sealed interface ArchiveSnapshotResult {
    data class Valid(val snapshot: ArchiveSnapshot) : ArchiveSnapshotResult

    data class Invalid(val failure: ArchiveSnapshotFailure) : ArchiveSnapshotResult
}

internal class ArchiveSnapshot internal constructor(internal val path: Path, val size: Long) {
    override fun toString(): String = "ArchiveSnapshot(size=$size)"
}

/**
 * Copies a selected document exactly once into a private immutable snapshot.
 *
 * Provider size metadata is not trusted. The actual stream is counted and the
 * completed file is published only after EOF.
 */
internal object BackupArchiveSnapshot {
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val PRIVATE_SPACE_RESERVE_BYTES = 128L shl 20

    suspend fun capture(
        contentResolver: ContentResolver,
        uri: Uri,
        workspaceDir: Path,
        destination: Path,
        limits: ArchiveLimits = ArchiveLimits.Default,
    ): ArchiveSnapshotResult = coroutineScope {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@coroutineScope ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.SOURCE_UNAVAILABLE)
        }
        val cancellationSignal = CancellationSignal()
        val activeResource = AtomicReference<Closeable?>()
        val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                cancellationSignal.cancel()
                closeQuietly(activeResource.getAndSet(null))
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val maxBytes = runtimeArchiveLimit(workspaceDir, limits.maxArchiveBytes)
                    ?: return@withContext ArchiveSnapshotResult.Invalid(
                        ArchiveSnapshotFailure.INSUFFICIENT_STORAGE,
                    )
                val partial = destination.resolveSibling("${destination.fileName}.partial")
                val descriptor = contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
                    ?: return@withContext ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.SOURCE_UNAVAILABLE)
                activeResource.set(descriptor)
                descriptor.use {
                    val input = descriptor.createInputStream()
                    activeResource.set(input)
                    input.use {
                        copyToSnapshot(it, partial, destination, maxBytes)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.IO_FAILURE)
        } finally {
            activeResource.set(null)
            cancellationWatcher.cancel()
        }
    }

    internal suspend fun copyToSnapshot(
        input: InputStream,
        partial: Path,
        destination: Path,
        maxBytes: Long,
    ): ArchiveSnapshotResult {
        if (maxBytes < 0L) {
            return ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.ARCHIVE_TOO_LARGE)
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.IO_FAILURE)
        }
        Files.createDirectories(partial.parent)
        var ownsPartial = false
        return try {
            val copiedBytes = copyBounded(input, partial, maxBytes) {
                ownsPartial = true
            }
                ?: return ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.ARCHIVE_TOO_LARGE)
            publish(partial, destination)
            ownsPartial = false
            ArchiveSnapshotResult.Valid(ArchiveSnapshot(destination, copiedBytes))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.IO_FAILURE)
        } finally {
            if (ownsPartial) {
                deleteQuietly(partial)
            }
        }
    }

    private suspend fun copyBounded(
        input: InputStream,
        partial: Path,
        maxBytes: Long,
        onPartialCreated: () -> Unit,
    ): Long? {
        FileChannel.open(
            partial,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            onPartialCreated()
            val bytes = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(bytes)
                if (read < 0) break
                check(read > 0) { "Input stream made no progress." }
                if (read.toLong() > maxBytes - total) return null
                var buffer = ByteBuffer.wrap(bytes, 0, read)
                while (buffer.hasRemaining()) {
                    coroutineContext.ensureActive()
                    output.write(buffer)
                }
                total += read
            }
            output.force(true)
            return total
        }
    }

    private fun publish(partial: Path, destination: Path) {
        try {
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial, destination)
        }
    }

    // Keep this check conservative instead of evicting unrelated cached data.
    private fun runtimeArchiveLimit(workspaceDir: Path, configuredLimit: Long): Long? {
        if (configuredLimit < 0L) return null
        val usableBytes = workspaceDir.toFile().conservativeUsableSpace()
        if (usableBytes <= PRIVATE_SPACE_RESERVE_BYTES) return null
        return min(configuredLimit, usableBytes - PRIVATE_SPACE_RESERVE_BYTES)
    }

    private fun closeQuietly(resource: Closeable?) {
        try {
            resource?.close()
        } catch (_: Exception) {
            // The caller reports the original cancellation or I/O failure.
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // The owning workspace performs a second cleanup pass.
        }
    }
}
