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

import android.content.ContentProvider
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import dev.patrickgold.florisboard.currentFlorisApplicationProcessName
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs all calls into foreign clipboard providers in a disposable process.
 *
 * The app accesses this provider only through an unstable provider client, so
 * the watchdog can terminate this process without terminating the app process.
 */
internal class ClipboardExternalMediaImportWorkerProvider : ContentProvider() {
    private val activeRequest = AtomicReference<ActiveRequest?>()
    private val hardKillExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "clipboard-import-watchdog").apply {
            isDaemon = true
        }
    }
    private val cancellationExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "clipboard-import-cancel").apply {
            isDaemon = true
        }
    }
    private val stageExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "clipboard-import-stage").apply {
            isDaemon = true
        }
    }
    private val random = SecureRandom()

    @Volatile
    private var isExpectedProcess = false

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        val expectedProcessName =
            providerContext.packageName +
            ClipboardExternalMediaImportWorkerContract.PROCESS_SUFFIX
        isExpectedProcess =
            currentFlorisApplicationProcessName(providerContext) == expectedProcessName
        return isExpectedProcess
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        enforcePrivateRemoteCaller()
        return try {
            when (method) {
                ClipboardExternalMediaImportWorkerContract.METHOD_BEGIN ->
                    begin(arg, extras)
                ClipboardExternalMediaImportWorkerContract.METHOD_STAGE ->
                    stage(arg, extras)
                ClipboardExternalMediaImportWorkerContract.METHOD_POLL ->
                    poll(arg, extras)
                ClipboardExternalMediaImportWorkerContract.METHOD_CANCEL ->
                    cancel(arg, extras)
                else -> rejected()
            }
        } catch (_: RuntimeException) {
            rejected()
        }
    }

    private fun begin(
        token: String?,
        extras: Bundle?,
    ): Bundle {
        val deadline = parseBegin(token, extras) ?: return rejected()
        val now = SystemClock.elapsedRealtime()
        val remainingMs = deadline - now
        if (remainingMs !in 1..ClipboardExternalMediaImportWorkerContract.MAX_TIMEOUT_MS) {
            return rejected()
        }
        val request = ActiveRequest(
            token = requireNotNull(token),
            generation = freshGeneration(),
            deadlineElapsedRealtimeMs = deadline,
        )
        if (!activeRequest.compareAndSet(null, request)) {
            return response(ClipboardExternalMediaImportWorkerContract.STATUS_BUSY)
        }
        return try {
            request.watchdog = hardKillExecutor.schedule(
                {
                    if (activeRequest.get() === request) {
                        Process.killProcess(Process.myPid())
                    }
                },
                remainingMs + ClipboardExternalMediaImportWorkerContract.WATCHDOG_GRACE_MS,
                TimeUnit.MILLISECONDS,
            )
            response(
                status = ClipboardExternalMediaImportWorkerContract.STATUS_ACCEPTED,
                generation = request.generation,
            )
        } catch (_: RuntimeException) {
            activeRequest.compareAndSet(request, null)
            rejected()
        }
    }

    private fun stage(
        token: String?,
        extras: Bundle?,
    ): Bundle {
        val input = parseStage(token, extras) ?: run {
            extras?.parcelFileDescriptor(
                ClipboardExternalMediaImportWorkerContract.KEY_DESTINATION,
            )?.closeQuietly()
            return rejected()
        }
        val request = activeRequest.get()
            ?.takeIf { it.token == token && it.generation == input.generation }
            ?: run {
                input.destination.closeQuietly()
                return rejected()
            }
        if (!request.phase.compareAndSet(RequestPhase.RESERVED, RequestPhase.STAGING)) {
            input.destination.closeQuietly()
            return rejected()
        }
        val ownedDestination = try {
            ParcelFileDescriptor.dup(input.destination.fileDescriptor)
        } catch (_: Exception) {
            input.destination.closeQuietly()
            finishRejectedStart(request)
            return rejected()
        }
        input.destination.closeQuietly()
        val ownedInput = input.copy(destination = ownedDestination)
        return try {
            requireRegularEmptyFile(ownedDestination)
            stageExecutor.execute {
                completeStage(request, ownedInput)
            }
            response(
                status = ClipboardExternalMediaImportWorkerContract.STATUS_ACCEPTED,
                generation = request.generation,
            )
        } catch (_: RejectedExecutionException) {
            ownedDestination.closeQuietly()
            finishRejectedStart(request)
            rejected()
        } catch (_: Exception) {
            ownedDestination.closeQuietly()
            finishRejectedStart(request)
            rejected()
        }
    }

    private fun completeStage(
        request: ActiveRequest,
        input: StageInput,
    ) {
        val completion = try {
            request.cancellationSignal.throwIfCanceled()
            StageCompletion.Success(copyForeignMedia(request, input))
        } catch (_: Exception) {
            StageCompletion.Failure
        } finally {
            input.destination.closeQuietly()
        }
        request.completion.set(completion)
        request.phase.compareAndSet(RequestPhase.STAGING, RequestPhase.COMPLETED)
    }

    private fun poll(
        token: String?,
        extras: Bundle?,
    ): Bundle {
        val generation = parseGenerationRequest(token, extras) ?: return rejected()
        val request = activeRequest.get()
            ?.takeIf { it.token == token && it.generation == generation }
            ?: return rejected()
        return when (request.phase.get()) {
            RequestPhase.STAGING -> response(
                status = ClipboardExternalMediaImportWorkerContract.STATUS_BUSY,
                generation = request.generation,
            )
            RequestPhase.COMPLETED -> consumeCompletion(request)
            RequestPhase.RESERVED,
            RequestPhase.CANCELLING,
            RequestPhase.FINISHED,
            -> rejected()
        }
    }

    private fun consumeCompletion(request: ActiveRequest): Bundle {
        if (!request.phase.compareAndSet(RequestPhase.COMPLETED, RequestPhase.FINISHED)) {
            return rejected()
        }
        request.watchdog?.cancel(false)
        activeRequest.compareAndSet(request, null)
        return when (val completion = request.completion.getAndSet(null)) {
            is StageCompletion.Success -> response(
                status = ClipboardExternalMediaImportWorkerContract.STATUS_SUCCESS,
                byteCount = completion.result.byteCount,
                displayName = completion.result.displayName,
                sourceMimeType = completion.result.sourceMimeType,
            )
            StageCompletion.Failure,
            null,
            -> rejected()
        }
    }

    private fun finishRejectedStart(request: ActiveRequest) {
        request.phase.set(RequestPhase.FINISHED)
        request.watchdog?.cancel(false)
        activeRequest.compareAndSet(request, null)
    }

    private fun parseGenerationRequest(
        token: String?,
        extras: Bundle?,
    ): String? = parseCancel(token, extras)

    private fun acquireSourceClient(
        resolver: ContentResolver,
        source: Uri,
    ): ContentProviderClient {
        return resolver.acquireUnstableContentProviderClient(source)
            ?: throw IOException("Clipboard media is unavailable.")
    }

    private fun sourceMimeType(
        client: ContentProviderClient,
        request: ActiveRequest,
        source: Uri,
    ): String? {
        return try {
            client.getType(source)
        } catch (_: Exception) {
            ensureActive(request)
            null
        }?.trim()?.lowercase()?.takeIf(::isValidMimeType)
    }

    private fun copyForeignMedia(
        request: ActiveRequest,
        input: StageInput,
    ): StageResult {
        ensureActive(request)
        requireRegularEmptyFile(input.destination)
        val providerContext = context
            ?: throw IOException("Clipboard import worker is unavailable.")
        val client = acquireSourceClient(providerContext.contentResolver, input.source)
        return try {
            val sourceMimeType = sourceMimeType(client, request, input.source)
            val displayName = queryDisplayName(client, request, input.source)
            ensureActive(request)
            val descriptor = client.openAssetFile(
                input.source,
                "r",
                request.cancellationSignal,
            ) ?: throw IOException("Clipboard media is unavailable.")
            val byteCount = descriptor.use { source ->
                source.createInputStream().use { sourceStream ->
                    ParcelFileDescriptor.AutoCloseOutputStream(input.destination).channel.use {
                            output ->
                        output.truncate(0L)
                        output.position(0L)
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            ensureActive(request)
                            val read = sourceStream.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            if (read.toLong() > input.maximumBytes - total) {
                                throw IOException("Clipboard media is too large.")
                            }
                            writeFully(output, ByteBuffer.wrap(buffer, 0, read))
                            total += read
                        }
                        ensureActive(request)
                        output.force(true)
                        total
                    }
                }
            }
            if (byteCount <= 0L) throw IOException("Clipboard media is empty.")
            StageResult(
                byteCount = byteCount,
                displayName = displayName,
                sourceMimeType = sourceMimeType,
            )
        } finally {
            client.close()
        }
    }

    private fun cancel(
        token: String?,
        extras: Bundle?,
    ): Bundle {
        val generation = parseCancel(token, extras) ?: return rejected()
        val request = activeRequest.get()
            ?.takeIf { it.token == token && it.generation == generation }
            ?: return rejected()
        while (true) {
            val phase = request.phase.get()
            if (phase == RequestPhase.CANCELLING || phase == RequestPhase.FINISHED) {
                return rejected()
            }
            if (request.phase.compareAndSet(phase, RequestPhase.CANCELLING)) break
        }
        if (request.killScheduled.compareAndSet(false, true)) {
            try {
                cancellationExecutor.execute {
                    try {
                        request.cancellationSignal.cancel()
                    } catch (_: Exception) {
                        // The independent hard kill remains mandatory.
                    }
                }
            } catch (_: RuntimeException) {
                // The independent hard kill remains mandatory.
            }
            try {
                hardKillExecutor.schedule(
                    { Process.killProcess(Process.myPid()) },
                    ClipboardExternalMediaImportWorkerContract.CANCEL_GRACE_MS,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RuntimeException) {
                Process.killProcess(Process.myPid())
            }
        }
        return response(
            status = ClipboardExternalMediaImportWorkerContract.STATUS_ACCEPTED,
            generation = request.generation,
        )
    }

    private fun queryDisplayName(
        client: ContentProviderClient,
        request: ActiveRequest,
        source: Uri,
    ): String? {
        val cursor = try {
            client.query(
                source,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
                request.cancellationSignal,
            )
        } catch (_: Exception) {
            ensureActive(request)
            return null
        } ?: return null
        return cursor.use {
            try {
                ensureActive(request)
                if (!it.moveToFirst()) return null
                val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column < 0 || it.isNull(column)) return null
                normalizeClipboardMediaDisplayName(it.getString(column))
            } catch (_: Exception) {
                ensureActive(request)
                null
            }
        }
    }

    private fun parseBegin(
        token: String?,
        extras: Bundle?,
    ): Long? {
        val input = extras ?: return null
        if (!validToken(token) || !input.hasExactKeys(BEGIN_KEYS)) return null
        if (input.getInt(
                ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
                Int.MIN_VALUE,
            ) != ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION
        ) {
            return null
        }
        return input.getLong(
            ClipboardExternalMediaImportWorkerContract.KEY_DEADLINE_ELAPSED_REALTIME_MS,
            Long.MIN_VALUE,
        )
    }

    private fun parseStage(
        token: String?,
        extras: Bundle?,
    ): StageInput? {
        val input = extras ?: return null
        if (!validToken(token) || !input.hasExactKeys(STAGE_KEYS)) return null
        if (input.getInt(
                ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
                Int.MIN_VALUE,
            ) != ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION
        ) {
            return null
        }
        val generation = input.getString(
            ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
        )?.takeIf(::validToken) ?: return null
        val rawSource = input.getString(
            ClipboardExternalMediaImportWorkerContract.KEY_SOURCE_URI,
        )?.takeIf { it.length <= ClipboardExternalMediaImportWorkerContract.MAX_SOURCE_URI_LENGTH }
            ?: return null
        val source = try {
            rawSource.toUri()
        } catch (_: RuntimeException) {
            return null
        }
        if (source.scheme != ContentResolver.SCHEME_CONTENT ||
            source.authority.isNullOrEmpty() ||
            source.host != source.authority ||
            source.userInfo != null ||
            source.authority == ClipboardExternalMediaImportWorkerContract.AUTHORITY ||
            source.authority == ClipboardMediaProvider.AUTHORITY ||
            source.authority == OreoSystemClipboardMediaProvider.AUTHORITY
        ) {
            return null
        }
        val maximumBytes = input.getLong(
            ClipboardExternalMediaImportWorkerContract.KEY_MAXIMUM_BYTES,
            Long.MIN_VALUE,
        )
        if (maximumBytes !in 1..ClipboardFileStorage.MAX_MEDIA_BYTES) return null
        val destination = input.parcelFileDescriptor(
            ClipboardExternalMediaImportWorkerContract.KEY_DESTINATION,
        ) ?: return null
        return StageInput(generation, source, destination, maximumBytes)
    }

    private fun parseCancel(
        token: String?,
        extras: Bundle?,
    ): String? {
        val input = extras ?: return null
        if (!validToken(token) || !input.hasExactKeys(CANCEL_KEYS)) return null
        if (input.getInt(
                ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
                Int.MIN_VALUE,
            ) != ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION
        ) {
            return null
        }
        return input.getString(
            ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
        )?.takeIf(::validToken)
    }

    private fun ensureActive(request: ActiveRequest) {
        if (activeRequest.get() !== request ||
            request.phase.get() != RequestPhase.STAGING ||
            request.cancellationSignal.isCanceled ||
            SystemClock.elapsedRealtime() >= request.deadlineElapsedRealtimeMs ||
            Thread.currentThread().isInterrupted
        ) {
            throw IOException("Clipboard import was abandoned.")
        }
    }

    private fun requireRegularEmptyFile(destination: ParcelFileDescriptor) {
        val stat = Os.fstat(destination.fileDescriptor)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 0L) {
            throw IOException("Clipboard import destination is unavailable.")
        }
    }

    private fun writeFully(
        channel: FileChannel,
        buffer: ByteBuffer,
    ) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) {
                throw IOException("Clipboard media staging failed.")
            }
        }
    }

    private fun enforcePrivateRemoteCaller() {
        if (!isExpectedProcess ||
            Binder.getCallingUid() != Process.myUid() ||
            Binder.getCallingPid() == Process.myPid()
        ) {
            throw SecurityException("Clipboard import worker access denied.")
        }
    }

    private fun freshGeneration(): String = UUID(random.nextLong(), random.nextLong()).let { uuid ->
        val value = uuid.toString().toCharArray()
        value[14] = '4'
        value[19] = "89ab"[random.nextInt(4)]
        String(value)
    }

    private fun response(
        status: Int,
        generation: String? = null,
        byteCount: Long? = null,
        displayName: String? = null,
        sourceMimeType: String? = null,
    ): Bundle = Bundle().apply {
        putInt(
            ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
            ClipboardExternalMediaImportWorkerContract.PROTOCOL_VERSION,
        )
        putInt(ClipboardExternalMediaImportWorkerContract.KEY_STATUS, status)
        generation?.let {
            putString(ClipboardExternalMediaImportWorkerContract.KEY_GENERATION, it)
        }
        byteCount?.let {
            putLong(ClipboardExternalMediaImportWorkerContract.KEY_BYTE_COUNT, it)
        }
        displayName?.let {
            putString(ClipboardExternalMediaImportWorkerContract.KEY_DISPLAY_NAME, it)
        }
        sourceMimeType?.let {
            putString(ClipboardExternalMediaImportWorkerContract.KEY_SOURCE_MIME_TYPE, it)
        }
    }

    private fun rejected(): Bundle =
        response(ClipboardExternalMediaImportWorkerContract.STATUS_REJECTED)

    private fun validToken(token: String?): Boolean =
        token != null && ClipboardExternalMediaImportWorkerContract.TOKEN.matches(token)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException("Clipboard import queries are unsupported.")

    override fun getType(uri: Uri): String? =
        throw UnsupportedOperationException("Clipboard import types are unsupported.")

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("Clipboard import inserts are unsupported.")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Clipboard import deletes are unsupported.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Clipboard import updates are unsupported.")

    private data class StageInput(
        val generation: String,
        val source: Uri,
        val destination: ParcelFileDescriptor,
        val maximumBytes: Long,
    )

    private data class StageResult(
        val byteCount: Long,
        val displayName: String?,
        val sourceMimeType: String?,
    )

    private class ActiveRequest(
        val token: String,
        val generation: String,
        val deadlineElapsedRealtimeMs: Long,
        val cancellationSignal: CancellationSignal = CancellationSignal(),
        val phase: AtomicReference<RequestPhase> =
            AtomicReference(RequestPhase.RESERVED),
        val completion: AtomicReference<StageCompletion?> = AtomicReference(),
        val killScheduled: AtomicBoolean = AtomicBoolean(false),
    ) {
        @Volatile
        var watchdog: ScheduledFuture<*>? = null
    }

    private enum class RequestPhase {
        RESERVED,
        STAGING,
        COMPLETED,
        CANCELLING,
        FINISHED,
    }

    private sealed class StageCompletion {
        data class Success(val result: StageResult) : StageCompletion()

        data object Failure : StageCompletion()
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private val MIME_TYPE =
            Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")
        private val BEGIN_KEYS = setOf(
            ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
            ClipboardExternalMediaImportWorkerContract.KEY_DEADLINE_ELAPSED_REALTIME_MS,
        )
        private val STAGE_KEYS = setOf(
            ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
            ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
            ClipboardExternalMediaImportWorkerContract.KEY_SOURCE_URI,
            ClipboardExternalMediaImportWorkerContract.KEY_DESTINATION,
            ClipboardExternalMediaImportWorkerContract.KEY_MAXIMUM_BYTES,
        )
        private val CANCEL_KEYS = setOf(
            ClipboardExternalMediaImportWorkerContract.KEY_PROTOCOL_VERSION,
            ClipboardExternalMediaImportWorkerContract.KEY_GENERATION,
        )

        private fun isValidMimeType(value: String): Boolean =
            value.length <= ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH &&
                '*' !in value &&
                MIME_TYPE.matches(value)
    }
}

private fun Bundle?.hasExactKeys(expected: Set<String>): Boolean {
    if (this == null) return false
    return try {
        keySet() == expected
    } catch (_: RuntimeException) {
        false
    }
}

private fun Bundle.parcelFileDescriptor(key: String): ParcelFileDescriptor? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, ParcelFileDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key)
        }
    } catch (_: RuntimeException) {
        null
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Process death is the final descriptor cleanup boundary.
    }
}
