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
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Stages foreign provider data in a disposable app process. A provider which
 * ignores cancellation can kill only that worker, not the calling app process.
 */
internal class DisposableExternalContentImporter(
    context: Context,
    private val timeoutMs: Long,
    private val stageCapacity: (Context) -> Long = ClipboardFileStorage::externalStageCapacity,
    private val stagingDirectory: String = DEFAULT_STAGING_DIRECTORY,
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val activeTask = AtomicReference<StageTask?>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(1),
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
        maximumBytes: Long? = null,
        minimumBytes: Long = 1L,
    ): StagedExternalContent? {
        if (closed.get()) return null
        val task = StageTask(
            source = source,
            deadlineElapsedRealtimeMs = elapsedDeadline(timeoutMs),
            requestedMaximumBytes = maximumBytes,
            requestedMinimumBytes = minimumBytes,
        )
        val future = FutureTask(task)
        task.future = future
        if (!admit(task)) return null
        return stageAdmittedTask(task, future, externalCancellation)
    }

    private fun stageAdmittedTask(
        task: StageTask,
        future: FutureTask<StagedExternalContent>,
        externalCancellation: CancellationSignal?,
    ): StagedExternalContent? {
        if (closed.get()) {
            releaseUnstartedTask(task)
            return null
        }
        try {
            val cancellationRegistered =
                registerExternalCancellation(externalCancellation, task)
            val started = cancellationRegistered && startTask(task, future)
            return if (started) awaitTask(task, future) else null
        } finally {
            clearExternalCancellation(externalCancellation)
        }
    }

    private fun registerExternalCancellation(cancellation: CancellationSignal?, task: StageTask): Boolean = try {
        cancellation?.setOnCancelListener(task::abandon)
        true
    } catch (_: RuntimeException) {
        activeTask.compareAndSet(task, null)
        task.abandon()
        false
    }

    private fun clearExternalCancellation(cancellation: CancellationSignal?) {
        try {
            cancellation?.setOnCancelListener(null)
        } catch (_: RuntimeException) {
            // The import already owns any cancellation work it accepted.
        }
    }

    private fun startTask(task: StageTask, future: FutureTask<StagedExternalContent>): Boolean = try {
        executor.execute {
            try {
                future.run()
            } finally {
                activeTask.compareAndSet(task, null)
                task.markReleased()
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        releaseUnstartedTask(task)
        false
    }

    private fun releaseUnstartedTask(task: StageTask) {
        activeTask.compareAndSet(task, null)
        task.abandon()
        task.markReleased()
    }

    private fun awaitTask(task: StageTask, future: FutureTask<StagedExternalContent>): StagedExternalContent? {
        val remainingMs =
            task.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) return task.abandonAndAwait()
        return try {
            val staged = future.get(remainingMs, TimeUnit.MILLISECONDS)
            if (task.claim()) {
                activeTask.compareAndSet(task, null)
                staged
            } else {
                null
            }
        } catch (_: TimeoutException) {
            task.abandonAndAwait()
        } catch (_: java.util.concurrent.CancellationException) {
            task.abandonAndAwait()
        } catch (_: ExecutionException) {
            task.abandonAndAwait()
        } catch (_: InterruptedException) {
            task.abandon()
            task.awaitReleaseUninterruptibly()
            Thread.currentThread().interrupt()
            null
        }
    }

    fun cancelActive() {
        activeTask.get()?.abandon()
    }

    private tailrec fun admit(task: StageTask): Boolean {
        val previous = activeTask.get()
        return when {
            previous == null -> activeTask.compareAndSet(null, task)
            !previous.isAbandoned() -> false
            !previous.awaitReleaseBefore(task.deadlineElapsedRealtimeMs) -> false
            else -> admit(task)
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
        private val requestedMaximumBytes: Long?,
        private val requestedMinimumBytes: Long,
    ) : Callable<StagedExternalContent> {
        private val state = AtomicReference(StageState.RUNNING)
        private val released = CountDownLatch(1)
        private val activeResource = AtomicReference<Closeable?>()
        private val partialPath = AtomicReference<Path?>()
        private val remoteSession = AtomicReference<RemoteImportSession?>()
        private val completedStage = AtomicReference<StagedExternalContent?>()
        private val runnerLock = Any()
        private var runner: Thread? = null

        lateinit var future: FutureTask<StagedExternalContent>

        override fun call(): StagedExternalContent {
            val currentThread = Thread.currentThread()
            synchronized(runnerLock) {
                runner = currentThread
            }
            var staged: StagedExternalContent? = null
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

        fun awaitRelease(waitMs: Long = TASK_RELEASE_WAIT_MS): Boolean = try {
            released.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        fun awaitReleaseBefore(deadlineElapsedRealtimeMs: Long): Boolean {
            val remainingMs =
                deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()
            return remainingMs > 0L &&
                awaitRelease(minOf(remainingMs, TASK_RELEASE_WAIT_MS))
        }

        fun awaitReleaseUninterruptibly() {
            val deadline = elapsedDeadline(TASK_RELEASE_WAIT_MS)
            var interrupted = false
            var finished = false
            while (!finished) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    finished = true
                } else {
                    try {
                        finished = released.await(remaining, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        fun abandon() = transitionToAbandoned()

        fun abandonAndAwait(): StagedExternalContent? {
            abandon()
            awaitRelease()
            return null
        }

        private tailrec fun transitionToAbandoned() {
            val current = state.get()
            when (current) {
                StageState.RUNNING,
                StageState.COMPLETED,
                -> {
                    if (state.compareAndSet(current, StageState.ABANDONED)) {
                        if (current == StageState.COMPLETED) {
                            completedStage.getAndSet(null)?.close()
                        }
                        cancelWork()
                    } else {
                        transitionToAbandoned()
                    }
                }

                StageState.CLAIMED,
                StageState.ABANDONED,
                -> Unit
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

        private fun copyToPrivateStage(source: Uri): StagedExternalContent {
            val sourceUri = validateSource(source)
            ensureRunning()
            val directory = appContext.cacheDir.toPath().resolve(stagingDirectory)
            Files.createDirectories(directory)
            cleanStagingDirectoryOnce(directory)
            val maximumBytes = requestedMaximumBytes ?: stageCapacity(appContext)
            if (maximumBytes !in 1..ClipboardFileStorage.MAX_MEDIA_BYTES ||
                requestedMinimumBytes !in 0..maximumBytes
            ) {
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
                        sourceUri = sourceUri,
                        destination = destination,
                        minimumBytes = requestedMinimumBytes,
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
                    result.byteCount !in requestedMinimumBytes..maximumBytes ||
                    Files.size(path) != result.byteCount
                ) {
                    throw IOException("Clipboard media staging failed.")
                }
                accepted = true
                partialPath.compareAndSet(path, null)
                return StagedExternalContent(
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
            sourceUri: String,
            destination: ParcelFileDescriptor,
            minimumBytes: Long,
            maximumBytes: Long,
        ): RemoteImportResult {
            val session = registerRemoteSession()
            var completed = false
            var workerDied = false
            try {
                val admission = admitWorker(session)
                try {
                    startWorker(
                        admission = admission,
                        session = session,
                        sourceUri = sourceUri,
                        destination = destination,
                        minimumBytes = minimumBytes,
                        maximumBytes = maximumBytes,
                    )
                    val result = pollWorker(
                        admission = admission,
                        session = session,
                        minimumBytes = minimumBytes,
                        maximumBytes = maximumBytes,
                    )
                    completed = true
                    return result
                } catch (error: WorkerTerminatedException) {
                    workerDied = true
                    throw error
                } finally {
                    admission.client.close()
                }
            } finally {
                remoteSession.compareAndSet(session, null)
                if (!completed && !workerDied) {
                    cancelRemoteSession(session)
                }
            }
        }

        private fun registerRemoteSession(): RemoteImportSession {
            val session = RemoteImportSession(UUID.randomUUID().toString())
            if (!remoteSession.compareAndSet(null, session)) {
                workerUnavailable()
            }
            return session
        }

        private fun admitWorker(session: RemoteImportSession): WorkerAdmission {
            var admission: WorkerAdmission? = null
            while (admission == null) {
                ensureRunning()
                requireWorkerTimeRemaining()
                val client = acquireWorkerClient()
                var retainClient = false
                try {
                    requireIsolatedWorker(client)
                    val beginCall = callWorkerBegin(client, session.requestToken)
                    if (!beginCall.retry) {
                        val accepted = parseBeginResult(
                            beginCall.response ?: workerUnavailable(),
                        )
                        val generation = accepted.generation
                        if (generation != null) {
                            session.generation.set(generation)
                            admission = WorkerAdmission(client, generation)
                            retainClient = true
                        } else if (!accepted.busy) {
                            workerRejectedRequest()
                        }
                    }
                } finally {
                    if (!retainClient) client.close()
                }
                if (admission == null) {
                    awaitWorkerRetry(deadlineElapsedRealtimeMs)
                }
            }
            return requireNotNull(admission)
        }

        private fun callWorkerBegin(client: ContentProviderClient, requestToken: String): WorkerBeginCall = try {
            WorkerBeginCall(
                response = client.call(
                    ClipboardExternalMediaImportWorkerContract.METHOD_BEGIN,
                    requestToken,
                    workerBeginRequest(deadlineElapsedRealtimeMs),
                ),
                retry = false,
            )
        } catch (_: RemoteException) {
            WorkerBeginCall(response = null, retry = true)
        }

        private fun startWorker(
            admission: WorkerAdmission,
            session: RemoteImportSession,
            sourceUri: String,
            destination: ParcelFileDescriptor,
            minimumBytes: Long,
            maximumBytes: Long,
        ) {
            ensureRunning()
            val started = callActiveWorker(
                client = admission.client,
                method = ClipboardExternalMediaImportWorkerContract.METHOD_STAGE,
                requestToken = session.requestToken,
                request = workerStageRequest(
                    generation = admission.generation,
                    sourceUri = sourceUri,
                    destination = destination,
                    minimumBytes = minimumBytes,
                    maximumBytes = maximumBytes,
                ),
            )
            parseAcceptedResult(started, admission.generation)
        }

        private fun pollWorker(
            admission: WorkerAdmission,
            session: RemoteImportSession,
            minimumBytes: Long,
            maximumBytes: Long,
        ): RemoteImportResult {
            var result: RemoteImportResult? = null
            while (result == null) {
                ensureRunning()
                awaitWorkerRetry(deadlineElapsedRealtimeMs)
                requireWorkerTimeRemaining()
                val polled = callActiveWorker(
                    client = admission.client,
                    method = ClipboardExternalMediaImportWorkerContract.METHOD_POLL,
                    requestToken = session.requestToken,
                    request = generationRequest(admission.generation),
                )
                result = parsePollResult(
                    bundle = polled,
                    expectedGeneration = admission.generation,
                    minimumBytes = minimumBytes,
                    maximumBytes = maximumBytes,
                )
            }
            return result
        }

        private fun callActiveWorker(
            client: ContentProviderClient,
            method: String,
            requestToken: String,
            request: Bundle,
        ): Bundle = try {
            client.call(method, requestToken, request) ?: workerUnavailable()
        } catch (_: RemoteException) {
            throw WorkerTerminatedException()
        }

        private fun requireIsolatedWorker(client: ContentProviderClient) {
            if (client.localContentProvider != null) {
                throw IOException("Clipboard import worker is not isolated.")
            }
        }

        private fun requireWorkerTimeRemaining() {
            if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) {
                throw IOException("Clipboard import worker timed out.")
            }
        }

        private fun ensureRunning() {
            if (state.get() != StageState.RUNNING || Thread.currentThread().isInterrupted) {
                throw IOException("Clipboard import was abandoned.")
            }
        }
    }

    private fun acquireWorkerClient(): ContentProviderClient =
        appContext.contentResolver.acquireUnstableContentProviderClient(
            ClipboardExternalMediaImportWorkerContract.AUTHORITY,
        ) ?: throw IOException("Clipboard import worker is unavailable.")

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
        minimumBytes: Long,
        maximumBytes: Long,
    ): RemoteImportResult? {
        requireProtocol(bundle)
        return when (bundle.getInt(ClipboardExternalMediaImportWorkerContract.KEY_STATUS)) {
            ClipboardExternalMediaImportWorkerContract.STATUS_BUSY -> {
                requireExpectedGeneration(bundle, expectedGeneration)
                null
            }

            ClipboardExternalMediaImportWorkerContract.STATUS_SUCCESS ->
                parseSuccessfulPoll(bundle, minimumBytes, maximumBytes)

            else -> {
                requireExactKeys(bundle, STATUS_RESPONSE_KEYS)
                workerRejectedSource()
            }
        }
    }

    private fun requireExpectedGeneration(bundle: Bundle, expectedGeneration: String) {
        requireExactKeys(bundle, GENERATION_RESPONSE_KEYS)
        if (bundle.getString(
                ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
            ) != expectedGeneration
        ) {
            invalidWorkerResponse()
        }
    }

    private fun parseSuccessfulPoll(bundle: Bundle, minimumBytes: Long, maximumBytes: Long): RemoteImportResult {
        if (!SUCCESS_RESPONSE_KEYS.containsAll(bundle.keySet()) ||
            !bundle.keySet().containsAll(SUCCESS_REQUIRED_RESPONSE_KEYS)
        ) {
            workerRejectedSource()
        }
        val byteCount = bundle.getLong(
            ClipboardExternalMediaImportWorkerContract.KEY_BYTE_COUNT,
            -1L,
        )
        if (byteCount !in minimumBytes..maximumBytes) {
            invalidWorkerResponse()
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
                    ?: invalidWorkerResponse()
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

    private fun parseAcceptedResult(bundle: Bundle, expectedGeneration: String) {
        requireProtocol(bundle)
        requireExactKeys(bundle, GENERATION_RESPONSE_KEYS)
        if (bundle.getInt(ClipboardExternalMediaImportWorkerContract.KEY_STATUS) !=
            ClipboardExternalMediaImportWorkerContract.STATUS_ACCEPTED ||
            bundle.getString(ClipboardExternalMediaImportWorkerContract.KEY_GENERATION) !=
            expectedGeneration
        ) {
            invalidWorkerResponse()
        }
    }

    private fun validateSource(source: Uri): String {
        if (!ClipboardExternalMediaImportWorkerContract.admitsExternalSource(source)) {
            throw IOException("Clipboard media source is unavailable.")
        }
        return source.toString()
    }

    private fun cleanStagingDirectoryOnce(directory: Path) {
        val key = directory.toRealPath()
        synchronized(STAGING_DIRECTORY_CLEANUP_GUARD) {
            if (key in CLEANED_STAGING_DIRECTORIES) return
            Files.newDirectoryStream(directory).use { children ->
                for (child in children) {
                    val name = child.fileName.toString()
                    if (name.startsWith(PARTIAL_PREFIX) && name.endsWith(PARTIAL_SUFFIX)) {
                        Files.deleteIfExists(child)
                    }
                }
            }
            CLEANED_STAGING_DIRECTORIES.add(key)
        }
    }

    private fun requireProtocol(bundle: Bundle) {
        if (bundle.getInt(
                ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
                Int.MIN_VALUE,
            ) != ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION
        ) {
            invalidWorkerResponse()
        }
    }

    private fun requireExactKeys(bundle: Bundle, expected: Set<String>) {
        if (bundle.keySet() != expected) {
            invalidWorkerResponse()
        }
    }

    private fun generationRequest(generation: String): Bundle = Bundle(2).apply {
        putInt(
            ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
            ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION,
        )
        putString(
            ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
            generation,
        )
    }

    private fun normalizeSourceMimeType(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { normalized ->
            normalized.length <= ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH &&
                '*' !in normalized &&
                EXTERNAL_MIME_TYPE.matches(normalized)
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

    private class RemoteImportSession(val requestToken: String) {
        val generation = AtomicReference<String?>()
    }

    private class WorkerAdmission(val client: ContentProviderClient, val generation: String)

    private class WorkerBeginCall(val response: Bundle?, val retry: Boolean)

    private class WorkerTerminatedException : IOException("Clipboard import worker terminated.")

    private data class RemoteImportResult(val byteCount: Long, val displayName: String?, val sourceMimeType: String?)

    private data class BeginResult(val generation: String?, val busy: Boolean)

    companion object {
        private const val DEFAULT_STAGING_DIRECTORY = "clipboard-provider-imports"
        private const val PARTIAL_PREFIX = ".clipboard-provider-"
        private const val PARTIAL_SUFFIX = ".partial"
        private const val STAGE_REQUEST_CAPACITY = 6
        private const val WORKER_RETRY_DELAY_MS = 25L
        private const val TASK_RELEASE_WAIT_MS =
            ClipboardExternalMediaImportWorkerContract.CANCEL_GRACE_MS +
                ClipboardExternalMediaImportWorkerContract.WATCHDOG_GRACE_MS +
                500L

        // Never evict entries: a claimed stage can outlive the importer which created it.
        private val STAGING_DIRECTORY_CLEANUP_GUARD = Any()
        private val CLEANED_STAGING_DIRECTORIES = mutableSetOf<Path>()
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

        private fun workerBeginRequest(deadlineElapsedRealtimeMs: Long): Bundle = Bundle(2).apply {
            putInt(
                ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
                ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION,
            )
            putLong(
                ClipboardExternalMediaImportWorkerContract
                    .KEY_DEADLINE_ELAPSED_REALTIME_MS,
                deadlineElapsedRealtimeMs,
            )
        }

        private fun workerStageRequest(
            generation: String,
            sourceUri: String,
            destination: ParcelFileDescriptor,
            minimumBytes: Long,
            maximumBytes: Long,
        ): Bundle = Bundle(STAGE_REQUEST_CAPACITY).apply {
            putInt(
                ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
                ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION,
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
                ClipboardExternalMediaImportWorkerContract.KEY_MINIMUM_BYTES,
                minimumBytes,
            )
            putLong(
                ClipboardExternalMediaImportWorkerContract.KEY_MAXIMUM_BYTES,
                maximumBytes,
            )
        }

        private fun workerUnavailable(): Nothing = throw IOException("Clipboard import worker is unavailable.")

        private fun workerRejectedRequest(): Nothing =
            throw IOException("Clipboard import worker rejected the request.")

        private fun workerRejectedSource(): Nothing = throw IOException("Clipboard import worker rejected the source.")

        private fun invalidWorkerResponse(): Nothing = throw IOException("Clipboard import worker response is invalid.")
    }
}

internal class StagedExternalContent(
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
            } catch (_: IOException) {
                // Staging cleanup is best effort after ownership leaves this object.
            } catch (_: SecurityException) {
                // Staging cleanup is best effort after ownership leaves this object.
            }
        }
    }

    override fun toString(): String = "StagedExternalContent(byteCount=$byteCount, path=<redacted>)"
}

/**
 * Compatibility names for established clipboard call sites.
 */
internal typealias ClipboardExternalMediaImporter = DisposableExternalContentImporter
internal typealias StagedClipboardMedia = StagedExternalContent
