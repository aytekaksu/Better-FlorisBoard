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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext

private object BackupArchiveSessionAuthority

internal sealed interface BackupArchiveSessionFailure {
    data object SnapshotUnavailable : BackupArchiveSessionFailure

    data object SnapshotSizeMismatch : BackupArchiveSessionFailure

    data class ZipGateRejected(val reason: BackupArchiveZipGateFailure) : BackupArchiveSessionFailure

    data object ZipReaderFailure : BackupArchiveSessionFailure

    data class ArchiveRejected(val reason: ArchiveFailure) : BackupArchiveSessionFailure
}

internal sealed interface BackupArchiveSessionResult {
    data class Valid(val session: BackupArchiveSession) : BackupArchiveSessionResult

    data class Invalid(val failure: BackupArchiveSessionFailure) : BackupArchiveSessionResult
}

/**
 * Keeps one validated archive bound to the exact ZIP entries that produced it.
 *
 * Entry operations and close are serialized because Commons Compress does not
 * promise that one [ZipFile] is safe for concurrent use.
 */
internal class BackupArchiveSession internal constructor(
    authority: Any,
    private val zipFile: ZipFile,
    private val boundEntries: IdentityHashMap<ValidatedArchiveEntry, ZipArchiveEntry>,
    val archive: ValidatedArchive,
) : Closeable {
    private val guard = ReentrantLock()
    private var closed = false

    init {
        check(authority === BackupArchiveSessionAuthority)
    }

    fun createPlan(request: RestoreRequest): RestorePlanResult = RestorePlanner.create(archive, request)

    /**
     * Returns whether every staged entry in [plan] belongs to this exact
     * snapshot. Equal paths from another session are deliberately insufficient.
     */
    internal fun owns(plan: RestorePlan): Boolean = guard.withLock {
        !closed &&
            plan.componentsToStage.all { component ->
                component.entries.all(boundEntries::containsKey)
            } &&
            plan.clipboardMediaCandidatesToStage.all(boundEntries::containsKey)
    }

    /**
     * Runs [block] while the session is locked. The block must finish all ZIP
     * reads before returning and must not let [ZipFile] or [ZipArchiveEntry]
     * escape. Null means that the session is closed or the entry is foreign.
     */
    internal fun <T : Any> withEntry(entry: ValidatedArchiveEntry, block: (ZipFile, ZipArchiveEntry) -> T): T? =
        guard.withLock {
            if (closed) return@withLock null
            val zipEntry = boundEntries[entry] ?: return@withLock null
            block(zipFile, zipEntry)
        }

    override fun close() {
        guard.withLock {
            if (closed) return
            closed = true
            boundEntries.clear()
            closeQuietly(zipFile)
        }
    }

    override fun toString(): String = guard.withLock {
        "BackupArchiveSession(componentCount=${archive.components.size}, closed=$closed)"
    }

    companion object {
        suspend fun open(
            snapshot: ArchiveSnapshot,
            limits: ArchiveLimits = ArchiveLimits.Default,
        ): BackupArchiveSessionResult {
            val pendingSession = AtomicReference<BackupArchiveSession?>()
            return try {
                withContext(Dispatchers.IO) {
                    BackupArchiveSessionOpener(snapshot, limits).open().also { result ->
                        if (result is BackupArchiveSessionResult.Valid) {
                            pendingSession.set(result.session)
                        }
                    }
                }.also {
                    pendingSession.set(null)
                }
            } finally {
                pendingSession.getAndSet(null)?.let { session ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        session.close()
                    }
                }
            }
        }
    }
}

private class BackupArchiveSessionOpener(private val snapshot: ArchiveSnapshot, private val limits: ArchiveLimits) {
    suspend fun open(): BackupArchiveSessionResult {
        coroutineContext.ensureActive()
        val channel = runOrNullPreservingCancellation {
            FileChannel.open(
                snapshot.path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            )
        } ?: return invalid(BackupArchiveSessionFailure.SnapshotUnavailable)
        return inspectChannel(channel)
    }

    private suspend fun inspectChannel(channel: FileChannel): BackupArchiveSessionResult {
        if (channelSize(channel) != snapshot.size) {
            closeQuietly(channel)
            return invalid(BackupArchiveSessionFailure.SnapshotSizeMismatch)
        }

        val gate = BackupArchiveZipGate.inspect(
            channel = channel,
            archiveSize = snapshot.size,
            maxEntries = limits.maxEntries,
            maxCentralDirectoryBytes = MAX_CENTRAL_DIRECTORY_BYTES,
            maxNameBytes = limits.maxPathBytes,
            maxExtraBytes = MAX_CENTRAL_EXTRA_BYTES,
            maxCommentBytes = MAX_CENTRAL_COMMENT_BYTES,
        )
        return when (gate) {
            is BackupArchiveZipGateResult.Invalid -> {
                closeQuietly(channel)
                invalid(BackupArchiveSessionFailure.ZipGateRejected(gate.failure))
            }

            is BackupArchiveZipGateResult.Valid -> inspectGatedChannel(channel, gate.layout)
        }
    }

    private suspend fun inspectGatedChannel(
        channel: FileChannel,
        layout: BackupArchiveZipLayout,
    ): BackupArchiveSessionResult {
        ensureActiveOrClose(channel)
        val zipFile = runOrNullPreservingCancellation(channel) {
            ZipFile.builder()
                .setSeekableByteChannel(channel)
                .setCharset(StandardCharsets.UTF_8)
                .setUseUnicodeExtraFields(false)
                .setIgnoreLocalFileHeader(true)
                .get()
        }
        if (zipFile == null) {
            closeQuietly(channel)
            return invalid(BackupArchiveSessionFailure.ZipReaderFailure)
        }
        return completeSessionOpen(channel, zipFile, layout)
    }

    private suspend fun completeSessionOpen(
        channel: FileChannel,
        zipFile: ZipFile,
        layout: BackupArchiveZipLayout,
    ): BackupArchiveSessionResult = when (val inspected = inspectSafely(channel, zipFile, layout)) {
        is SessionInspection.Valid -> BackupArchiveSessionResult.Valid(
            BackupArchiveSession(
                authority = BackupArchiveSessionAuthority,
                zipFile = zipFile,
                boundEntries = inspected.boundEntries,
                archive = inspected.archive,
            ),
        )

        is SessionInspection.Invalid -> {
            closeQuietly(zipFile)
            invalid(inspected.failure)
        }
    }

    private suspend fun inspectSafely(
        channel: FileChannel,
        zipFile: ZipFile,
        layout: BackupArchiveZipLayout,
    ): SessionInspection = try {
        inspect(channel, zipFile, layout)
    } catch (error: CancellationException) {
        closeQuietly(zipFile)
        throw error
    } catch (_: Exception) {
        SessionInspection.Invalid(BackupArchiveSessionFailure.ZipReaderFailure)
    }

    private suspend fun inspect(
        channel: FileChannel,
        zipFile: ZipFile,
        layout: BackupArchiveZipLayout,
    ): SessionInspection {
        val bindings = enumerateEntries(zipFile)
        if (bindings.size.toLong() != layout.entryCount) {
            return SessionInspection.Invalid(BackupArchiveSessionFailure.ZipReaderFailure)
        }
        coroutineContext.ensureActive()
        val preflight = when (
            val result = BackupArchive.preflight(
                entries = bindings.asSequence().map { it.fact },
                archiveSize = snapshot.size,
                limits = limits,
            )
        ) {
            is ArchiveValidation.Invalid -> {
                return SessionInspection.Invalid(
                    BackupArchiveSessionFailure.ArchiveRejected(result.failure),
                )
            }

            is ArchiveValidation.Valid -> result.value
        }
        return inspectControlFiles(channel, zipFile, layout, preflight, bindings)
    }

    private suspend fun inspectControlFiles(
        channel: FileChannel,
        zipFile: ZipFile,
        layout: BackupArchiveZipLayout,
        preflight: ArchivePreflight,
        bindings: List<BoundZipEntry>,
    ): SessionInspection {
        val boundEntries = bindValidatedEntries(preflight, bindings)
            ?: return SessionInspection.Invalid(BackupArchiveSessionFailure.ZipReaderFailure)
        if (!validateRetainedLocalHeaders(channel, zipFile, layout.centralDirectoryOffset, preflight, boundEntries)) {
            return SessionInspection.Invalid(
                BackupArchiveSessionFailure.ArchiveRejected(ArchiveFailure.INVALID_ENTRY),
            )
        }
        val descriptor = ArchiveDescriptor(
            metadata = decodeControlFile<BackupArchive.Metadata>(
                zipFile = zipFile,
                validatedEntry = preflight.metadataEntry,
                zipEntry = boundEntries[preflight.metadataEntry]!!,
                maxBytes = limits.maxMetadataBytes,
            ),
            manifest = preflight.manifestEntry?.let { validatedEntry ->
                decodeControlFile<BackupArchive.Manifest>(
                    zipFile = zipFile,
                    validatedEntry = validatedEntry,
                    zipEntry = boundEntries[validatedEntry]!!,
                    maxBytes = limits.maxManifestBytes,
                )
            } ?: DecodedArchiveFile.Absent,
        )
        coroutineContext.ensureActive()
        return when (val result = BackupArchive.inspect(preflight, descriptor)) {
            is ArchiveValidation.Invalid -> SessionInspection.Invalid(
                BackupArchiveSessionFailure.ArchiveRejected(result.failure),
            )

            is ArchiveValidation.Valid -> SessionInspection.Valid(
                archive = result.value,
                boundEntries = boundEntries,
            )
        }
    }

    /**
     * Commons can resolve data offsets without checking the local-header
     * signature. Validate the retained entries explicitly, then ask Commons to
     * pin the same checked offset before the session becomes observable.
     */
    private suspend fun validateRetainedLocalHeaders(
        channel: FileChannel,
        zipFile: ZipFile,
        centralDirectoryOffset: Long,
        preflight: ArchivePreflight,
        boundEntries: IdentityHashMap<ValidatedArchiveEntry, ZipArchiveEntry>,
    ): Boolean = try {
        for (validatedEntry in preflight.retainedEntries()) {
            coroutineContext.ensureActive()
            val zipEntry = boundEntries[validatedEntry] ?: return false
            val expectedDataOffset = validateLocalHeader(
                channel = channel,
                zipEntry = zipEntry,
                centralDirectoryOffset = centralDirectoryOffset,
            ) ?: return false
            val rawInput = zipFile.getRawInputStream(zipEntry) ?: return false
            rawInput.use {
                if (zipEntry.dataOffset != expectedDataOffset) return false
            }
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun validateLocalHeader(
        channel: FileChannel,
        zipEntry: ZipArchiveEntry,
        centralDirectoryOffset: Long,
    ): Long? {
        val header = channel.readExact(
            offset = zipEntry.localHeaderOffset,
            byteCount = LOCAL_HEADER_BYTES,
            upperBound = centralDirectoryOffset,
        ) ?: return null
        if (header.u32(LOCAL_SIGNATURE_OFFSET) != LOCAL_HEADER_SIGNATURE) return null
        if (!header.matches(zipEntry)) return null
        return resolveLocalDataOffset(channel, header, zipEntry, centralDirectoryOffset)
    }

    private fun ByteArray.matches(zipEntry: ZipArchiveEntry): Boolean {
        val centralFlags = zipEntry.rawFlag
        val centralMethod = zipEntry.method
        val allowedFlags = when (centralMethod) {
            ZipMethod.STORED.code -> STORED_ALLOWED_FLAGS
            ZipMethod.DEFLATED.code -> DEFLATED_ALLOWED_FLAGS
            else -> return false
        }
        return u16(LOCAL_METHOD_OFFSET) == centralMethod &&
            u16(LOCAL_FLAGS_OFFSET) == centralFlags &&
            centralFlags and allowedFlags.inv() == 0
    }

    private fun resolveLocalDataOffset(
        channel: FileChannel,
        header: ByteArray,
        zipEntry: ZipArchiveEntry,
        centralDirectoryOffset: Long,
    ): Long? {
        val centralName = zipEntry.rawName
        val localNameBytes = header.u16(LOCAL_NAME_LENGTH_OFFSET)
        val localExtraBytes = header.u16(LOCAL_EXTRA_LENGTH_OFFSET)
        if (centralName == null || localNameBytes != centralName.size) return null
        val layout = checkedLocalDataLayout(
            localHeaderOffset = zipEntry.localHeaderOffset,
            localNameBytes = localNameBytes,
            localExtraBytes = localExtraBytes,
            compressedSize = zipEntry.compressedSize,
            centralDirectoryOffset = centralDirectoryOffset,
        ) ?: return null
        val localName = channel.readExact(
            offset = layout.nameOffset,
            byteCount = localNameBytes,
            upperBound = layout.dataOffset,
        ) ?: return null
        return layout.dataOffset.takeIf { localName.contentEquals(centralName) }
    }

    private fun checkedLocalDataLayout(
        localHeaderOffset: Long,
        localNameBytes: Int,
        localExtraBytes: Int,
        compressedSize: Long,
        centralDirectoryOffset: Long,
    ): LocalDataLayout? {
        val nameOffset = localHeaderOffset.checkedAdd(LOCAL_HEADER_BYTES.toLong()) ?: return null
        val dataOffset = nameOffset.checkedAdd(localNameBytes.toLong())
            ?.checkedAdd(localExtraBytes.toLong())
            ?: return null
        val dataEnd = dataOffset.checkedAdd(compressedSize) ?: return null
        return LocalDataLayout(nameOffset, dataOffset).takeIf { dataEnd <= centralDirectoryOffset }
    }

    private suspend fun enumerateEntries(zipFile: ZipFile): List<BoundZipEntry> = buildList {
        val entries = zipFile.entries
        while (entries.hasMoreElements()) {
            coroutineContext.ensureActive()
            val entry = entries.nextElement()
            check(entry.diskNumberStart == 0L)
            add(BoundZipEntry(entry, entry.toFact(zipFile)))
        }
    }

    private suspend inline fun <reified T> decodeControlFile(
        zipFile: ZipFile,
        validatedEntry: ValidatedArchiveEntry,
        zipEntry: ZipArchiveEntry,
        maxBytes: Long,
    ): DecodedArchiveFile<T> {
        val bytes = readExactControlFile(zipFile, validatedEntry, zipEntry, maxBytes)
            ?: return DecodedArchiveFile.Invalid
        val text = runOrNullPreservingCancellation {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } ?: return DecodedArchiveFile.Invalid
        return runOrNullPreservingCancellation {
            DecodedArchiveFile.Parsed(BackupArchive.controlFileJson.decodeFromString<T>(text))
        } ?: DecodedArchiveFile.Invalid
    }

    private suspend fun readExactControlFile(
        zipFile: ZipFile,
        validatedEntry: ValidatedArchiveEntry,
        zipEntry: ZipArchiveEntry,
        maxBytes: Long,
    ): ByteArray? {
        if (!validatedEntry.isWithinControlLimits(maxBytes)) return null
        return try {
            val initialCapacity = minOf(validatedEntry.uncompressedSize, CONTROL_READ_BUFFER_BYTES.toLong()).toInt()
            val output = ByteArrayOutputStream(initialCapacity)
            val checksum = CRC32()
            var actualBytes = 0L
            zipFile.getInputStream(zipEntry).use { input ->
                val buffer = ByteArray(CONTROL_READ_BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val readCount = input.read(buffer)
                    if (readCount < 0) break
                    if (!canAppendControlBytes(readCount, actualBytes, validatedEntry.uncompressedSize, maxBytes)) {
                        return null
                    }
                    output.write(buffer, 0, readCount)
                    checksum.update(buffer, 0, readCount)
                    actualBytes += readCount
                }
            }
            output.toByteArray().takeIf {
                actualBytes == validatedEntry.uncompressedSize &&
                    checksum.value == validatedEntry.crc32
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun ValidatedArchiveEntry.isWithinControlLimits(maxBytes: Long): Boolean {
        if (maxBytes < 0L || uncompressedSize > maxBytes) return false
        return compressedSize <= maxBytes ||
            compressedSize - maxBytes <= MAX_CONTROL_COMPRESSED_OVERHEAD_BYTES
    }

    private fun canAppendControlBytes(
        readCount: Int,
        actualBytes: Long,
        expectedBytes: Long,
        maxBytes: Long,
    ): Boolean = readCount > 0 &&
        readCount.toLong() <= maxBytes - actualBytes &&
        readCount.toLong() <= expectedBytes - actualBytes

    private fun bindValidatedEntries(
        preflight: ArchivePreflight,
        bindings: List<BoundZipEntry>,
    ): IdentityHashMap<ValidatedArchiveEntry, ZipArchiveEntry>? {
        val byKey = HashMap<ZipEntryKey, ZipArchiveEntry>(bindings.size)
        bindings.forEach { binding ->
            if (byKey.put(binding.key, binding.entry) != null) return null
        }
        return IdentityHashMap<ValidatedArchiveEntry, ZipArchiveEntry>().also { result ->
            for (validatedEntry in preflight.retainedEntries()) {
                val key = ZipEntryKey(validatedEntry.archivePath, validatedEntry.kind)
                val zipEntry = byKey[key] ?: return null
                result[validatedEntry] = zipEntry
            }
        }
    }

    private fun channelSize(channel: FileChannel): Long? = try {
        channel.size()
    } catch (_: Exception) {
        null
    }

    private fun invalid(failure: BackupArchiveSessionFailure): BackupArchiveSessionResult.Invalid =
        BackupArchiveSessionResult.Invalid(failure)

    companion object {
        private const val MAX_CENTRAL_DIRECTORY_BYTES = 16L shl 20
        private const val MAX_CENTRAL_EXTRA_BYTES = 4 shl 10
        private const val MAX_CENTRAL_COMMENT_BYTES = 1 shl 10
        private const val MAX_CONTROL_COMPRESSED_OVERHEAD_BYTES = 1L shl 10
        private const val CONTROL_READ_BUFFER_BYTES = 8 * 1024

        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
        private const val LOCAL_HEADER_BYTES = 30
        private const val LOCAL_SIGNATURE_OFFSET = 0
        private const val LOCAL_FLAGS_OFFSET = 6
        private const val LOCAL_METHOD_OFFSET = 8
        private const val LOCAL_NAME_LENGTH_OFFSET = 26
        private const val LOCAL_EXTRA_LENGTH_OFFSET = 28

        private const val DATA_DESCRIPTOR_FLAG = 1 shl 3
        private const val UTF8_NAMES_FLAG = 1 shl 11
        private const val DEFLATE_OPTION_FLAGS = (1 shl 1) or (1 shl 2)
        private const val STORED_ALLOWED_FLAGS = DATA_DESCRIPTOR_FLAG or UTF8_NAMES_FLAG
        private const val DEFLATED_ALLOWED_FLAGS = STORED_ALLOWED_FLAGS or DEFLATE_OPTION_FLAGS
    }
}

private sealed interface SessionInspection {
    data class Valid(
        val archive: ValidatedArchive,
        val boundEntries: IdentityHashMap<ValidatedArchiveEntry, ZipArchiveEntry>,
    ) : SessionInspection

    data class Invalid(val failure: BackupArchiveSessionFailure) : SessionInspection
}

private data class BoundZipEntry(val entry: ZipArchiveEntry, val fact: ArchiveEntryFact) {
    val key = ZipEntryKey(
        path = if (fact.kind == ArchiveEntryKind.DIRECTORY) fact.path.dropLast(1) else fact.path,
        kind = fact.kind,
    )
}

private data class ZipEntryKey(val path: String, val kind: ArchiveEntryKind)

private data class LocalDataLayout(val nameOffset: Long, val dataOffset: Long)

private fun ZipArchiveEntry.toFact(zipFile: ZipFile): ArchiveEntryFact {
    val isEncrypted = generalPurposeBit.usesEncryption() || method == ZipMethod.AES_ENCRYPTED.code
    val declaredCompression = when (method) {
        ZipMethod.STORED.code -> ArchiveCompression.STORED
        ZipMethod.DEFLATED.code -> ArchiveCompression.DEFLATED
        else -> ArchiveCompression.UNSUPPORTED
    }
    return ArchiveEntryFact(
        path = name,
        kind = archiveEntryKind(),
        compressedSize = compressedSize,
        uncompressedSize = size,
        crc32 = crc,
        compression = declaredCompression.takeIf { zipFile.canReadEntryData(this) }
            ?: ArchiveCompression.UNSUPPORTED,
        encrypted = isEncrypted,
    )
}

private fun ZipArchiveEntry.archiveEntryKind(): ArchiveEntryKind {
    if (platform != ZipArchiveEntry.PLATFORM_UNIX) {
        return if (isDirectory) ArchiveEntryKind.DIRECTORY else ArchiveEntryKind.FILE
    }
    return when (unixMode and UnixStat.FILE_TYPE_FLAG) {
        UnixStat.FILE_FLAG -> ArchiveEntryKind.FILE
        UnixStat.DIR_FLAG -> ArchiveEntryKind.DIRECTORY
        UnixStat.LINK_FLAG -> ArchiveEntryKind.SYMBOLIC_LINK
        0 -> if (isDirectory) ArchiveEntryKind.DIRECTORY else ArchiveEntryKind.FILE
        else -> ArchiveEntryKind.SPECIAL
    }
}

private fun ArchivePreflight.retainedEntries(): Sequence<ValidatedArchiveEntry> = sequence {
    yield(metadataEntry)
    manifestEntry?.let { yield(it) }
    components.forEach { component -> yieldAll(component.entries) }
    yieldAll(clipboardMediaEntries)
}

private fun closeQuietly(closeable: Closeable) {
    try {
        closeable.close()
    } catch (_: Exception) {
        // The session reports the original typed failure.
    }
}

private fun FileChannel.readExact(offset: Long, byteCount: Int, upperBound: Long): ByteArray? {
    val end = offset.checkedAdd(byteCount.toLong())
    if (byteCount < 0 || upperBound < 0L || end == null || end > upperBound) return null
    val bytes = ByteArray(byteCount)
    val buffer = ByteBuffer.wrap(bytes)
    var cursor = offset
    while (buffer.hasRemaining()) {
        val readCount = read(buffer, cursor)
        if (readCount <= 0) return null
        cursor += readCount
    }
    return bytes
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and BYTE_MASK) or
    ((this[offset + 1].toInt() and BYTE_MASK) shl Byte.SIZE_BITS)

private fun ByteArray.u32(offset: Int): Long =
    u16(offset).toLong() or (u16(offset + Short.SIZE_BYTES).toLong() shl Short.SIZE_BITS)

private fun Long.checkedAdd(other: Long): Long? =
    takeIf { this >= 0L && other >= 0L && this <= Long.MAX_VALUE - other }?.plus(other)

private const val BYTE_MASK = 0xff

private inline fun <T> runOrNullPreservingCancellation(closeOnCancellation: Closeable? = null, block: () -> T): T? =
    try {
        block()
    } catch (error: CancellationException) {
        closeOnCancellation?.let(::closeQuietly)
        throw error
    } catch (_: Exception) {
        null
    }

private suspend fun ensureActiveOrClose(closeable: Closeable) {
    try {
        coroutineContext.ensureActive()
    } catch (error: CancellationException) {
        closeQuietly(closeable)
        throw error
    }
}
