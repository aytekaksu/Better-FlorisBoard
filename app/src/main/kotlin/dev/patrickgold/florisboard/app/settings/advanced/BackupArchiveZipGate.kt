/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

@file:Suppress("MagicNumber")

package dev.patrickgold.florisboard.app.settings.advanced

import dev.patrickgold.florisboard.lib.io.ZipCentralHeader
import dev.patrickgold.florisboard.lib.io.ZipEndRecord
import dev.patrickgold.florisboard.lib.io.ZipEndRecordSearch
import dev.patrickgold.florisboard.lib.io.ZipRecordReader
import dev.patrickgold.florisboard.lib.io.ZipRecordReader.Companion.CENTRAL_HEADER_BYTES
import dev.patrickgold.florisboard.lib.io.zipCheckedAdd
import dev.patrickgold.florisboard.lib.io.zipU16
import dev.patrickgold.florisboard.lib.io.zipU32
import dev.patrickgold.florisboard.lib.io.zipU64
import java.io.IOException
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
    channel: FileChannel,
    private val archiveSize: Long,
    private val maxEntries: Int,
    private val maxCentralDirectoryBytes: Long,
    private val maxNameBytes: Int,
    private val maxExtraBytes: Int,
    private val maxCommentBytes: Int,
) {
    private val reader = ZipRecordReader(channel)

    fun inspect(): BackupArchiveZipGateResult {
        val endRecord = when (val search = reader.findEndRecord(archiveSize)) {
            is ZipEndRecordSearch.Found -> search.record

            ZipEndRecordSearch.Missing -> {
                return invalid(BackupArchiveZipGateFailure.END_RECORD_NOT_FOUND)
            }

            ZipEndRecordSearch.TrailingMismatch -> {
                return invalid(BackupArchiveZipGateFailure.END_RECORD_TRAILING_MISMATCH)
            }
        }
        val hasLocator = reader.hasZip64Locator(endRecord.offset, archiveSize) == true
        return if (endRecord.hasZip64Sentinel || hasLocator) {
            inspectZip64(endRecord, hasLocator)
        } else {
            inspectClassic(endRecord)
        }
    }

    private fun inspectClassic(endRecord: ZipEndRecord): BackupArchiveZipGateResult {
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

    private fun inspectZip64(endRecord: ZipEndRecord, hasLocator: Boolean): BackupArchiveZipGateResult =
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

    private fun readZip64Locator(endRecord: ZipEndRecord, hasLocator: Boolean): GateValue<LocatedZip64Locator> {
        if (!hasLocator) return GateValue.Invalid(BackupArchiveZipGateFailure.INVALID_ZIP64)
        val locatorOffset = endRecord.offset - ZIP64_LOCATOR_BYTES
        val locator = reader.readExact(
            locatorOffset,
            ZIP64_LOCATOR_BYTES.toInt(),
            archiveSize,
        )?.let(Zip64Locator::parse)
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
        val record = reader.readExact(locator.recordOffset, ZIP64_END_RECORD_MIN_BYTES.toInt(), archiveSize)
            ?.let(Zip64EndRecord::parse)
        val recordEnd = record?.recordBytes?.zipCheckedAdd(locator.recordOffset)
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
        val end = size.zipCheckedAdd(offset)
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

        entryCount > 0L && centralDirectoryBytes < entryCount * CENTRAL_HEADER_BYTES -> {
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
        while (cursor < endOffset) {
            val header = reader.centralHeader(cursor, archiveSize)
            if (header == null) {
                return BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
            }
            actualEntries++
            val nextOffset = cursor.zipCheckedAdd(header.recordBytes)
            val entryFailure = centralEntryLayoutFailure(
                entryNumber = actualEntries,
                nameBytes = header.nameBytes.toLong(),
                extraBytes = header.extraBytes.toLong(),
                commentBytes = header.commentBytes.toLong(),
                nextOffset = nextOffset,
                endOffset = endOffset,
            ) ?: centralEntryDiskFailure(header, cursor)
            if (entryFailure != null) return entryFailure
            cursor = nextOffset ?: return BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
        }
        return BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
            .takeIf { actualEntries != expectedEntries }
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

    private fun centralEntryDiskFailure(header: ZipCentralHeader, headerOffset: Long): BackupArchiveZipGateFailure? {
        val diskNumber = header.diskStart.toLong()
        return when {
            diskNumber == SINGLE_DISK_NUMBER -> null

            diskNumber != UINT16_MAX -> BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE

            else -> {
                val extraOffset = headerOffset.zipCheckedAdd(CENTRAL_HEADER_BYTES.toLong())
                    ?.zipCheckedAdd(header.nameBytes.toLong())
                val extra = extraOffset?.let { reader.readExact(it, header.extraBytes, archiveSize) }
                if (extra == null) {
                    BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
                } else {
                    resolveZip64DiskNumber(header, extra)
                }
            }
        }
    }

    private fun resolveZip64DiskNumber(header: ZipCentralHeader, extra: ByteArray): BackupArchiveZipGateFailure? {
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
        header: ZipCentralHeader,
        extra: ByteArray,
        cursor: Int,
        resolvedDisk: Long?,
    ): Zip64ExtraFieldInspection {
        if (extra.size - cursor < EXTRA_HEADER_BYTES) {
            return Zip64ExtraFieldInspection.invalid(cursor, resolvedDisk)
        }
        val id = extra.zipU16(cursor)
        val valueBytes = extra.zipU16(cursor + Short.SIZE_BYTES)
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
                resolvedDisk = extra.zipU32(valueOffset + diskOffset),
                failure = null,
            )
        }
    }

    private fun zip64DiskOffset(header: ZipCentralHeader): Int {
        var offset = 0
        if (header.expandedBytes == UINT32_MAX) {
            offset += Long.SIZE_BYTES
        }
        if (header.compressedBytes == UINT32_MAX) {
            offset += Long.SIZE_BYTES
        }
        if (header.localHeaderOffset == UINT32_MAX) {
            offset += Long.SIZE_BYTES
        }
        return offset
    }

    private fun invalid(failure: BackupArchiveZipGateFailure): BackupArchiveZipGateResult.Invalid =
        BackupArchiveZipGateResult.Invalid(failure)
}

private val ZipEndRecord.hasZip64Sentinel: Boolean
    get() = when {
        diskNumber == UINT16_MAX || centralDirectoryDisk == UINT16_MAX -> true
        entriesOnDisk == UINT16_MAX || totalEntries == UINT16_MAX -> true
        centralDirectoryBytes == UINT32_MAX || centralDirectoryOffset == UINT32_MAX -> true
        else -> false
    }

private fun ZipEndRecord.matches(zip64: Zip64EndRecord): Boolean {
    val disksMatch = diskNumber.matchesZip64(UINT16_MAX, zip64.diskNumber.toULong()) &&
        centralDirectoryDisk.matchesZip64(UINT16_MAX, zip64.centralDirectoryDisk.toULong())
    val countsMatch = entriesOnDisk.matchesZip64(UINT16_MAX, zip64.entriesOnDisk) &&
        totalEntries.matchesZip64(UINT16_MAX, zip64.totalEntries)
    val directoryMatches = centralDirectoryBytes.matchesZip64(UINT32_MAX, zip64.centralDirectoryBytes) &&
        centralDirectoryOffset.matchesZip64(UINT32_MAX, zip64.centralDirectoryOffset)
    return disksMatch && countsMatch && directoryMatches
}

private sealed interface GateValue<out T> {
    data class Valid<T>(val value: T) : GateValue<T>

    data class Invalid(val failure: BackupArchiveZipGateFailure) : GateValue<Nothing>
}

private data class LocatedZip64Locator(val locatorOffset: Long, val recordOffset: Long)

private data class LocatedZip64EndRecord(val record: Zip64EndRecord, val recordOffset: Long)

private data class BoundedCentralDirectory(val layout: BackupArchiveZipLayout, val endOffset: Long)

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

private data class Zip64Locator(val recordDisk: Long, val recordOffset: ULong, val totalDisks: Long) {
    companion object {
        fun parse(bytes: ByteArray): Zip64Locator? {
            if (bytes.size != ZIP64_LOCATOR_BYTES.toInt() || bytes.zipU32(0) != ZIP64_LOCATOR_SIGNATURE) return null
            return Zip64Locator(
                recordDisk = bytes.zipU32(ZIP64_LOCATOR_DISK_OFFSET),
                recordOffset = bytes.zipU64(ZIP64_LOCATOR_RECORD_OFFSET),
                totalDisks = bytes.zipU32(ZIP64_LOCATOR_TOTAL_DISKS_OFFSET),
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
                bytes.zipU32(0) != ZIP64_END_RECORD_SIGNATURE ||
                bytes.zipU16(ZIP64_END_RECORD_VERSION_NEEDED_OFFSET) < ZIP64_MIN_VERSION
            ) {
                return null
            }
            val recordBytes = bytes.zipU64(ZIP64_END_RECORD_SIZE_OFFSET)
                .toLongOrNull()
                ?.takeIf { it >= ZIP64_END_RECORD_MIN_BODY_BYTES }
                ?.let { ZIP64_END_RECORD_PREFIX_BYTES.zipCheckedAdd(it) }
                ?: return null
            return Zip64EndRecord(
                recordBytes = recordBytes,
                diskNumber = bytes.zipU32(ZIP64_END_RECORD_DISK_OFFSET),
                centralDirectoryDisk = bytes.zipU32(ZIP64_END_RECORD_CENTRAL_DISK_OFFSET),
                entriesOnDisk = bytes.zipU64(ZIP64_END_RECORD_ENTRIES_ON_DISK_OFFSET),
                totalEntries = bytes.zipU64(ZIP64_END_RECORD_TOTAL_ENTRIES_OFFSET),
                centralDirectoryBytes = bytes.zipU64(ZIP64_END_RECORD_CENTRAL_SIZE_OFFSET),
                centralDirectoryOffset = bytes.zipU64(ZIP64_END_RECORD_CENTRAL_OFFSET_OFFSET),
            )
        }
    }
}

private fun ULong.toLongOrNull(): Long? = takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()

private fun Long.matchesZip64(sentinel: Long, zip64Value: ULong): Boolean = this == sentinel || toULong() == zip64Value

private const val UINT16_MAX = 0xffffL
private const val UINT32_MAX = 0xffff_ffffL

private const val SINGLE_DISK_NUMBER = 0L
private const val SINGLE_DISK_COUNT = 1L

private const val EXTRA_HEADER_BYTES = 4
private const val ZIP64_EXTRA_ID = 0x0001

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
