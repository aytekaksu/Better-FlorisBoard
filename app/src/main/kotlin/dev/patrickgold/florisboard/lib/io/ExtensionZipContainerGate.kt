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

package dev.patrickgold.florisboard.lib.io

import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException

/**
 * Small structural gate which runs before Commons Compress allocates its entry
 * table. It rejects multi-disk and ZIP64 containers because neither is needed
 * within the extension archive limits.
 */
internal object ExtensionZipContainerGate {
    fun accepts(channel: FileChannel, archiveBytes: Long, maxEntries: Int, maxNameBytes: Int): Boolean = try {
        inspect(channel, archiveBytes, maxEntries, maxNameBytes)
    } catch (error: InterruptedException) {
        throw error
    } catch (_: ClosedByInterruptException) {
        throw InterruptedException()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun inspect(channel: FileChannel, archiveBytes: Long, maxEntries: Int, maxNameBytes: Int): Boolean =
        Inspector(channel, archiveBytes, maxEntries, maxNameBytes).inspect()

    private class Inspector(
        private val channel: FileChannel,
        private val archiveBytes: Long,
        private val maxEntries: Int,
        private val maxNameBytes: Int,
    ) {
        fun inspect(): Boolean {
            if (!hasValidInputs()) return false
            val endRecord = inspectEndRecordPhase() ?: return false
            return inspectCentralDirectoryPhase(endRecord)
        }

        private fun hasValidInputs(): Boolean = archiveBytes >= END_RECORD_BYTES && maxEntries >= 0 && maxNameBytes >= 0

        private fun inspectEndRecordPhase(): EndRecord? {
            val located = locateEndRecord() ?: return null
            val endRecord = parseEndRecord(located) ?: return null
            if (!endRecord.hasSingleDiskLayout ||
                !endRecord.hasSupportedEntryCount(maxEntries) ||
                !endRecord.hasClassicOffsets ||
                !endRecord.isContiguous
            ) {
                return null
            }
            return endRecord.takeIf {
                inspectZip64Locator(it.absoluteOffset) == Zip64LocatorStatus.ABSENT
            }
        }

        private fun locateEndRecord(): LocatedEndRecord? {
            val searchBytes = minOf(archiveBytes, MAX_END_SEARCH_BYTES.toLong()).toInt()
            val searchOffset = archiveBytes - searchBytes
            val tail = channel.readExact(searchOffset, searchBytes) ?: return null
            val relativeOffset = tail.findEndRecord() ?: return null
            return LocatedEndRecord(
                bytes = tail.copyOfRange(relativeOffset, relativeOffset + END_RECORD_BYTES),
                absoluteOffset = searchOffset + relativeOffset,
            )
        }

        private fun parseEndRecord(located: LocatedEndRecord): EndRecord? {
            val centralBytes = located.bytes.u32(END_CENTRAL_SIZE_OFFSET)
            val centralOffset = located.bytes.u32(END_CENTRAL_OFFSET_OFFSET)
            val centralEnd = centralOffset.checkedAdd(centralBytes) ?: return null
            return EndRecord(
                disk = located.bytes.u16(END_DISK_OFFSET),
                centralDisk = located.bytes.u16(END_CENTRAL_DISK_OFFSET),
                entriesOnDisk = located.bytes.u16(END_ENTRIES_ON_DISK_OFFSET),
                entryCount = located.bytes.u16(END_TOTAL_ENTRIES_OFFSET),
                centralBytes = centralBytes,
                centralOffset = centralOffset,
                centralEnd = centralEnd,
                absoluteOffset = located.absoluteOffset,
            )
        }

        private fun inspectZip64Locator(endRecordOffset: Long): Zip64LocatorStatus {
            if (endRecordOffset < ZIP64_LOCATOR_BYTES) return Zip64LocatorStatus.ABSENT
            val possibleLocator = channel.readExact(
                endRecordOffset - ZIP64_LOCATOR_BYTES,
                Int.SIZE_BYTES,
            ) ?: return Zip64LocatorStatus.INVALID
            return if (possibleLocator.u32(0) == ZIP64_LOCATOR_SIGNATURE) {
                Zip64LocatorStatus.PRESENT
            } else {
                Zip64LocatorStatus.ABSENT
            }
        }

        private fun inspectCentralDirectoryPhase(endRecord: EndRecord): Boolean {
            val state = CentralDirectoryState(endRecord.centralOffset)
            repeat(endRecord.entryCount) {
                ensureNotInterrupted()
                if (!inspectCentralEntryPhase(endRecord, state)) return false
            }
            return state.cursor == endRecord.centralEnd
        }

        private fun inspectCentralEntryPhase(endRecord: EndRecord, state: CentralDirectoryState): Boolean {
            val entry = readCentralEntry(state.cursor) ?: return false
            if (!state.addExtraFields(entry.extraFieldCount)) return false
            val localEntry = inspectLocalEntryPhase(entry, endRecord.centralOffset) ?: return false
            return state.accept(entry, localEntry, endRecord.centralEnd)
        }

        private fun readCentralEntry(cursor: Long): CentralEntry? {
            val header = readCentralHeader(cursor) ?: return null
            return readCentralPayload(cursor, header)
        }

        private fun readCentralHeader(cursor: Long): CentralHeader? {
            val bytes = channel.readExact(cursor, CENTRAL_HEADER_BYTES) ?: return null
            val header = CentralHeader(
                signature = bytes.u32(0),
                flags = bytes.u16(CENTRAL_FLAGS_OFFSET),
                method = bytes.u16(CENTRAL_METHOD_OFFSET),
                compressedBytes = bytes.u32(CENTRAL_COMPRESSED_SIZE_OFFSET),
                expandedBytes = bytes.u32(CENTRAL_EXPANDED_SIZE_OFFSET),
                nameBytes = bytes.u16(CENTRAL_NAME_LENGTH_OFFSET),
                extraBytes = bytes.u16(CENTRAL_EXTRA_LENGTH_OFFSET),
                commentBytes = bytes.u16(CENTRAL_COMMENT_LENGTH_OFFSET),
                diskStart = bytes.u16(CENTRAL_DISK_START_OFFSET),
                localHeaderOffset = bytes.u32(CENTRAL_LOCAL_HEADER_OFFSET),
            )
            return header.takeIf {
                it.signature == CENTRAL_HEADER_SIGNATURE &&
                    it.hasSupportedLengths(maxNameBytes) &&
                    it.hasClassicLocation
            }
        }

        private fun readCentralPayload(cursor: Long, header: CentralHeader): CentralEntry? {
            val name = channel.readExact(
                cursor + CENTRAL_HEADER_BYTES,
                header.nameBytes,
            ) ?: return null
            val extraOffset = cursor + CENTRAL_HEADER_BYTES + header.nameBytes
            val extra = channel.readExact(extraOffset, header.extraBytes) ?: return null
            val extraFieldCount = extra.validExtraFieldCount() ?: return null
            return CentralEntry(header, name, extraFieldCount)
        }

        private fun inspectLocalEntryPhase(centralEntry: CentralEntry, centralOffset: Long): LocalEntry? {
            val localHeader = readLocalHeader(centralEntry.header) ?: return null
            val localName = channel.readExact(
                centralEntry.header.localHeaderOffset + LOCAL_HEADER_BYTES,
                localHeader.nameBytes,
            ) ?: return null
            val dataEnd = localHeader.dataEnd(
                centralEntry.header.localHeaderOffset,
                centralEntry.header.compressedBytes,
            ) ?: return null
            return LocalEntry(localHeader.nameBytes, localHeader.extraBytes).takeIf {
                localName.contentEquals(centralEntry.name) && dataEnd <= centralOffset
            }
        }

        private fun readLocalHeader(centralHeader: CentralHeader): LocalHeader? {
            val bytes = channel.readExact(
                centralHeader.localHeaderOffset,
                LOCAL_HEADER_BYTES,
            ) ?: return null
            val header = LocalHeader(
                signature = bytes.u32(0),
                flags = bytes.u16(LOCAL_FLAGS_OFFSET),
                method = bytes.u16(LOCAL_METHOD_OFFSET),
                nameBytes = bytes.u16(LOCAL_NAME_LENGTH_OFFSET),
                extraBytes = bytes.u16(LOCAL_EXTRA_LENGTH_OFFSET),
            )
            return header.takeIf {
                it.signature == LOCAL_HEADER_SIGNATURE &&
                    it.matches(centralHeader) &&
                    it.extraBytes <= MAX_ENTRY_EXTRA_BYTES
            }
        }
    }

    private data class LocatedEndRecord(val bytes: ByteArray, val absoluteOffset: Long)

    private data class EndRecord(
        val disk: Int,
        val centralDisk: Int,
        val entriesOnDisk: Int,
        val entryCount: Int,
        val centralBytes: Long,
        val centralOffset: Long,
        val centralEnd: Long,
        val absoluteOffset: Long,
    ) {
        val hasSingleDiskLayout
            get() = disk == 0 && centralDisk == 0 && entriesOnDisk == entryCount

        val hasClassicOffsets
            get() = centralBytes != ZIP64_U32_SENTINEL && centralOffset != ZIP64_U32_SENTINEL

        val isContiguous
            get() = centralEnd == absoluteOffset

        fun hasSupportedEntryCount(maxEntries: Int): Boolean =
            entryCount <= maxEntries && entryCount != ZIP64_U16_SENTINEL
    }

    private data class CentralHeader(
        val signature: Long,
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
        val hasClassicLocation
            get() = diskStart == 0 &&
                compressedBytes != ZIP64_U32_SENTINEL &&
                expandedBytes != ZIP64_U32_SENTINEL &&
                localHeaderOffset != ZIP64_U32_SENTINEL

        fun hasSupportedLengths(maxNameBytes: Int): Boolean = nameBytes in 1..maxNameBytes &&
            extraBytes <= MAX_ENTRY_EXTRA_BYTES &&
            commentBytes <= MAX_ENTRY_COMMENT_BYTES

        fun metadataBytes(localEntry: LocalEntry): Long? = nameBytes.toLong()
            .checkedAdd(extraBytes.toLong())
            ?.checkedAdd(commentBytes.toLong())
            ?.checkedAdd(localEntry.nameBytes.toLong())
            ?.checkedAdd(localEntry.extraBytes.toLong())

        fun recordBytes(): Long? = CENTRAL_HEADER_BYTES.toLong()
            .checkedAdd(nameBytes.toLong())
            ?.checkedAdd(extraBytes.toLong())
            ?.checkedAdd(commentBytes.toLong())
    }

    private data class CentralEntry(val header: CentralHeader, val name: ByteArray, val extraFieldCount: Int)

    private data class LocalHeader(
        val signature: Long,
        val flags: Int,
        val method: Int,
        val nameBytes: Int,
        val extraBytes: Int,
    ) {
        fun matches(centralHeader: CentralHeader): Boolean = flags == centralHeader.flags &&
            method == centralHeader.method &&
            nameBytes == centralHeader.nameBytes

        fun dataEnd(localHeaderOffset: Long, compressedBytes: Long): Long? =
            localHeaderOffset.checkedAdd(LOCAL_HEADER_BYTES.toLong())
                ?.checkedAdd(nameBytes.toLong())
                ?.checkedAdd(extraBytes.toLong())
                ?.checkedAdd(compressedBytes)
    }

    private data class LocalEntry(val nameBytes: Int, val extraBytes: Int)

    private class CentralDirectoryState(var cursor: Long) {
        private var extraFieldCount = 0
        private var metadataBytes = 0L

        fun addExtraFields(entryFields: Int): Boolean {
            extraFieldCount += entryFields
            return extraFieldCount <= MAX_TOTAL_EXTRA_FIELDS
        }

        fun accept(entry: CentralEntry, localEntry: LocalEntry, centralEnd: Long): Boolean =
            reserveMetadata(entry.header, localEntry) &&
                advanceCursor(entry.header, centralEnd)

        private fun reserveMetadata(header: CentralHeader, localEntry: LocalEntry): Boolean {
            val entryBytes = header.metadataBytes(localEntry) ?: return false
            val nextTotal = metadataBytes.checkedAdd(entryBytes) ?: return false
            return if (nextTotal <= MAX_METADATA_BYTES) {
                metadataBytes = nextTotal
                true
            } else {
                false
            }
        }

        private fun advanceCursor(header: CentralHeader, centralEnd: Long): Boolean {
            val entryBytes = header.recordBytes() ?: return false
            val nextCursor = cursor.checkedAdd(entryBytes) ?: return false
            return if (nextCursor <= centralEnd) {
                cursor = nextCursor
                true
            } else {
                false
            }
        }
    }

    private enum class Zip64LocatorStatus {
        ABSENT,
        PRESENT,
        INVALID,
    }

    private fun ByteArray.findEndRecord(): Int? {
        for (offset in size - END_RECORD_BYTES downTo 0) {
            ensureNotInterrupted()
            if (u32(offset) == END_RECORD_SIGNATURE) {
                return offset.takeIf {
                    u16(offset + END_COMMENT_LENGTH_OFFSET) == size - offset - END_RECORD_BYTES
                }
            }
        }
        return null
    }

    private fun FileChannel.readExact(offset: Long, byteCount: Int): ByteArray? {
        val end = offset.checkedAdd(byteCount.toLong()) ?: return null
        if (offset < 0L || byteCount < 0 || end > size()) return null
        val bytes = ByteArray(byteCount)
        val buffer = ByteBuffer.wrap(bytes)
        var cursor = offset
        while (buffer.hasRemaining()) {
            ensureNotInterrupted()
            val readBytes = read(buffer, cursor)
            if (readBytes <= 0) return null
            cursor += readBytes
        }
        return bytes
    }

    private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and BYTE_MASK) or
        ((this[offset + 1].toInt() and BYTE_MASK) shl Byte.SIZE_BITS)

    private fun ByteArray.u32(offset: Int): Long =
        u16(offset).toLong() or (u16(offset + Short.SIZE_BYTES).toLong() shl Short.SIZE_BITS)

    private fun ByteArray.validExtraFieldCount(): Int? {
        var cursor = 0
        var fieldCount = 0
        while (cursor < size) {
            if (size - cursor < EXTRA_FIELD_HEADER_BYTES) return null
            val dataBytes = u16(cursor + Short.SIZE_BYTES)
            if (dataBytes > size - cursor - EXTRA_FIELD_HEADER_BYTES) return null
            cursor += EXTRA_FIELD_HEADER_BYTES + dataBytes
            fieldCount++
            if (fieldCount > MAX_ENTRY_EXTRA_FIELDS) return null
        }
        return fieldCount
    }

    private fun Long.checkedAdd(other: Long): Long? =
        takeIf { this >= 0L && other >= 0L && this <= Long.MAX_VALUE - other }?.plus(other)

    private fun ensureNotInterrupted() {
        if (Thread.interrupted()) throw InterruptedException()
    }

    private const val BYTE_MASK = 0xff
    private const val ZIP64_U16_SENTINEL = 0xffff
    private const val ZIP64_U32_SENTINEL = 0xffff_ffffL
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val ZIP64_LOCATOR_BYTES = 20L

    private const val END_RECORD_SIGNATURE = 0x06054b50L
    private const val END_RECORD_BYTES = 22
    private const val END_DISK_OFFSET = 4
    private const val END_CENTRAL_DISK_OFFSET = 6
    private const val END_ENTRIES_ON_DISK_OFFSET = 8
    private const val END_TOTAL_ENTRIES_OFFSET = 10
    private const val END_CENTRAL_SIZE_OFFSET = 12
    private const val END_CENTRAL_OFFSET_OFFSET = 16
    private const val END_COMMENT_LENGTH_OFFSET = 20
    private const val MAX_END_SEARCH_BYTES = END_RECORD_BYTES + ZIP64_U16_SENTINEL

    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val CENTRAL_HEADER_BYTES = 46
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
    private const val LOCAL_HEADER_BYTES = 30
    private const val LOCAL_FLAGS_OFFSET = 6
    private const val LOCAL_METHOD_OFFSET = 8
    private const val LOCAL_NAME_LENGTH_OFFSET = 26
    private const val LOCAL_EXTRA_LENGTH_OFFSET = 28

    private const val MAX_ENTRY_EXTRA_BYTES = 8 * 1_024
    private const val MAX_ENTRY_COMMENT_BYTES = 1 * 1_024
    private const val MAX_METADATA_BYTES = 16L * 1_024 * 1_024
    private const val EXTRA_FIELD_HEADER_BYTES = 4
    private const val MAX_ENTRY_EXTRA_FIELDS = 64
    private const val MAX_TOTAL_EXTRA_FIELDS = 8_192
}
