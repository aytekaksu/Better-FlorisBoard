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

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Stages foreign provider data in a disposable app process. A provider which
 * ignores cancellation can kill only that worker, not the clipboard actor's
 * sole import thread.
 */
internal class ClipboardExternalMediaImporter(
    context: Context,
    private val timeoutMs: Long,
    private val stageCapacity: (Context) -> Long = ClipboardFileStorage::externalStageCapacity,
    private val stagingDirectory: String = DEFAULT_STAGING_DIRECTORY,
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val stagingDirectoryCleaned = AtomicBoolean(false)
    private val activeTask = AtomicReference<StageTask?>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        { task ->
            Thread(task, "clipboard-provider-import").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val cleanupExecutor = ThreadPoolExecutor(
        0,
        1,
        5L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { task ->
            Thread(task, "clipboard-provider-cleanup").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    init {
        require(timeoutMs in 1..ClipboardExternalMediaImportWorkerContract.MAX_TIMEOUT_MS)
        require(stagingDirectory.matches(STAGING_DIRECTORY_NAME))
    }

    fun stage(
        source: Uri,
        externalCancellation: CancellationSignal? = null,
    ): StagedClipboardMedia? {
        if (closed.get()) return null
        val task = StageTask(
            source = source,
            deadlineElapsedRealtimeMs = elapsedDeadline(timeoutMs),
        )
        val future = FutureTask(task)
        task.future = future
        if (!admit(task)) return null
        if (closed.get()) {
            activeTask.compareAndSet(task, null)
            task.abandon()
            task.markReleased()
            return null
        }
        try {
            try {
                externalCancellation?.setOnCancelListener(task::abandon)
            } catch (_: RuntimeException) {
                activeTask.compareAndSet(task, null)
                task.abandon()
                return null
            }
            try {
                executor.execute {
                    try {
                        future.run()
                    } finally {
                        activeTask.compareAndSet(task, null)
                        task.markReleased()
                    }
                }
            } catch (_: RejectedExecutionException) {
                activeTask.compareAndSet(task, null)
                task.abandon()
                task.markReleased()
                return null
            }
            return try {
                val remainingMs =
                    task.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0L) {
                    task.abandon()
                    task.awaitRelease()
                    return null
                }
                val staged = future.get(remainingMs, TimeUnit.MILLISECONDS)
                if (task.claim()) staged else null
            } catch (_: TimeoutException) {
                task.abandon()
                task.awaitRelease()
                null
            } catch (_: java.util.concurrent.CancellationException) {
                task.abandon()
                task.awaitRelease()
                null
            } catch (_: ExecutionException) {
                task.abandon()
                task.awaitRelease()
                null
            } catch (_: InterruptedException) {
                task.abandon()
                task.awaitReleaseUninterruptibly()
                Thread.currentThread().interrupt()
                null
            }
        } finally {
            try {
                externalCancellation?.setOnCancelListener(null)
            } catch (_: RuntimeException) {
                // The import already owns any cancellation work it accepted.
            }
        }
    }

    fun cancelActive() {
        activeTask.get()?.abandon()
    }

    private fun admit(task: StageTask): Boolean {
        while (true) {
            val previous = activeTask.get()
            if (previous == null) {
                return activeTask.compareAndSet(null, task)
            }
            if (!previous.isAbandoned()) return false
            val remainingMs =
                task.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L ||
                !previous.awaitRelease(minOf(remainingMs, TASK_RELEASE_WAIT_MS))
            ) {
                return false
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // closed prevents another task from registering. Queue process
        // termination before either executor winds down.
        cancelActive()
        executor.shutdown()
        cleanupExecutor.shutdown()
    }

    private inner class StageTask(
        private val source: Uri,
        val deadlineElapsedRealtimeMs: Long,
    ) : Callable<StagedClipboardMedia> {
        private val state = AtomicReference(StageState.RUNNING)
        private val released = CountDownLatch(1)
        private val activeResource = AtomicReference<Closeable?>()
        private val partialPath = AtomicReference<Path?>()
        private val remoteSession = AtomicReference<RemoteImportSession?>()
        private val completedStage = AtomicReference<StagedClipboardMedia?>()
        private val runnerLock = Any()
        private var runner: Thread? = null

        lateinit var future: FutureTask<StagedClipboardMedia>

        override fun call(): StagedClipboardMedia {
            val currentThread = Thread.currentThread()
            synchronized(runnerLock) {
                runner = currentThread
            }
            var staged: StagedClipboardMedia? = null
            try {
                staged = copyToPrivateStage(source)
                completedStage.set(staged)
                if (!state.compareAndSet(StageState.RUNNING, StageState.COMPLETED)) {
                    completedStage.compareAndSet(staged, null)
                    staged.close()
                    throw IOException("Clipboard import was abandoned.")
                }
                return staged
            } finally {
                closeActiveResource()
                if (state.get() == StageState.ABANDONED) {
                    completedStage.getAndSet(null)?.close()
                    staged?.close()
                }
                synchronized(runnerLock) {
                    if (runner === currentThread) {
                        runner = null
                    }
                }
            }
        }

        fun claim(): Boolean {
            if (!state.compareAndSet(StageState.COMPLETED, StageState.CLAIMED)) return false
            completedStage.set(null)
            return true
        }

        fun isAbandoned(): Boolean = state.get() == StageState.ABANDONED

        fun markReleased() {
            released.countDown()
        }

        fun awaitRelease(
            waitMs: Long = TASK_RELEASE_WAIT_MS,
        ): Boolean {
            return try {
                released.await(waitMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun awaitReleaseUninterruptibly() {
            val deadline = elapsedDeadline(TASK_RELEASE_WAIT_MS)
            var interrupted = false
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    if (released.await(remaining, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        fun abandon() {
            while (true) {
                when (state.get()) {
                    StageState.RUNNING -> {
                        if (!state.compareAndSet(StageState.RUNNING, StageState.ABANDONED)) continue
                        cancelWork()
                        return
                    }
                    StageState.COMPLETED -> {
                        if (!state.compareAndSet(StageState.COMPLETED, StageState.ABANDONED)) continue
                        completedStage.getAndSet(null)?.close()
                        cancelWork()
                        return
                    }
                    StageState.CLAIMED,
                    StageState.ABANDONED,
                    -> return
                }
            }
        }

        private fun cancelWork() {
            if (::future.isInitialized) {
                future.cancel(false)
            }
            try {
                cleanupExecutor.execute {
                    cancelRemoteSession(remoteSession.get())
                    interruptRunner()
                    closeActiveResource()
                    deletePartial()
                }
            } catch (_: RejectedExecutionException) {
                // The remote hard deadline remains the final containment
                // boundary if shutdown wins this small cancellation race.
                interruptRunner()
                closeActiveResource()
                deletePartial()
            }
        }

        private fun interruptRunner() {
            synchronized(runnerLock) {
                runner?.interrupt()
            }
        }

        private fun closeActiveResource() {
            try {
                activeResource.getAndSet(null)?.close()
            } catch (_: Exception) {
                // Process death and unlinking remain the cleanup boundary.
            }
        }

        private fun deletePartial() {
            partialPath.get()?.let { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: Exception) {
                    // The task's finalizer retries after the worker exits.
                }
            }
        }

        private fun copyToPrivateStage(source: Uri): StagedClipboardMedia {
            validateSource(source)
            ensureRunning()
            val directory = appContext.cacheDir.toPath().resolve(stagingDirectory)
            Files.createDirectories(directory)
            cleanStagingDirectoryOnce(directory)
            val maximumBytes = stageCapacity(appContext)
            if (maximumBytes !in 1..ClipboardFileStorage.MAX_MEDIA_BYTES) {
                throw IOException("Clipboard media storage is unavailable.")
            }
            ensureRunning()
            val path = Files.createTempFile(directory, PARTIAL_PREFIX, PARTIAL_SUFFIX)
            partialPath.set(path)
            var accepted = false
            try {
                ensureRunning()
                val destination = ParcelFileDescriptor.open(
                    path.toFile(),
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
                activeResource.set(destination)
                val result = try {
                    importInWorker(
                        source = source,
                        destination = destination,
                        maximumBytes = maximumBytes,
                    )
                } finally {
                    if (activeResource.compareAndSet(destination, null)) {
                        try {
                            destination.close()
                        } catch (_: Exception) {
                            // The returned file is validated below.
                        }
                    }
                }
                ensureRunning()
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                    result.byteCount !in 1..maximumBytes ||
                    Files.size(path) != result.byteCount
                ) {
                    throw IOException("Clipboard media staging failed.")
                }
                accepted = true
                partialPath.compareAndSet(path, null)
                return StagedClipboardMedia(
                    path = path,
                    byteCount = result.byteCount,
                    displayName = result.displayName,
                    sourceMimeType = result.sourceMimeType,
                )
            } finally {
                closeActiveResource()
                if (!accepted) {
                    try {
                        Files.deleteIfExists(path)
                    } catch (_: Exception) {
                        // A killed worker may still be releasing its duplicate.
                    }
                }
                partialPath.compareAndSet(path, null)
            }
        }

        private fun importInWorker(
            source: Uri,
            destination: ParcelFileDescriptor,
            maximumBytes: Long,
        ): RemoteImportResult {
            val sourceUri = source.toString()
            if (sourceUri.length > ClipboardExternalMediaImportWorkerContract.MAX_SOURCE_URI_LENGTH) {
                throw IOException("Clipboard media source is unavailable.")
            }
            val requestToken = UUID.randomUUID().toString()
            val session = RemoteImportSession(requestToken)
            if (!remoteSession.compareAndSet(null, session)) {
                throw IOException("Clipboard import worker is unavailable.")
            }
            var completed = false
            var workerDied = false
            try {
                while (true) {
                    ensureRunning()
                    if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) {
                        throw IOException("Clipboard import worker timed out.")
                    }
                    val client = acquireWorkerClient()
                    var retry = false
                    try {
                        if (client.localContentProvider != null) {
                            throw IOException("Clipboard import worker is not isolated.")
                        }
                        val begin = try {
                            client.call(
                                ClipboardExternalMediaImportWorkerContract.METHOD_BEGIN,
                                requestToken,
                                Bundle(2).apply {
                                    putInt(
                                        ClipboardExternalMediaImportWorkerContract
                                            .KEY_PROTOCOL_VERSION,
                                        ClipboardExternalMediaImportWorkerContract
                                            .PROTOCOL_VERSION,
                                    )
                                    putLong(
                                        ClipboardExternalMediaImportWorkerContract
                                            .KEY_DEADLINE_ELAPSED_REALTIME_MS,
                                        deadlineElapsedRealtimeMs,
                                    )
                                },
                            )
                        } catch (_: DeadObjectException) {
                            retry = true
                            null
                        } catch (_: RemoteException) {
                            retry = true
                            null
                        }
                        if (retry) continue
                        val accepted = parseBeginResult(
                            begin ?: throw IOException(
                                "Clipboard import worker is unavailable.",
                            ),
                        )
                        val generation = accepted.generation
                        if (generation == null) {
                            retry = accepted.busy
                            if (retry) continue
                            throw IOException("Clipboard import worker rejected the request.")
                        }
                        session.generation.set(generation)
                        ensureRunning()

                        val started = try {
                            client.call(
                                ClipboardExternalMediaImportWorkerContract.METHOD_STAGE,
                                requestToken,
                                Bundle(5).apply {
                                    putInt(
                                        ClipboardExternalMediaImportWorkerContract
                                            .KEY_PROTOCOL_VERSION,
                                        ClipboardExternalMediaImportWorkerContract
                                            .PROTOCOL_VERSION,
                                    )
                                    putString(
                                        ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
                                        generation,
                                    )
                                    putString(
                                        ClipboardExternalMediaImportWorkerContract.KEY_SOURCE_URI,
                                        sourceUri,
                                    )
                                    putParcelable(
                                        ClipboardExternalMediaImportWorkerContract.KEY_DESTINATION,
                                        destination,
                                    )
                                    putLong(
                                        ClipboardExternalMediaImportWorkerContract
                                            .KEY_MAXIMUM_BYTES,
                                        maximumBytes,
                                    )
                                },
                            )
                        } catch (_: DeadObjectException) {
                            workerDied = true
                            throw IOException("Clipboard import worker terminated.")
                        } catch (_: RemoteException) {
                            workerDied = true
                            throw IOException("Clipboard import worker terminated.")
                        } ?: throw IOException("Clipboard import worker is unavailable.")
                        parseAcceptedResult(started, generation)
                        while (true) {
                            ensureRunning()
                            awaitWorkerRetry(deadlineElapsedRealtimeMs)
                            if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) {
                                throw IOException("Clipboard import worker timed out.")
                            }
                            val polled = try {
                                client.call(
                                    ClipboardExternalMediaImportWorkerContract.METHOD_POLL,
                                    requestToken,
                                    generationRequest(generation),
                                )
                            } catch (_: DeadObjectException) {
                                workerDied = true
                                throw IOException("Clipboard import worker terminated.")
                            } catch (_: RemoteException) {
                                workerDied = true
                                throw IOException("Clipboard import worker terminated.")
                            } ?: throw IOException("Clipboard import worker is unavailable.")
                            val result = parsePollResult(
                                bundle = polled,
                                expectedGeneration = generation,
                                maximumBytes = maximumBytes,
                            ) ?: continue
                            completed = true
                            return result
                        }
                    } finally {
                        client.close()
                        if (retry) {
                            awaitWorkerRetry(deadlineElapsedRealtimeMs)
                        }
                    }
                }
            } finally {
                remoteSession.compareAndSet(session, null)
                if (!completed && !workerDied) {
                    cancelRemoteSession(session)
                }
            }
        }

        private fun ensureRunning() {
            if (state.get() != StageState.RUNNING || Thread.currentThread().isInterrupted) {
                throw IOException("Clipboard import was abandoned.")
            }
        }
    }

    private fun acquireWorkerClient(): ContentProviderClient {
        return appContext.contentResolver.acquireUnstableContentProviderClient(
            ClipboardExternalMediaImportWorkerContract.AUTHORITY,
        ) ?: throw IOException("Clipboard import worker is unavailable.")
    }

    private fun cancelRemoteSession(session: RemoteImportSession?) {
        val active = session ?: return
        val generation = active.generation.get() ?: return
        try {
            val client = acquireWorkerClient()
            try {
                if (client.localContentProvider != null) return
                client.call(
                    ClipboardExternalMediaImportWorkerContract.METHOD_CANCEL,
                    active.requestToken,
                    generationRequest(generation),
                )
            } finally {
                client.close()
            }
        } catch (_: Exception) {
            // DeadObjectException is the expected successful termination path.
        }
    }

    private fun parsePollResult(
        bundle: Bundle,
        expectedGeneration: String,
        maximumBytes: Long,
    ): RemoteImportResult? {
        requireProtocol(bundle)
        when (bundle.getInt(ClipboardExternalMediaImportWorkerContract.KEY_STATUS)) {
            ClipboardExternalMediaImportWorkerContract.STATUS_BUSY -> {
                requireExactKeys(bundle, GENERATION_RESPONSE_KEYS)
                if (bundle.getString(
                        ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
                    ) != expectedGeneration
                ) {
                    throw IOException("Clipboard import worker response is invalid.")
                }
                return null
            }
            ClipboardExternalMediaImportWorkerContract.STATUS_SUCCESS -> Unit
            else -> {
                requireExactKeys(bundle, STATUS_RESPONSE_KEYS)
                throw IOException("Clipboard import worker rejected the source.")
            }
        }
        if (!SUCCESS_RESPONSE_KEYS.containsAll(bundle.keySet()) ||
            !bundle.keySet().containsAll(SUCCESS_REQUIRED_RESPONSE_KEYS)
        ) {
            throw IOException("Clipboard import worker rejected the source.")
        }
        val byteCount = bundle.getLong(
            ClipboardExternalMediaImportWorkerContract.KEY_BYTE_COUNT,
            -1L,
        )
        if (byteCount !in 1..maximumBytes) {
            throw IOException("Clipboard import worker response is invalid.")
        }
        val displayName = bundle.getString(
            ClipboardExternalMediaImportWorkerContract.KEY_DISPLAY_NAME,
        )?.let(::normalizeClipboardMediaDisplayName)
        val sourceMimeType = normalizeSourceMimeType(
            bundle.getString(
                ClipboardExternalMediaImportWorkerContract.KEY_SOURCE_MIME_TYPE,
            ),
        )
        return RemoteImportResult(
            byteCount = byteCount,
            displayName = displayName,
            sourceMimeType = sourceMimeType,
        )
    }

    private fun parseBeginResult(bundle: Bundle): BeginResult {
        requireProtocol(bundle)
        return when (
            bundle.getInt(ClipboardExternalMediaImportWorkerContract.KEY_STATUS)
        ) {
            ClipboardExternalMediaImportWorkerContract.STATUS_ACCEPTED -> {
                requireExactKeys(bundle, GENERATION_RESPONSE_KEYS)
                val generation = bundle.getString(
                    ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
                )?.takeIf(ClipboardExternalMediaImportWorkerContract.TOKEN::matches)
                    ?: throw IOException("Clipboard import worker response is invalid.")
                BeginResult(generation = generation, busy = false)
            }
            ClipboardExternalMediaImportWorkerContract.STATUS_BUSY -> {
                requireExactKeys(bundle, STATUS_RESPONSE_KEYS)
                BeginResult(generation = null, busy = true)
            }
            else -> {
                requireExactKeys(bundle, STATUS_RESPONSE_KEYS)
                BeginResult(generation = null, busy = false)
            }
        }
    }

    private fun parseAcceptedResult(
        bundle: Bundle,
        expectedGeneration: String,
    ) {
        requireProtocol(bundle)
        requireExactKeys(bundle, GENERATION_RESPONSE_KEYS)
        if (bundle.getInt(ClipboardExternalMediaImportWorkerContract.KEY_STATUS) !=
            ClipboardExternalMediaImportWorkerContract.STATUS_ACCEPTED ||
            bundle.getString(ClipboardExternalMediaImportWorkerContract.KEY_GENERATION) !=
            expectedGeneration
        ) {
            throw IOException("Clipboard import worker response is invalid.")
        }
    }

    private fun validateSource(source: Uri) {
        if (source.scheme != ContentResolver.SCHEME_CONTENT ||
            source.authority.isNullOrEmpty() ||
            source.host != source.authority ||
            source.userInfo != null ||
            source.authority == ClipboardExternalMediaImportWorkerContract.AUTHORITY ||
            source.authority == ClipboardMediaProvider.AUTHORITY ||
            source.authority == OreoSystemClipboardMediaProvider.AUTHORITY
        ) {
            throw IOException("Clipboard media source is unavailable.")
        }
    }

    private fun cleanStagingDirectoryOnce(directory: Path) {
        if (!stagingDirectoryCleaned.compareAndSet(false, true)) return
        try {
            Files.newDirectoryStream(directory).use { children ->
                for (child in children) {
                    val name = child.fileName.toString()
                    if (name.startsWith(PARTIAL_PREFIX) && name.endsWith(PARTIAL_SUFFIX)) {
                        Files.deleteIfExists(child)
                    }
                }
            }
        } catch (error: Exception) {
            stagingDirectoryCleaned.set(false)
            throw error
        }
    }

    private fun requireProtocol(bundle: Bundle) {
        if (bundle.getInt(
                ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
                Int.MIN_VALUE,
            ) != ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION
        ) {
            throw IOException("Clipboard import worker response is invalid.")
        }
    }

    private fun requireExactKeys(bundle: Bundle, expected: Set<String>) {
        if (bundle.keySet() != expected) {
            throw IOException("Clipboard import worker response is invalid.")
        }
    }

    private fun generationRequest(generation: String): Bundle =
        Bundle(2).apply {
            putInt(
                ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
                ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION,
            )
            putString(
                ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
                generation,
            )
        }

    private fun normalizeSourceMimeType(value: String?): String? {
        return value
            ?.trim()
            ?.lowercase()
            ?.takeIf { normalized ->
                normalized.length <= ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH &&
                    '*' !in normalized &&
                    EXTERNAL_MIME_TYPE.matches(normalized)
            }
    }

    private fun elapsedDeadline(delayMs: Long): Long {
        val now = SystemClock.elapsedRealtime()
        return if (now > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else now + delayMs
    }

    private fun awaitWorkerRetry(deadlineElapsedRealtimeMs: Long) {
        val remaining = deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()
        if (remaining <= 0L) return
        Thread.sleep(minOf(WORKER_RETRY_DELAY_MS, remaining))
    }

    private enum class StageState {
        RUNNING,
        COMPLETED,
        CLAIMED,
        ABANDONED,
    }

    private class RemoteImportSession(
        val requestToken: String,
    ) {
        val generation = AtomicReference<String?>()
    }

    private data class RemoteImportResult(
        val byteCount: Long,
        val displayName: String?,
        val sourceMimeType: String?,
    )

    private data class BeginResult(
        val generation: String?,
        val busy: Boolean,
    )

    companion object {
        private const val DEFAULT_STAGING_DIRECTORY = "clipboard-provider-imports"
        private const val PARTIAL_PREFIX = ".clipboard-provider-"
        private const val PARTIAL_SUFFIX = ".partial"
        private const val WORKER_RETRY_DELAY_MS = 25L
        private const val TASK_RELEASE_WAIT_MS =
            ClipboardExternalMediaImportWorkerContract.CANCEL_GRACE_MS +
                ClipboardExternalMediaImportWorkerContract.WATCHDOG_GRACE_MS +
                500L
        private val STAGING_DIRECTORY_NAME = Regex("[a-z][a-z0-9-]{0,63}")
        private val EXTERNAL_MIME_TYPE =
            Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")
        private val STATUS_RESPONSE_KEYS = setOf(
            ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
            ClipboardExternalMediaImportWorkerContract.KEY_STATUS,
        )
        private val GENERATION_RESPONSE_KEYS =
            STATUS_RESPONSE_KEYS + ClipboardExternalMediaImportWorkerContract.KEY_GENERATION
        private val SUCCESS_REQUIRED_RESPONSE_KEYS =
            STATUS_RESPONSE_KEYS + ClipboardExternalMediaImportWorkerContract.KEY_BYTE_COUNT
        private val SUCCESS_RESPONSE_KEYS = SUCCESS_REQUIRED_RESPONSE_KEYS + setOf(
            ClipboardExternalMediaImportWorkerContract.KEY_DISPLAY_NAME,
            ClipboardExternalMediaImportWorkerContract.KEY_SOURCE_MIME_TYPE,
        )
    }
}

internal class StagedClipboardMedia(
    val path: Path,
    val byteCount: Long,
    val displayName: String?,
    val sourceMimeType: String?,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                Files.deleteIfExists(path)
            } catch (_: Exception) {
                // Staging cleanup is best effort after ownership leaves this object.
            }
        }
    }

    override fun toString(): String =
        "StagedClipboardMedia(byteCount=$byteCount, path=<redacted>)"
}
