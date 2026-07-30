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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class BackupArchiveZipGateTest :
    FunSpec({
        test("classic empty and non-empty archives pass") {
            val empty = gate(classicFixture())
            val emptyLayout = (empty as BackupArchiveZipGateResult.Valid).layout
            emptyLayout.entryCount shouldBe 0
            emptyLayout.centralDirectoryOffset shouldBe 0
            emptyLayout.centralDirectoryBytes shouldBe 0
            emptyLayout.usesZip64 shouldBe false

            val fixture = classicFixture("entry", comment = "comment".encodeToByteArray())
            val layout = (gate(fixture) as BackupArchiveZipGateResult.Valid).layout
            layout.entryCount shouldBe 1
            layout.centralDirectoryOffset shouldBe fixture.centralDirectoryOffset
            layout.centralDirectoryBytes shouldBe fixture.centralDirectoryBytes
            layout.usesZip64 shouldBe false
        }

        test("inspection uses positional reads and preserves the channel position") {
            val fixture = classicFixture("entry")
            withChannel(fixture.bytes) { channel ->
                channel.position(7)

                BackupArchiveZipGate.inspect(
                    channel = channel,
                    archiveSize = fixture.bytes.size.toLong(),
                    maxEntries = TEST_MAX_ENTRIES,
                    maxCentralDirectoryBytes = TEST_MAX_CENTRAL_BYTES,
                    maxNameBytes = TEST_MAX_NAME_BYTES,
                    maxExtraBytes = TEST_MAX_EXTRA_BYTES,
                    maxCommentBytes = TEST_MAX_COMMENT_BYTES,
                ) shouldBeValid true
                channel.position() shouldBe 7
            }
        }

        test("valid ZIP64 archives pass") {
            val fixture = zip64Fixture("entry")

            val layout = (gate(fixture) as BackupArchiveZipGateResult.Valid).layout

            layout.entryCount shouldBe 1
            layout.centralDirectoryOffset shouldBe fixture.centralDirectoryOffset
            layout.centralDirectoryBytes shouldBe fixture.centralDirectoryBytes
            layout.usesZip64 shouldBe true
        }

        test("missing and trailing-mismatched end records fail closed") {
            gate(ZipFixture(byteArrayOf(1, 2, 3))) shouldFailWith
                BackupArchiveZipGateFailure.END_RECORD_NOT_FOUND

            val trailing = classicFixture("entry").bytes + 0x7f.toByte()
            gate(ZipFixture(trailing)) shouldFailWith
                BackupArchiveZipGateFailure.END_RECORD_TRAILING_MISMATCH
        }

        test("invalid sizes and limits fail before parsing") {
            val fixture = classicFixture()
            gate(fixture, archiveSize = -1) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_ARCHIVE_SIZE
            gate(fixture, archiveSize = fixture.bytes.size.toLong() - 1) shouldFailWith
                BackupArchiveZipGateFailure.ARCHIVE_SIZE_MISMATCH
            gate(fixture, maxEntries = -1) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_LIMITS
            gate(fixture, maxCentralDirectoryBytes = -1) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_LIMITS
            gate(fixture, maxNameBytes = -1) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_LIMITS
        }

        test("classic multi-disk metadata is rejected") {
            val fixture = classicFixture("entry")
            val bytes = fixture.bytes.patchedU16(fixture.endRecordOffset + END_DISK_NUMBER_OFFSET, 1)

            gate(ZipFixture(bytes)) shouldFailWith BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE
        }

        test("every central entry must start on the only disk") {
            val fixedDisk = classicFixture("entry").patchCentralDisk(diskNumber = 1)
            gate(fixedDisk) shouldFailWith BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE

            gate(classicFixture("entry").patchCentralDisk(UINT16_MAX, zip64DiskNumber = 0)) shouldBeValid true
            gate(classicFixture("entry").patchCentralDisk(UINT16_MAX, zip64DiskNumber = 1)) shouldFailWith
                BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE
            gate(classicFixture("entry").patchCentralDisk(UINT16_MAX)) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
            val truncatedZip64 = classicFixture("entry").patchCentralDisk(UINT16_MAX, zip64DiskNumber = 0)
            gate(
                truncatedZip64.copy(
                    bytes = truncatedZip64.bytes.patchedU16(
                        truncatedZip64.centralExtraOffset() + Short.SIZE_BYTES,
                        Int.SIZE_BYTES - 1,
                    ),
                ),
            ) shouldFailWith BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
            gate(
                classicFixture("entry").patchCentralDisk(
                    diskNumber = UINT16_MAX,
                    zip64DiskNumber = 0,
                    precedingZip64Fields = true,
                ),
            ) shouldBeValid true
        }

        test("missing and malformed ZIP64 locator or record metadata is rejected") {
            val fixture = zip64Fixture("entry")
            val withoutLocator = fixture.bytes.copyOfRange(0, fixture.locatorOffset) +
                fixture.bytes.copyOfRange(fixture.endRecordOffset, fixture.bytes.size)
            gate(ZipFixture(withoutLocator)) shouldFailWith BackupArchiveZipGateFailure.INVALID_ZIP64

            val invalidRecordOffset = fixture.bytes.patchedU64(
                fixture.locatorOffset + ZIP64_LOCATOR_RECORD_OFFSET,
                Long.MAX_VALUE,
            )
            gate(ZipFixture(invalidRecordOffset)) shouldFailWith BackupArchiveZipGateFailure.INVALID_ZIP64

            val shortRecord = fixture.bytes.patchedU64(
                fixture.zip64RecordOffset + ZIP64_END_RECORD_SIZE_OFFSET,
                ZIP64_END_RECORD_BODY_BYTES - 1,
            )
            gate(ZipFixture(shortRecord)) shouldFailWith BackupArchiveZipGateFailure.INVALID_ZIP64
        }

        test("ZIP64 multi-disk metadata is rejected") {
            val fixture = zip64Fixture("entry")
            val bytes = fixture.bytes.patchedU32(
                fixture.locatorOffset + ZIP64_LOCATOR_TOTAL_DISKS_OFFSET,
                2,
            )

            gate(ZipFixture(bytes)) shouldFailWith BackupArchiveZipGateFailure.MULTI_DISK_ARCHIVE
        }

        test("central-directory spans must use checked in-file bounds") {
            val classic = classicFixture("entry")
            val outside = classic.bytes.patchedU32(
                classic.endRecordOffset + END_CENTRAL_OFFSET_OFFSET,
                classic.endRecordOffset.toLong(),
            )
            gate(ZipFixture(outside)) shouldFailWith BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY

            val zip64 = zip64Fixture("entry")
            val overflowing = zip64.bytes.patchedU64(
                zip64.zip64RecordOffset + ZIP64_END_CENTRAL_OFFSET_OFFSET,
                Long.MAX_VALUE,
            )
            gate(ZipFixture(overflowing)) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
        }

        test("declared entry and central-byte budgets are enforced") {
            val fixture = classicFixture("one", "two")
            gate(fixture, maxEntries = 1) shouldFailWith BackupArchiveZipGateFailure.TOO_MANY_ENTRIES

            gate(
                classicFixture("entry"),
                maxCentralDirectoryBytes = CENTRAL_HEADER_BYTES,
            ) shouldFailWith BackupArchiveZipGateFailure.CENTRAL_DIRECTORY_TOO_LARGE

            gate(classicFixture("n".repeat(TEST_MAX_NAME_BYTES + 1))) shouldFailWith
                BackupArchiveZipGateFailure.CENTRAL_DIRECTORY_TOO_LARGE
            val oneEntry = classicFixture("entry")
            gate(
                ZipFixture(
                    oneEntry.bytes.patchedU16(
                        oneEntry.centralDirectoryOffset + CENTRAL_EXTRA_LENGTH_OFFSET,
                        TEST_MAX_EXTRA_BYTES + 1,
                    ),
                ),
            ) shouldFailWith BackupArchiveZipGateFailure.CENTRAL_DIRECTORY_TOO_LARGE
        }

        test("actual central records cannot hide behind a forged low end-record count") {
            val fixture = classicFixture("one", "two")
            val forged = fixture.bytes
                .patchedU16(fixture.endRecordOffset + END_ENTRIES_ON_DISK_OFFSET, 1)
                .patchedU16(fixture.endRecordOffset + END_TOTAL_ENTRIES_OFFSET, 1)

            gate(ZipFixture(forged), maxEntries = 1) shouldFailWith
                BackupArchiveZipGateFailure.TOO_MANY_ENTRIES
            gate(ZipFixture(forged)) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY

            val hiddenSecondRecord = forged.patchedU32(
                fixture.endRecordOffset + END_CENTRAL_SIZE_OFFSET,
                CENTRAL_HEADER_BYTES + "one".encodeToByteArray().size,
            )
            gate(ZipFixture(hiddenSecondRecord), maxEntries = 1) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
        }

        test("every central record needs an exact bounded CEN header") {
            val fixture = classicFixture("entry")
            val badSignature = fixture.bytes.patchedU32(fixture.centralDirectoryOffset, 0)
            gate(ZipFixture(badSignature)) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY

            val oversizedName = fixture.bytes.patchedU16(
                fixture.centralDirectoryOffset + CENTRAL_NAME_LENGTH_OFFSET,
                0xffff,
            )
            gate(ZipFixture(oversizedName), maxNameBytes = UINT16_MAX) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY

            val wrongCount = fixture.bytes
                .patchedU16(fixture.endRecordOffset + END_ENTRIES_ON_DISK_OFFSET, 2)
                .patchedU16(fixture.endRecordOffset + END_TOTAL_ENTRIES_OFFSET, 2)
            gate(ZipFixture(wrongCount)) shouldFailWith
                BackupArchiveZipGateFailure.INVALID_CENTRAL_DIRECTORY
        }

        test("results never retain archive entry names") {
            val sensitiveName = "private-clipboard-name"
            val valid = gate(classicFixture(sensitiveName))
            valid.toString() shouldNotContain sensitiveName

            val fixture = classicFixture(sensitiveName)
            val invalid = gate(
                ZipFixture(fixture.bytes.patchedU32(fixture.centralDirectoryOffset, 0)),
            )
            invalid.toString() shouldNotContain sensitiveName
        }
    })

private infix fun BackupArchiveZipGateResult.shouldFailWith(expected: BackupArchiveZipGateFailure) {
    (this as BackupArchiveZipGateResult.Invalid).failure shouldBe expected
}

private infix fun BackupArchiveZipGateResult.shouldBeValid(expected: Boolean) {
    (this is BackupArchiveZipGateResult.Valid) shouldBe expected
}

private fun gate(
    fixture: ZipFixture,
    archiveSize: Long = fixture.bytes.size.toLong(),
    maxEntries: Int = TEST_MAX_ENTRIES,
    maxCentralDirectoryBytes: Long = TEST_MAX_CENTRAL_BYTES,
    maxNameBytes: Int = TEST_MAX_NAME_BYTES,
    maxExtraBytes: Int = TEST_MAX_EXTRA_BYTES,
    maxCommentBytes: Int = TEST_MAX_COMMENT_BYTES,
): BackupArchiveZipGateResult = withChannel(fixture.bytes) { channel ->
    BackupArchiveZipGate.inspect(
        channel = channel,
        archiveSize = archiveSize,
        maxEntries = maxEntries,
        maxCentralDirectoryBytes = maxCentralDirectoryBytes,
        maxNameBytes = maxNameBytes,
        maxExtraBytes = maxExtraBytes,
        maxCommentBytes = maxCommentBytes,
    )
}

private fun ZipFixture.patchCentralDisk(
    diskNumber: Int,
    zip64DiskNumber: Long? = null,
    precedingZip64Fields: Boolean = false,
): ZipFixture {
    var patched = bytes.patchedU16(centralDirectoryOffset + CENTRAL_DISK_NUMBER_OFFSET, diskNumber)
    if (zip64DiskNumber == null) return copy(bytes = patched)

    val value = ByteArrayOutputStream().apply {
        if (precedingZip64Fields) {
            repeat(3) { writeU64(0) }
        }
        writeU32(zip64DiskNumber)
    }.toByteArray()
    val extra = ByteArrayOutputStream().apply {
        writeU16(ZIP64_EXTRA_ID)
        writeU16(value.size)
        write(value)
    }.toByteArray()
    if (precedingZip64Fields) {
        patched = patched
            .patchedU32(centralDirectoryOffset + CENTRAL_COMPRESSED_SIZE_OFFSET, UINT32_MAX)
            .patchedU32(centralDirectoryOffset + CENTRAL_UNCOMPRESSED_SIZE_OFFSET, UINT32_MAX)
            .patchedU32(centralDirectoryOffset + CENTRAL_LOCAL_HEADER_OFFSET, UINT32_MAX)
    }
    val extraOffset = centralDirectoryOffset + CENTRAL_HEADER_BYTES.toInt() +
        patched.readU16(centralDirectoryOffset + CENTRAL_NAME_LENGTH_OFFSET)
    val combined = ByteArray(patched.size + extra.size)
    patched.copyInto(combined, endIndex = extraOffset)
    extra.copyInto(combined, destinationOffset = extraOffset)
    patched.copyInto(
        combined,
        destinationOffset = extraOffset + extra.size,
        startIndex = extraOffset,
    )
    val newEndRecordOffset = endRecordOffset + extra.size
    val newCentralBytes = centralDirectoryBytes + extra.size
    val bytesWithLengths = combined
        .patchedU16(centralDirectoryOffset + CENTRAL_EXTRA_LENGTH_OFFSET, extra.size)
        .patchedU32(newEndRecordOffset + END_CENTRAL_SIZE_OFFSET, newCentralBytes)
    return copy(
        bytes = bytesWithLengths,
        centralDirectoryBytes = newCentralBytes,
        endRecordOffset = newEndRecordOffset,
    )
}

private fun ZipFixture.centralExtraOffset(): Int = centralDirectoryOffset + CENTRAL_HEADER_BYTES.toInt() +
    bytes.readU16(centralDirectoryOffset + CENTRAL_NAME_LENGTH_OFFSET)

private inline fun <T> withChannel(bytes: ByteArray, block: (FileChannel) -> T): T {
    val file = Files.createTempFile("backup-zip-gate-", ".zip")
    return try {
        Files.write(file, bytes)
        FileChannel.open(file, StandardOpenOption.READ).use(block)
    } finally {
        Files.deleteIfExists(file)
    }
}

private data class ZipFixture(
    val bytes: ByteArray,
    val centralDirectoryOffset: Int = 0,
    val centralDirectoryBytes: Long = 0,
    val zip64RecordOffset: Int = -1,
    val locatorOffset: Int = -1,
    val endRecordOffset: Int = -1,
)

private fun classicFixture(vararg names: String, comment: ByteArray = byteArrayOf()): ZipFixture {
    val output = ByteArrayOutputStream()
    val central = output.writeEntries(names.toList())
    val endRecordOffset = output.size()
    output.writeEndRecord(
        entries = names.size,
        centralDirectoryBytes = central.bytes.toLong(),
        centralDirectoryOffset = central.offset.toLong(),
        comment = comment,
    )
    return ZipFixture(
        bytes = output.toByteArray(),
        centralDirectoryOffset = central.offset,
        centralDirectoryBytes = central.bytes.toLong(),
        endRecordOffset = endRecordOffset,
    )
}

private fun zip64Fixture(vararg names: String): ZipFixture {
    val output = ByteArrayOutputStream()
    val central = output.writeEntries(names.toList())
    val zip64RecordOffset = output.size()
    output.writeU32(ZIP64_END_RECORD_SIGNATURE)
    output.writeU64(ZIP64_END_RECORD_BODY_BYTES)
    output.writeU16(ZIP64_VERSION)
    output.writeU16(ZIP64_VERSION)
    output.writeU32(0)
    output.writeU32(0)
    output.writeU64(names.size.toLong())
    output.writeU64(names.size.toLong())
    output.writeU64(central.bytes.toLong())
    output.writeU64(central.offset.toLong())
    val locatorOffset = output.size()
    output.writeU32(ZIP64_LOCATOR_SIGNATURE)
    output.writeU32(0)
    output.writeU64(zip64RecordOffset.toLong())
    output.writeU32(1)
    val endRecordOffset = output.size()
    output.writeEndRecord(
        entries = UINT16_MAX,
        centralDirectoryBytes = UINT32_MAX,
        centralDirectoryOffset = UINT32_MAX,
    )
    return ZipFixture(
        bytes = output.toByteArray(),
        centralDirectoryOffset = central.offset,
        centralDirectoryBytes = central.bytes.toLong(),
        zip64RecordOffset = zip64RecordOffset,
        locatorOffset = locatorOffset,
        endRecordOffset = endRecordOffset,
    )
}

private data class CentralDirectory(val offset: Int, val bytes: Int)

private fun ByteArrayOutputStream.writeEntries(names: List<String>): CentralDirectory {
    val localOffsets = names.map { name ->
        val nameBytes = name.encodeToByteArray()
        val localOffset = size()
        writeU32(LOCAL_HEADER_SIGNATURE)
        writeU16(CLASSIC_VERSION)
        writeU16(0)
        writeU16(STORED_METHOD)
        writeU16(0)
        writeU16(0)
        writeU32(0)
        writeU32(0)
        writeU32(0)
        writeU16(nameBytes.size)
        writeU16(0)
        write(nameBytes)
        localOffset
    }
    val centralOffset = size()
    names.forEachIndexed { index, name ->
        val nameBytes = name.encodeToByteArray()
        writeU32(CENTRAL_HEADER_SIGNATURE)
        writeU16(CLASSIC_VERSION)
        writeU16(CLASSIC_VERSION)
        writeU16(0)
        writeU16(STORED_METHOD)
        writeU16(0)
        writeU16(0)
        writeU32(0)
        writeU32(0)
        writeU32(0)
        writeU16(nameBytes.size)
        writeU16(0)
        writeU16(0)
        writeU16(0)
        writeU16(0)
        writeU32(0)
        writeU32(localOffsets[index].toLong())
        write(nameBytes)
    }
    return CentralDirectory(offset = centralOffset, bytes = size() - centralOffset)
}

private fun ByteArrayOutputStream.writeEndRecord(
    entries: Int,
    centralDirectoryBytes: Long,
    centralDirectoryOffset: Long,
    comment: ByteArray = byteArrayOf(),
) {
    writeU32(END_RECORD_SIGNATURE)
    writeU16(0)
    writeU16(0)
    writeU16(entries)
    writeU16(entries)
    writeU32(centralDirectoryBytes)
    writeU32(centralDirectoryOffset)
    writeU16(comment.size)
    write(comment)
}

private fun ByteArrayOutputStream.writeU16(value: Int) {
    repeat(Short.SIZE_BYTES) { index -> write(value ushr (Byte.SIZE_BITS * index)) }
}

private fun ByteArrayOutputStream.writeU32(value: Long) {
    repeat(Int.SIZE_BYTES) { index -> write((value ushr (Byte.SIZE_BITS * index)).toInt()) }
}

private fun ByteArrayOutputStream.writeU64(value: Long) {
    repeat(Long.SIZE_BYTES) { index -> write((value ushr (Byte.SIZE_BITS * index)).toInt()) }
}

private fun ByteArray.patchedU16(offset: Int, value: Int): ByteArray = copyOf().also { bytes ->
    repeat(Short.SIZE_BYTES) { index ->
        bytes[offset + index] = (value ushr (Byte.SIZE_BITS * index)).toByte()
    }
}

private fun ByteArray.readU16(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl Byte.SIZE_BITS)

private fun ByteArray.patchedU32(offset: Int, value: Long): ByteArray = copyOf().also { bytes ->
    repeat(Int.SIZE_BYTES) { index ->
        bytes[offset + index] = (value ushr (Byte.SIZE_BITS * index)).toByte()
    }
}

private fun ByteArray.patchedU64(offset: Int, value: Long): ByteArray = copyOf().also { bytes ->
    repeat(Long.SIZE_BYTES) { index ->
        bytes[offset + index] = (value ushr (Byte.SIZE_BITS * index)).toByte()
    }
}

private const val TEST_MAX_ENTRIES = 10
private const val TEST_MAX_CENTRAL_BYTES = 1_024L
private const val TEST_MAX_NAME_BYTES = 128
private const val TEST_MAX_EXTRA_BYTES = 128
private const val TEST_MAX_COMMENT_BYTES = 128

private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
private const val END_RECORD_SIGNATURE = 0x06054b50L
private const val ZIP64_END_RECORD_SIGNATURE = 0x06064b50L
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L

private const val CLASSIC_VERSION = 20
private const val ZIP64_VERSION = 45
private const val STORED_METHOD = 0
private const val CENTRAL_HEADER_BYTES = 46L
private const val CENTRAL_COMPRESSED_SIZE_OFFSET = 20
private const val CENTRAL_UNCOMPRESSED_SIZE_OFFSET = 24
private const val ZIP64_END_RECORD_BODY_BYTES = 44L
private const val UINT16_MAX = 0xffff
private const val UINT32_MAX = 0xffff_ffffL
private const val ZIP64_EXTRA_ID = 0x0001

private const val END_DISK_NUMBER_OFFSET = 4
private const val END_ENTRIES_ON_DISK_OFFSET = 8
private const val END_TOTAL_ENTRIES_OFFSET = 10
private const val END_CENTRAL_SIZE_OFFSET = 12
private const val END_CENTRAL_OFFSET_OFFSET = 16
private const val CENTRAL_NAME_LENGTH_OFFSET = 28
private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
private const val CENTRAL_DISK_NUMBER_OFFSET = 34
private const val CENTRAL_LOCAL_HEADER_OFFSET = 42
private const val ZIP64_END_RECORD_SIZE_OFFSET = 4
private const val ZIP64_END_CENTRAL_OFFSET_OFFSET = 48
private const val ZIP64_LOCATOR_RECORD_OFFSET = 8
private const val ZIP64_LOCATOR_TOTAL_DISKS_OFFSET = 16
