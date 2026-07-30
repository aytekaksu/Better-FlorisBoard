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

@file:Suppress("MagicNumber")

package dev.patrickgold.florisboard.app.settings.advanced

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException

/**
 * Bounds central-directory work before a full ZIP reader is allowed to parse
 * the same open channel.
 */
internal object BackupArchiveZipGate {
    fun inspect(
        channel: FileChannel,
        archiveSize: Long,
        maxEntries: Int,
        maxCentralDirectoryBytes: Long,
        maxNameBytes: Int,
        maxExtraBytes: Int,
        maxCommentBytes: Int,
    ): BackupArchiveZipGateResult {
        if (archiveSize < 0L) {
            return BackupArchiveZipGateResult.Invalid(BackupArchiveZipGateFailure.INVALID_ARCHIVE_SIZE)
        }
        if (hasInvalidLimits(maxEntries, maxCentralDirectoryBytes, maxNameBytes, maxExtraBytes, maxCommentBytes)) {
            return BackupArchiveZipGateResult.Invalid(BackupArchiveZipGateFailure.INVALID_LIMITS)
        }
        return try {
            if (channel.size() != archiveSize) {
                BackupArchiveZipGateResult.Invalid(BackupArchiveZipGateFailure.ARCHIVE_SIZE_MISMATCH)
            } else {
                BackupArchiveZipGateInspector(
                    channel = channel,
                    archiveSize = archiveSize,
                    maxEntries = maxEntries,
                    maxCentralDirectoryBytes = maxCentralDirectoryBytes,
                    maxNameBytes = maxNameBytes,
                    maxExtraBytes = maxExtraBytes,
                    maxCommentBytes = maxCommentBytes,
                ).inspect()
            }
        } catch (_: IOException) {
            BackupArchiveZipGateResult.Invalid(BackupArchiveZipGateFailure.IO_FAILURE)
        } catch (_: NonReadableChannelException) {
            BackupArchiveZipGateResult.Invalid(BackupArchiveZipGateFailure.IO_FAILURE)
        } catch (_: SecurityException) {
            BackupArchiveZipGateResult.Invalid(BackupArchiveZipGateFailure.IO_FAILURE)
        }
    }

    private fun hasInvalidLimits(
        maxEntries: Int,
        maxCentralDirectoryBytes: Long,
        maxNameBytes: Int,
        maxExtraBytes: Int,
        maxCommentBytes: Int,
    ): Boolean = maxEntries < 0 ||
        maxCentralDirectoryBytes < 0L ||
        intArrayOf(maxNameBytes, maxExtraBytes, maxCommentBytes).any { it < 0 }
}

internal enum class BackupArchiveZipGateFailure {
    INVALID_ARCHIVE_SIZE,
    INVALID_LIMITS,
    ARCHIVE_SIZE_MISMATCH,
    END_RECORD_NOT_FOUND,
    END_RECORD_TRAILING_MISMATCH,
    MULTI_DISK_ARCHIVE,
    INVALID_ZIP64,
    TOO_MANY_ENTRIES,
    CENTRAL_DIRECTORY_TOO_LARGE,
    INVALID_CENTRAL_DIRECTORY,
    IO_FAILURE,
}

internal sealed interface BackupArchiveZipGateResult {
    data class Valid(val layout: BackupArchiveZipLayout) : BackupArchiveZipGateResult

    data class Invalid(val failure: BackupArchiveZipGateFailure) : BackupArchiveZipGateResult
}

internal class BackupArchiveZipLayout(
    val entryCount: Long,
    val centralDirectoryOffset: Long,
    val centralDirectoryBytes: Long,
    val usesZip64: Boolean,
) {
    override fun toString(): String =
        "BackupArchiveZipLayout(entryCount=$entryCount, centralDirectoryOffset=$centralDirectoryOffset, " +
            "centralDirectoryBytes=$centralDirectoryBytes, usesZip64=$usesZip64)"
}

private class BackupArchiveZipGateInspector(
    private val channel: FileChannel,
    private val archiveSize: Long,
    private val maxEntries: Int,
    private val maxCentralDirectoryBytes: Long,
    private val maxNameBytes: Int,
    private val maxExtraBytes: Int,
    private val maxCommentBytes: Int,
) {
    fun inspect(): BackupArchiveZipGateResult {
        val endRecord = when (val search = findEndRecord()) {
            is EndRecordSearch.Found -> search.record

            EndRecordSearch.Missing -> {
                return invalid(BackupArchiveZipGateFailure.END_RECORD_NOT_FOUND)
            }

            EndRecordSearch.TrailingMismatch -> {
                return invalid(BackupArchiveZipGateFailure.END_RECORD_TRAILING_MISMATCH)
            }
        }
        val hasLocator = hasZip64Locator(endRecord.offset)
        return if (endRecord.hasZip64Sentinel || hasLocator) {
            inspectZip64(endRecord, hasLocator)
        } else {
            inspectClassic(endRecord)
        }
    }

    private fun inspectClassic(endRecord: EndRecord): BackupArchiveZipGateResult {
        if (endRecord.diskNumber != SINGLE_DISK_NUMBER ||
            endRecord.centralDirectoryDisk != SINGLE_DISK_NUMBER ||
            endRecord.entriesOnDisk != endRecord.totalEntries
        ) {
            return invalid(BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE)
        }
        return validateDirectory(
            entryCount = endRecord.totalEntries.toULong(),
            centralDirectoryOffset = endRecord.centralDirectoryOffset.toULong(),
            centralDirectoryBytes = endRecord.centralDirectoryBytes.toULong(),
            centralDirectoryBoundary = endRecord.offset,
            usesZip64 = false,
        )
    }

    private fun inspectZip64(endRecord: EndRecord, hasLocator: Boolean): BackupArchiveZipGateResult =
        when (val locator = readZip64Locator(endRecord, hasLocator)) {
            is GateValue.Invalid -> invalid(locator.failure)

            is GateValue.Valid -> when (val record = readZip64EndRecord(locator.value)) {
                is GateValue.Invalid -> invalid(record.failure)

                is GateValue.Valid -> if (!endRecord.matches(record.value.record)) {
                    invalid(BackupArchiveZipGateFailure.INVALID_ZIP64)
                } else {
                    validateDirectory(
                        entryCount = record.value.record.totalEntries,
                        centralDirectoryOffset = record.value.record.centralDirectoryOffset,
                        centralDirectoryBytes = record.value.record.centralDirectoryBytes,
                        centralDirectoryBoundary = record.value.recordOffset,
                        usesZip64 = true,
                    )
                }
            }
        }

    private fun readZip64Locator(endRecord: EndRecord, hasLocator: Boolean): GateValue<LocatedZip64Locator> {
        if (!hasLocator) return GateValue.Invalid(BackupArchiveZipGateFailure.INVALID_ZIP64)
        val locatorOffset = endRecord.offset - ZIP64_LOCATOR_BYTES
        val locator = readExact(locatorOffset, ZIP64_LOCATOR_BYTES.toInt())?.let(Zip64Locator::parse)
        val recordOffset = locator?.recordOffset?.toLongOrNull()
        return when {
            locator == null || recordOffset == null -> {
                GateValue.Invalid(BackupArchiveZipGateFailure.INVALID_ZIP64)
            }

            locator.recordDisk != SINGLE_DISK_NUMBER || locator.totalDisks != SINGLE_DISK_COUNT -> {
                GateValue.Invalid(BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE)
            }

            else -> GateValue.Valid(LocatedZip64Locator(locatorOffset, recordOffset))
        }
    }

    private fun readZip64EndRecord(locator: LocatedZip64Locator): GateValue<LocatedZip64EndRecord> {
        val record = readExact(locator.recordOffset, ZIP64_END_RECORD_MIN_BYTES.toInt())
            ?.let(Zip64EndRecord::parse)
        val recordEnd = record?.recordBytes?.checkedAdd(locator.recordOffset)
        return when {
            record == null || recordEnd == null || recordEnd != locator.locatorOffset -> {
                GateValue.Invalid(BackupArchiveZipGateFailure.INVALID_ZIP64)
            }

            record.diskNumber != SINGLE_DISK_NUMBER ||
                record.centralDirectoryDisk != SINGLE_DISK_NUMBER ||
                record.entriesOnDisk != record.totalEntries -> {
                GateValue.Invalid(BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE)
            }

            else -> GateValue.Valid(LocatedZip64EndRecord(record, locator.recordOffset))
        }
    }

    private fun validateDirectory(
        entryCount: ULong,
        centralDirectoryOffset: ULong,
        centralDirectoryBytes: ULong,
        centralDirectoryBoundary: Long,
        usesZip64: Boolean,
    ): BackupArchiveZipGateResult = when (
        val bounded = boundDirectory(
            entryCount,
            centralDirectoryOffset,
            centralDirectoryBytes,
            centralDirectoryBoundary,
            usesZip64,
        )
    ) {
        is GateValue.Invalid -> invalid(bounded.failure)

        is GateValue.Valid -> centralDirectoryFailure(
            startOffset = bounded.value.layout.centralDirectoryOffset,
            endOffset = bounded.value.endOffset,
            expectedEntries = bounded.value.layout.entryCount,
        )?.let(::invalid) ?: BackupArchiveZipGateResult.Valid(bounded.value.layout)
    }

    private fun boundDirectory(
        entryCount: ULong,
        centralDirectoryOffset: ULong,
        centralDirectoryBytes: ULong,
        centralDirectoryBoundary: Long,
        usesZip64: Boolean,
    ): GateValue<BoundedCentralDirectory> {
        val budgetFailure = directoryBudgetFailure(entryCount, centralDirectoryBytes)
        if (budgetFailure != null) return GateValue.Invalid(budgetFailure)
        val offset = centralDirectoryOffset.toLongOrNull()
            ?: return GateValue.Invalid(BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY)
        val size = centralDirectoryBytes.toLongOrNull()
            ?: return GateValue.Invalid(BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY)
        val count = entryCount.toLong()
        val end = size.checkedAdd(offset)
        val boundsFailure = directoryBoundsFailure(end, centralDirectoryBoundary, count, size)
        return if (boundsFailure != null || end == null) {
            GateValue.Invalid(boundsFailure ?: BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY)
        } else {
            GateValue.Valid(
                BoundedCentralDirectory(
                    layout = BackupArchiveZipLayout(
                        entryCount = count,
                        centralDirectoryOffset = offset,
                        centralDirectoryBytes = size,
                        usesZip64 = usesZip64,
                    ),
                    endOffset = end,
                ),
            )
        }
    }

    private fun directoryBudgetFailure(entryCount: ULong, centralDirectoryBytes: ULong): BackupArchiveZipGateFailure? =
        when {
            entryCount > maxEntries.toULong() -> BackupArchiveZipGateFailure.TOO_MANY_ENTRIES

            centralDirectoryBytes > maxCentralDirectoryBytes.toULong() -> {
                BackupArchiveZipGateFailure.CENTRAL_DIRECTORY_TOO_LARGE
            }

            else -> null
        }

    private fun directoryBoundsFailure(
        end: Long?,
        centralDirectoryBoundary: Long,
        entryCount: Long,
        centralDirectoryBytes: Long,
    ): BackupArchiveZipGateFailure? = when {
        end == null || end != centralDirectoryBoundary || end > archiveSize -> {
            BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
        }

        entryCount > 0L && centralDirectoryBytes < entryCount * CENTRAL_DIRECTORY_HEADER_BYTES -> {
            BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
        }

        else -> null
    }

    private fun centralDirectoryFailure(
        startOffset: Long,
        endOffset: Long,
        expectedEntries: Long,
    ): BackupArchiveZipGateFailure? {
        var cursor = startOffset
        var actualEntries = 0L
        var failure: BackupArchiveZipGateFailure? = null
        while (cursor < endOffset && failure == null) {
            val inspection = inspectCentralEntry(
                headerOffset = cursor,
                endOffset = endOffset,
                entryNumber = actualEntries + 1L,
            )
            if (inspection.counted) {
                actualEntries++
            }
            failure = inspection.failure
            inspection.nextOffset?.let { cursor = it }
        }
        return failure ?: BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
            .takeIf { actualEntries != expectedEntries }
    }

    private fun inspectCentralEntry(headerOffset: Long, endOffset: Long, entryNumber: Long): CentralEntryInspection {
        val header = readExact(headerOffset, CENTRAL_DIRECTORY_HEADER_BYTES.toInt())
        if (header == null || header.u32(0) != CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
            return CentralEntryInspection.invalid(counted = false)
        }
        val nameBytes = header.u16(CENTRAL_NAME_LENGTH_OFFSET)
        val extraBytes = header.u16(CENTRAL_EXTRA_LENGTH_OFFSET)
        val commentBytes = header.u16(CENTRAL_COMMENT_LENGTH_OFFSET)
        val variableBytes = nameBytes + extraBytes + commentBytes
        val nextOffset = (CENTRAL_DIRECTORY_HEADER_BYTES + variableBytes).checkedAdd(headerOffset)
        val layoutFailure = centralEntryLayoutFailure(
            entryNumber = entryNumber,
            nameBytes = nameBytes,
            extraBytes = extraBytes,
            commentBytes = commentBytes,
            nextOffset = nextOffset,
            endOffset = endOffset,
        )
        val failure = layoutFailure ?: centralEntryDiskFailure(
            header = header,
            headerOffset = headerOffset,
            nameBytes = nameBytes,
            extraBytes = extraBytes,
        )
        return CentralEntryInspection(nextOffset, failure, counted = true)
    }

    private fun centralEntryLayoutFailure(
        entryNumber: Long,
        nameBytes: Long,
        extraBytes: Long,
        commentBytes: Long,
        nextOffset: Long?,
        endOffset: Long,
    ): BackupArchiveZipGateFailure? = when {
        entryNumber > maxEntries -> BackupArchiveZipGateFailure.TOO_MANY_ENTRIES

        nameBytes > maxNameBytes ||
            extraBytes > maxExtraBytes ||
            commentBytes > maxCommentBytes -> BackupArchiveZipGateFailure.CENTRAL_DIRECTORY_TOO_LARGE

        nextOffset == null || nextOffset > endOffset -> BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY

        else -> null
    }

    private fun centralEntryDiskFailure(
        header: ByteArray,
        headerOffset: Long,
        nameBytes: Long,
        extraBytes: Long,
    ): BackupArchiveZipGateFailure? {
        val diskNumber = header.u16(CENTRAL_DISK_NUMBER_OFFSET)
        return when {
            diskNumber == SINGLE_DISK_NUMBER -> null

            diskNumber != UINT16_MAX -> BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE

            else -> {
                val extraOffset = headerOffset.checkedAdd(CENTRAL_DIRECTORY_HEADER_BYTES)
                    ?.checkedAdd(nameBytes)
                val extra = extraOffset?.let { readExact(it, extraBytes.toInt()) }
                if (extra == null) {
                    BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
                } else {
                    resolveZip64DiskNumber(header, extra)
                }
            }
        }
    }

    private fun resolveZip64DiskNumber(header: ByteArray, extra: ByteArray): BackupArchiveZipGateFailure? {
        var cursor = 0
        var resolvedDisk: Long? = null
        var failure: BackupArchiveZipGateFailure? = null
        while (cursor < extra.size && failure == null) {
            val field = inspectZip64ExtraField(header, extra, cursor, resolvedDisk)
            cursor = field.nextOffset
            resolvedDisk = field.resolvedDisk
            failure = field.failure
        }
        return failure ?: when (resolvedDisk) {
            null -> BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
            SINGLE_DISK_NUMBER -> null
            else -> BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE
        }
    }

    private fun inspectZip64ExtraField(
        header: ByteArray,
        extra: ByteArray,
        cursor: Int,
        resolvedDisk: Long?,
    ): Zip64ExtraFieldInspection {
        if (extra.size - cursor < EXTRA_HEADER_BYTES) {
            return Zip64ExtraFieldInspection.invalid(cursor, resolvedDisk)
        }
        val id = extra.u16(cursor)
        val valueBytes = extra.u16(cursor + Short.SIZE_BYTES).toInt()
        val valueOffset = cursor + EXTRA_HEADER_BYTES
        val nextOffset = valueOffset + valueBytes
        if (nextOffset > extra.size) {
            return Zip64ExtraFieldInspection.invalid(cursor, resolvedDisk)
        }
        val diskOffset = zip64DiskOffset(header)
        return when {
            id != ZIP64_EXTRA_ID -> Zip64ExtraFieldInspection(nextOffset, resolvedDisk, null)

            resolvedDisk != null || diskOffset > valueBytes - Int.SIZE_BYTES -> {
                Zip64ExtraFieldInspection.invalid(nextOffset, resolvedDisk)
            }

            else -> Zip64ExtraFieldInspection(
                nextOffset = nextOffset,
                resolvedDisk = extra.u32(valueOffset + diskOffset),
                failure = null,
            )
        }
    }

    private fun zip64DiskOffset(header: ByteArray): Int {
        var offset = 0
        if (header.u32(CENTRAL_UNCOMPRESSED_SIZE_OFFSET) == UINT32_MAX) {
            offset += Long.SIZE_BYTES
        }
        if (header.u32(CENTRAL_COMPRESSED_SIZE_OFFSET) == UINT32_MAX) {
            offset += Long.SIZE_BYTES
        }
        if (header.u32(CENTRAL_LOCAL_HEADER_OFFSET) == UINT32_MAX) {
            offset += Long.SIZE_BYTES
        }
        return offset
    }

    private fun findEndRecord(): EndRecordSearch {
        if (archiveSize < END_RECORD_BYTES) return EndRecordSearch.Missing
        val tailBytes = minOf(archiveSize, MAX_END_SEARCH_BYTES).toInt()
        val tailOffset = archiveSize - tailBytes
        val tail = readExact(tailOffset, tailBytes) ?: return EndRecordSearch.Missing
        var sawSignature = false
        for (index in tail.size - END_RECORD_BYTES.toInt() downTo 0) {
            if (tail.u32(index) == END_RECORD_SIGNATURE) {
                sawSignature = true
                val commentBytes = tail.u16(index + END_COMMENT_LENGTH_OFFSET)
                if (index + END_RECORD_BYTES + commentBytes == tail.size.toLong()) {
                    return EndRecordSearch.Found(
                        EndRecord(
                            offset = tailOffset + index,
                            diskNumber = tail.u16(index + END_DISK_NUMBER_OFFSET),
                            centralDirectoryDisk = tail.u16(index + END_CENTRAL_DISK_OFFSET),
                            entriesOnDisk = tail.u16(index + END_ENTRIES_ON_DISK_OFFSET),
                            totalEntries = tail.u16(index + END_TOTAL_ENTRIES_OFFSET),
                            centralDirectoryBytes = tail.u32(index + END_CENTRAL_SIZE_OFFSET),
                            centralDirectoryOffset = tail.u32(index + END_CENTRAL_OFFSET_OFFSET),
                        ),
                    )
                }
            }
        }
        return if (sawSignature) EndRecordSearch.TrailingMismatch else EndRecordSearch.Missing
    }

    private fun hasZip64Locator(endRecordOffset: Long): Boolean {
        if (endRecordOffset < ZIP64_LOCATOR_BYTES) return false
        val signature = readExact(endRecordOffset - ZIP64_LOCATOR_BYTES, SIGNATURE_BYTES)
            ?: return false
        return signature.u32(0) == ZIP64_LOCATOR_SIGNATURE
    }

    private fun readExact(offset: Long, byteCount: Int): ByteArray? {
        if (offset < 0L || byteCount < 0) return null
        val end = byteCount.toLong().checkedAdd(offset) ?: return null
        if (end > archiveSize) return null
        val bytes = ByteArray(byteCount)
        val buffer = ByteBuffer.wrap(bytes)
        var cursor = offset
        var complete = true
        while (buffer.hasRemaining() && complete) {
            val readCount = channel.read(buffer, cursor)
            if (readCount <= 0) {
                complete = false
            } else {
                cursor += readCount
            }
        }
        return bytes.takeIf { complete }
    }

    private fun invalid(failure: BackupArchiveZipGateFailure): BackupArchiveZipGateResult.Invalid =
        BackupArchiveZipGateResult.Invalid(failure)
}

private data class EndRecord(
    val offset: Long,
    val diskNumber: Long,
    val centralDirectoryDisk: Long,
    val entriesOnDisk: Long,
    val totalEntries: Long,
    val centralDirectoryBytes: Long,
    val centralDirectoryOffset: Long,
) {
    val hasZip64Sentinel: Boolean
        get() = diskNumber == UINT16_MAX ||
            centralDirectoryDisk == UINT16_MAX ||
            entriesOnDisk == UINT16_MAX ||
            totalEntries == UINT16_MAX ||
            centralDirectoryBytes == UINT32_MAX ||
            centralDirectoryOffset == UINT32_MAX

    fun matches(zip64: Zip64EndRecord): Boolean = diskNumber.matchesZip64(UINT16_MAX, zip64.diskNumber.toULong()) &&
        centralDirectoryDisk.matchesZip64(UINT16_MAX, zip64.centralDirectoryDisk.toULong()) &&
        entriesOnDisk.matchesZip64(UINT16_MAX, zip64.entriesOnDisk) &&
        totalEntries.matchesZip64(UINT16_MAX, zip64.totalEntries) &&
        centralDirectoryBytes.matchesZip64(UINT32_MAX, zip64.centralDirectoryBytes) &&
        centralDirectoryOffset.matchesZip64(UINT32_MAX, zip64.centralDirectoryOffset)
}

private sealed interface GateValue<out T> {
    data class Valid<T>(val value: T) : GateValue<T>

    data class Invalid(val failure: BackupArchiveZipGateFailure) : GateValue<Nothing>
}

private data class LocatedZip64Locator(val locatorOffset: Long, val recordOffset: Long)

private data class LocatedZip64EndRecord(val record: Zip64EndRecord, val recordOffset: Long)

private data class BoundedCentralDirectory(val layout: BackupArchiveZipLayout, val endOffset: Long)

private data class CentralEntryInspection(
    val nextOffset: Long?,
    val failure: BackupArchiveZipGateFailure?,
    val counted: Boolean,
) {
    companion object {
        fun invalid(counted: Boolean): CentralEntryInspection = CentralEntryInspection(
            nextOffset = null,
            failure = BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY,
            counted = counted,
        )
    }
}

private data class Zip64ExtraFieldInspection(
    val nextOffset: Int,
    val resolvedDisk: Long?,
    val failure: BackupArchiveZipGateFailure?,
) {
    companion object {
        fun invalid(cursor: Int, resolvedDisk: Long?): Zip64ExtraFieldInspection = Zip64ExtraFieldInspection(
            nextOffset = cursor,
            resolvedDisk = resolvedDisk,
            failure = BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY,
        )
    }
}

private sealed interface EndRecordSearch {
    data class Found(val record: EndRecord) : EndRecordSearch

    data object Missing : EndRecordSearch

    data object TrailingMismatch : EndRecordSearch
}

private data class Zip64Locator(val recordDisk: Long, val recordOffset: ULong, val totalDisks: Long) {
    companion object {
        fun parse(bytes: ByteArray): Zip64Locator? {
            if (bytes.size != ZIP64_LOCATOR_BYTES.toInt() || bytes.u32(0) != ZIP64_LOCATOR_SIGNATURE) return null
            return Zip64Locator(
                recordDisk = bytes.u32(ZIP64_LOCATOR_DISK_OFFSET),
                recordOffset = bytes.u64(ZIP64_LOCATOR_RECORD_OFFSET),
                totalDisks = bytes.u32(ZIP64_LOCATOR_TOTAL_DISKS_OFFSET),
            )
        }
    }
}

private data class Zip64EndRecord(
    val recordBytes: Long,
    val diskNumber: Long,
    val centralDirectoryDisk: Long,
    val entriesOnDisk: ULong,
    val totalEntries: ULong,
    val centralDirectoryBytes: ULong,
    val centralDirectoryOffset: ULong,
) {
    companion object {
        fun parse(bytes: ByteArray): Zip64EndRecord? {
            if (bytes.size != ZIP64_END_RECORD_MIN_BYTES.toInt() ||
                bytes.u32(0) != ZIP64_END_RECORD_SIGNATURE ||
                bytes.u16(ZIP64_END_RECORD_VERSION_NEEDED_OFFSET) < ZIP64_MIN_VERSION
            ) {
                return null
            }
            val recordBytes = bytes.u64(ZIP64_END_RECORD_SIZE_OFFSET)
                .toLongOrNull()
                ?.takeIf { it >= ZIP64_END_RECORD_MIN_BODY_BYTES }
                ?.let { ZIP64_END_RECORD_PREFIX_BYTES.checkedAdd(it) }
                ?: return null
            return Zip64EndRecord(
                recordBytes = recordBytes,
                diskNumber = bytes.u32(ZIP64_END_RECORD_DISK_OFFSET),
                centralDirectoryDisk = bytes.u32(ZIP64_END_RECORD_CENTRAL_DISK_OFFSET),
                entriesOnDisk = bytes.u64(ZIP64_END_RECORD_ENTRIES_ON_DISK_OFFSET),
                totalEntries = bytes.u64(ZIP64_END_RECORD_TOTAL_ENTRIES_OFFSET),
                centralDirectoryBytes = bytes.u64(ZIP64_END_RECORD_CENTRAL_SIZE_OFFSET),
                centralDirectoryOffset = bytes.u64(ZIP64_END_RECORD_CENTRAL_OFFSET_OFFSET),
            )
        }
    }
}

private fun ByteArray.u16(offset: Int): Long = (this[offset].toLong() and BYTE_MASK) or
    ((this[offset + 1].toLong() and BYTE_MASK) shl Byte.SIZE_BITS)

private fun ByteArray.u32(offset: Int): Long = u16(offset) or (u16(offset + Short.SIZE_BYTES) shl Short.SIZE_BITS)

private fun ByteArray.u64(offset: Int): ULong =
    u32(offset).toULong() or (u32(offset + Int.SIZE_BYTES).toULong() shl Int.SIZE_BITS)

private fun ULong.toLongOrNull(): Long? = takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()

private fun Long.checkedAdd(other: Long): Long? =
    takeIf { this >= 0L && other >= 0L && this <= Long.MAX_VALUE - other }?.plus(other)

private fun Long.matchesZip64(sentinel: Long, zip64Value: ULong): Boolean = this == sentinel || toULong() == zip64Value

private const val BYTE_MASK = 0xffL
private const val UINT16_MAX = 0xffffL
private const val UINT32_MAX = 0xffff_ffffL

private const val SINGLE_DISK_NUMBER = 0L
private const val SINGLE_DISK_COUNT = 1L

private const val SIGNATURE_BYTES = 4
private const val CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50L
private const val CENTRAL_DIRECTORY_HEADER_BYTES = 46L
private const val CENTRAL_COMPRESSED_SIZE_OFFSET = 20
private const val CENTRAL_UNCOMPRESSED_SIZE_OFFSET = 24
private const val CENTRAL_NAME_LENGTH_OFFSET = 28
private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
private const val CENTRAL_DISK_NUMBER_OFFSET = 34
private const val CENTRAL_LOCAL_HEADER_OFFSET = 42

private const val EXTRA_HEADER_BYTES = 4
private const val ZIP64_EXTRA_ID = 0x0001L

private const val END_RECORD_SIGNATURE = 0x06054b50L
private const val END_RECORD_BYTES = 22L
private const val END_DISK_NUMBER_OFFSET = 4
private const val END_CENTRAL_DISK_OFFSET = 6
private const val END_ENTRIES_ON_DISK_OFFSET = 8
private const val END_TOTAL_ENTRIES_OFFSET = 10
private const val END_CENTRAL_SIZE_OFFSET = 12
private const val END_CENTRAL_OFFSET_OFFSET = 16
private const val END_COMMENT_LENGTH_OFFSET = 20
private const val MAX_ZIP_COMMENT_BYTES = 0xffffL
private const val MAX_END_SEARCH_BYTES = END_RECORD_BYTES + MAX_ZIP_COMMENT_BYTES

private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
private const val ZIP64_LOCATOR_BYTES = 20L
private const val ZIP64_LOCATOR_DISK_OFFSET = 4
private const val ZIP64_LOCATOR_RECORD_OFFSET = 8
private const val ZIP64_LOCATOR_TOTAL_DISKS_OFFSET = 16

private const val ZIP64_END_RECORD_SIGNATURE = 0x06064b50L
private const val ZIP64_END_RECORD_MIN_BODY_BYTES = 44L
private const val ZIP64_END_RECORD_PREFIX_BYTES = 12L
private const val ZIP64_END_RECORD_MIN_BYTES = ZIP64_END_RECORD_PREFIX_BYTES + ZIP64_END_RECORD_MIN_BODY_BYTES
private const val ZIP64_END_RECORD_SIZE_OFFSET = 4
private const val ZIP64_END_RECORD_VERSION_NEEDED_OFFSET = 14
private const val ZIP64_END_RECORD_DISK_OFFSET = 16
private const val ZIP64_END_RECORD_CENTRAL_DISK_OFFSET = 20
private const val ZIP64_END_RECORD_ENTRIES_ON_DISK_OFFSET = 24
private const val ZIP64_END_RECORD_TOTAL_ENTRIES_OFFSET = 32
private const val ZIP64_END_RECORD_CENTRAL_SIZE_OFFSET = 40
private const val ZIP64_END_RECORD_CENTRAL_OFFSET_OFFSET = 48
private const val ZIP64_MIN_VERSION = 45L
