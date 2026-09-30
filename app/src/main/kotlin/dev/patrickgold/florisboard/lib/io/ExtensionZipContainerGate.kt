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

import dev.patrickgold.florisboard.lib.io.ZipRecordReader.Companion.CENTRAL_HEADER_BYTES
import dev.patrickgold.florisboard.lib.io.ZipRecordReader.Companion.END_RECORD_BYTES
import dev.patrickgold.florisboard.lib.io.ZipRecordReader.Companion.LOCAL_HEADER_BYTES
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
        Inspector(channel, archiveBytes, maxEntries, maxNameBytes).inspect()
    } catch (error: InterruptedException) {
        throw error
    } catch (_: ClosedByInterruptException) {
        throw InterruptedException()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private class Inspector(
        channel: FileChannel,
        private val archiveBytes: Long,
        private val maxEntries: Int,
        private val maxNameBytes: Int,
    ) {
        private val reader = ZipRecordReader(channel, ::ensureNotInterrupted)

        fun inspect(): Boolean {
            if (archiveBytes < END_RECORD_BYTES || maxEntries < 0 || maxNameBytes < 0) return false
            val endRecord = inspectEndRecordPhase() ?: return false
            return inspectCentralDirectoryPhase(endRecord)
        }

        private fun inspectEndRecordPhase(): ZipEndRecord? {
            val endRecord = (reader.findEndRecord(archiveBytes) as? ZipEndRecordSearch.Found)?.record ?: return null
            val centralEnd = endRecord.centralDirectoryOffset.zipCheckedAdd(endRecord.centralDirectoryBytes)
            if (!endRecord.hasSingleDiskLayout || endRecord.totalEntries > maxEntries ||
                !endRecord.hasClassicOffsets
            ) {
                return null
            }
            return endRecord.takeIf {
                centralEnd == it.offset && reader.hasZip64Locator(it.offset) == false
            }
        }

        private fun inspectCentralDirectoryPhase(endRecord: ZipEndRecord): Boolean {
            val centralEnd = endRecord.centralDirectoryOffset.zipCheckedAdd(endRecord.centralDirectoryBytes)
                ?: return false
            val state = CentralDirectoryState(endRecord.centralDirectoryOffset)
            repeat(endRecord.totalEntries.toInt()) {
                ensureNotInterrupted()
                if (!inspectCentralEntryPhase(endRecord.centralDirectoryOffset, centralEnd, state)) return false
            }
            return state.cursor == centralEnd
        }

        private fun inspectCentralEntryPhase(
            centralOffset: Long,
            centralEnd: Long,
            state: CentralDirectoryState,
        ): Boolean {
            val entry = readCentralEntry(state.cursor) ?: return false
            if (!state.addExtraFields(entry.extraFieldCount)) return false
            val localEntry = inspectLocalEntryPhase(entry, centralOffset) ?: return false
            return state.accept(entry.header, localEntry, centralEnd)
        }

        private fun readCentralEntry(cursor: Long): CentralEntry? {
            val header = reader.centralHeader(cursor) ?: return null
            val supportedLengths = header.nameBytes in 1..maxNameBytes &&
                header.extraBytes <= MAX_ENTRY_EXTRA_BYTES && header.commentBytes <= MAX_ENTRY_COMMENT_BYTES
            val classicLocation = header.diskStart == 0 && header.compressedBytes != ZIP64_U32_SENTINEL &&
                header.expandedBytes != ZIP64_U32_SENTINEL && header.localHeaderOffset != ZIP64_U32_SENTINEL
            if (!supportedLengths || !classicLocation) return null
            return readCentralPayload(cursor, header)
        }

        private fun readCentralPayload(cursor: Long, header: ZipCentralHeader): CentralEntry? {
            val name = reader.readExact(cursor + CENTRAL_HEADER_BYTES, header.nameBytes) ?: return null
            val extra = reader.readExact(cursor + CENTRAL_HEADER_BYTES + header.nameBytes, header.extraBytes)
                ?: return null
            val extraFieldCount = extra.validExtraFieldCount() ?: return null
            return CentralEntry(header, name, extraFieldCount)
        }

        private fun inspectLocalEntryPhase(centralEntry: CentralEntry, centralOffset: Long): ZipLocalHeader? {
            val central = centralEntry.header
            val local = readMatchingLocalHeader(central) ?: return null
            val localName = reader.readExact(central.localHeaderOffset + LOCAL_HEADER_BYTES, local.nameBytes)
                ?: return null
            val dataEnd = local.dataOffset(central.localHeaderOffset)?.zipCheckedAdd(central.compressedBytes)
                ?: return null
            return local.takeIf { localName.contentEquals(centralEntry.name) && dataEnd <= centralOffset }
        }

        private fun readMatchingLocalHeader(central: ZipCentralHeader): ZipLocalHeader? =
            reader.localHeader(central.localHeaderOffset)?.takeIf {
                it.flags == central.flags && it.method == central.method &&
                    it.nameBytes == central.nameBytes && it.extraBytes <= MAX_ENTRY_EXTRA_BYTES
            }
    }

    private val ZipEndRecord.hasSingleDiskLayout: Boolean
        get() = diskNumber == 0L && centralDirectoryDisk == 0L && entriesOnDisk == totalEntries

    private val ZipEndRecord.hasClassicOffsets: Boolean
        get() = totalEntries != ZIP64_U16_SENTINEL && centralDirectoryBytes != ZIP64_U32_SENTINEL &&
            centralDirectoryOffset != ZIP64_U32_SENTINEL

    private data class CentralEntry(val header: ZipCentralHeader, val name: ByteArray, val extraFieldCount: Int)

    private class CentralDirectoryState(var cursor: Long) {
        private var extraFieldCount = 0
        private var metadataBytes = 0L

        fun addExtraFields(entryFields: Int): Boolean {
            extraFieldCount += entryFields
            return extraFieldCount <= MAX_TOTAL_EXTRA_FIELDS
        }

        fun accept(header: ZipCentralHeader, local: ZipLocalHeader, centralEnd: Long): Boolean =
            reserveMetadata(header, local) && advanceCursor(header, centralEnd)

        private fun reserveMetadata(header: ZipCentralHeader, local: ZipLocalHeader): Boolean {
            val entryBytes = header.nameBytes.toLong() + header.extraBytes + header.commentBytes +
                local.nameBytes + local.extraBytes
            val nextTotal = metadataBytes.zipCheckedAdd(entryBytes) ?: return false
            return if (nextTotal <= MAX_METADATA_BYTES) {
                metadataBytes = nextTotal
                true
            } else {
                false
            }
        }

        private fun advanceCursor(header: ZipCentralHeader, centralEnd: Long): Boolean {
            val nextCursor = cursor.zipCheckedAdd(header.recordBytes) ?: return false
            return if (nextCursor <= centralEnd) {
                cursor = nextCursor
                true
            } else {
                false
            }
        }
    }

    private fun ByteArray.validExtraFieldCount(): Int? {
        var cursor = 0
        var fieldCount = 0
        while (cursor < size) {
            if (size - cursor < EXTRA_FIELD_HEADER_BYTES) return null
            val dataBytes = zipU16(cursor + Short.SIZE_BYTES)
            if (dataBytes > size - cursor - EXTRA_FIELD_HEADER_BYTES) return null
            cursor += EXTRA_FIELD_HEADER_BYTES + dataBytes
            fieldCount++
            if (fieldCount > MAX_ENTRY_EXTRA_FIELDS) return null
        }
        return fieldCount
    }

    private fun ensureNotInterrupted() {
        if (Thread.interrupted()) throw InterruptedException()
    }

    private const val ZIP64_U16_SENTINEL = 0xffffL
    private const val ZIP64_U32_SENTINEL = 0xffff_ffffL
    private const val MAX_ENTRY_EXTRA_BYTES = 8 * 1_024
    private const val MAX_ENTRY_COMMENT_BYTES = 1 * 1_024
    private const val MAX_METADATA_BYTES = 16L * 1_024 * 1_024
    private const val EXTRA_FIELD_HEADER_BYTES = 4
    private const val MAX_ENTRY_EXTRA_FIELDS = 64
    private const val MAX_TOTAL_EXTRA_FIELDS = 8_192
}
