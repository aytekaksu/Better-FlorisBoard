/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Reads structural ZIP records without owning the channel or deciding archive policy. */
internal class ZipRecordReader(private val channel: FileChannel, private val checkActive: () -> Unit = {}) {
    fun readExact(offset: Long, byteCount: Int, upperBound: Long = channel.size()): ByteArray? {
        val end = offset.zipCheckedAdd(byteCount.toLong())
        if (upperBound < 0L || end == null || end > upperBound) return null
        val bytes = ByteArray(byteCount)
        val buffer = ByteBuffer.wrap(bytes)
        var cursor = offset
        while (buffer.hasRemaining()) {
            checkActive()
            val readCount = channel.read(buffer, cursor)
            if (readCount <= 0) return null
            cursor += readCount
        }
        return bytes
    }

    fun findEndRecord(archiveBytes: Long): ZipEndRecordSearch {
        if (archiveBytes < END_RECORD_BYTES) return ZipEndRecordSearch.Missing
        val tailBytes = minOf(archiveBytes, MAX_END_SEARCH_BYTES.toLong()).toInt()
        val tailOffset = archiveBytes - tailBytes
        val tail = readExact(tailOffset, tailBytes, archiveBytes) ?: return ZipEndRecordSearch.Missing
        for (index in tail.size - END_RECORD_BYTES downTo 0) {
            checkActive()
            if (tail.zipU32(index) == END_RECORD_SIGNATURE) {
                // Commons selects the latest signature too; never admit a different directory.
                return if (tail.zipU16(index + END_COMMENT_LENGTH_OFFSET) != tail.size - index - END_RECORD_BYTES) {
                    ZipEndRecordSearch.TrailingMismatch
                } else {
                    ZipEndRecordSearch.Found(
                        ZipEndRecord(
                            offset = tailOffset + index,
                            diskNumber = tail.zipU16(index + END_DISK_NUMBER_OFFSET).toLong(),
                            centralDirectoryDisk = tail.zipU16(index + END_CENTRAL_DISK_OFFSET).toLong(),
                            entriesOnDisk = tail.zipU16(index + END_ENTRIES_ON_DISK_OFFSET).toLong(),
                            totalEntries = tail.zipU16(index + END_TOTAL_ENTRIES_OFFSET).toLong(),
                            centralDirectoryBytes = tail.zipU32(index + END_CENTRAL_SIZE_OFFSET),
                            centralDirectoryOffset = tail.zipU32(index + END_CENTRAL_OFFSET_OFFSET),
                        ),
                    )
                }
            }
        }
        return ZipEndRecordSearch.Missing
    }

    fun hasZip64Locator(endRecordOffset: Long, upperBound: Long = channel.size()): Boolean? {
        if (endRecordOffset < ZIP64_LOCATOR_BYTES) return false
        return readExact(endRecordOffset - ZIP64_LOCATOR_BYTES, Int.SIZE_BYTES, upperBound)
            ?.let { it.zipU32(0) == ZIP64_LOCATOR_SIGNATURE }
    }

    fun centralHeader(offset: Long, upperBound: Long = channel.size()): ZipCentralHeader? =
        readExact(offset, CENTRAL_HEADER_BYTES, upperBound)
            ?.takeIf { it.zipU32(0) == CENTRAL_HEADER_SIGNATURE }
            ?.let { bytes ->
                ZipCentralHeader(
                    flags = bytes.zipU16(CENTRAL_FLAGS_OFFSET),
                    method = bytes.zipU16(CENTRAL_METHOD_OFFSET),
                    compressedBytes = bytes.zipU32(CENTRAL_COMPRESSED_SIZE_OFFSET),
                    expandedBytes = bytes.zipU32(CENTRAL_EXPANDED_SIZE_OFFSET),
                    nameBytes = bytes.zipU16(CENTRAL_NAME_LENGTH_OFFSET),
                    extraBytes = bytes.zipU16(CENTRAL_EXTRA_LENGTH_OFFSET),
                    commentBytes = bytes.zipU16(CENTRAL_COMMENT_LENGTH_OFFSET),
                    diskStart = bytes.zipU16(CENTRAL_DISK_START_OFFSET),
                    localHeaderOffset = bytes.zipU32(CENTRAL_LOCAL_HEADER_OFFSET),
                )
            }

    fun localHeader(offset: Long, upperBound: Long = channel.size()): ZipLocalHeader? =
        readExact(offset, LOCAL_HEADER_BYTES, upperBound)
            ?.takeIf { it.zipU32(0) == LOCAL_HEADER_SIGNATURE }
            ?.let { bytes ->
                ZipLocalHeader(
                    flags = bytes.zipU16(LOCAL_FLAGS_OFFSET),
                    method = bytes.zipU16(LOCAL_METHOD_OFFSET),
                    nameBytes = bytes.zipU16(LOCAL_NAME_LENGTH_OFFSET),
                    extraBytes = bytes.zipU16(LOCAL_EXTRA_LENGTH_OFFSET),
                )
            }

    companion object {
        const val CENTRAL_HEADER_BYTES = 46
        const val LOCAL_HEADER_BYTES = 30
        const val END_RECORD_BYTES = 22
        private const val MAX_END_SEARCH_BYTES = END_RECORD_BYTES + 0xffff
        private const val END_RECORD_SIGNATURE = 0x06054b50L
        private const val END_DISK_NUMBER_OFFSET = 4
        private const val END_CENTRAL_DISK_OFFSET = 6
        private const val END_ENTRIES_ON_DISK_OFFSET = 8
        private const val END_TOTAL_ENTRIES_OFFSET = 10
        private const val END_CENTRAL_SIZE_OFFSET = 12
        private const val END_CENTRAL_OFFSET_OFFSET = 16
        private const val END_COMMENT_LENGTH_OFFSET = 20
        private const val ZIP64_LOCATOR_BYTES = 20L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
        private const val CENTRAL_FLAGS_OFFSET = 8
        private const val CENTRAL_METHOD_OFFSET = 10
        private const val CENTRAL_COMPRESSED_SIZE_OFFSET = 20
        private const val CENTRAL_EXPANDED_SIZE_OFFSET = 24
        private const val CENTRAL_NAME_LENGTH_OFFSET = 28
        private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
        private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
        private const val CENTRAL_DISK_START_OFFSET = 34
        private const val CENTRAL_LOCAL_HEADER_OFFSET = 42
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
        private const val LOCAL_FLAGS_OFFSET = 6
        private const val LOCAL_METHOD_OFFSET = 8
        private const val LOCAL_NAME_LENGTH_OFFSET = 26
        private const val LOCAL_EXTRA_LENGTH_OFFSET = 28
    }
}

internal sealed interface ZipEndRecordSearch {
    data class Found(val record: ZipEndRecord) : ZipEndRecordSearch
    data object Missing : ZipEndRecordSearch
    data object TrailingMismatch : ZipEndRecordSearch
}

internal data class ZipEndRecord(
    val offset: Long,
    val diskNumber: Long,
    val centralDirectoryDisk: Long,
    val entriesOnDisk: Long,
    val totalEntries: Long,
    val centralDirectoryBytes: Long,
    val centralDirectoryOffset: Long,
)

internal data class ZipCentralHeader(
    val flags: Int,
    val method: Int,
    val compressedBytes: Long,
    val expandedBytes: Long,
    val nameBytes: Int,
    val extraBytes: Int,
    val commentBytes: Int,
    val diskStart: Int,
    val localHeaderOffset: Long,
) {
    val recordBytes: Long
        get() = ZipRecordReader.CENTRAL_HEADER_BYTES.toLong() + nameBytes + extraBytes + commentBytes
}

internal data class ZipLocalHeader(val flags: Int, val method: Int, val nameBytes: Int, val extraBytes: Int) {
    fun dataOffset(headerOffset: Long): Long? =
        headerOffset.zipCheckedAdd(ZipRecordReader.LOCAL_HEADER_BYTES.toLong() + nameBytes + extraBytes)
}

internal fun ByteArray.zipU16(offset: Int): Int = (this[offset].toInt() and BYTE_MASK) or
    ((this[offset + 1].toInt() and BYTE_MASK) shl Byte.SIZE_BITS)

internal fun ByteArray.zipU32(offset: Int): Long =
    zipU16(offset).toLong() or (zipU16(offset + Short.SIZE_BYTES).toLong() shl Short.SIZE_BITS)

internal fun ByteArray.zipU64(offset: Int): ULong =
    zipU32(offset).toULong() or (zipU32(offset + Int.SIZE_BYTES).toULong() shl Int.SIZE_BITS)

internal fun Long.zipCheckedAdd(other: Long): Long? =
    takeIf { this >= 0L && other >= 0L && this <= Long.MAX_VALUE - other }?.plus(other)

private const val BYTE_MASK = 0xff
