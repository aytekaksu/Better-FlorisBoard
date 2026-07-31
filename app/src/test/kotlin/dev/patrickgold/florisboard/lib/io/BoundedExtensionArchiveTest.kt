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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.CRC32

class BoundedExtensionArchiveTest :
    FunSpec({
        val root = Files.createTempDirectory("bounded-extension-archive-test")

        afterSpec {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }

        test("a valid archive is published only after every entry verifies") {
            val source = root.resolve("valid.zip")
            writeArchive(
                source,
                ArchiveEntry("theme/"),
                ArchiveEntry("theme/extension.json", """{"id":"example"}""".encodeToByteArray()),
            )
            val destination = Files.createDirectory(root.resolve("valid-output"))

            BoundedExtensionArchive.extract(source, destination)

            readText(destination.resolve("theme/extension.json")) shouldBe """{"id":"example"}"""
            stagingDirectories(root) shouldBe emptyList()
        }

        test("bounded reads validate the whole archive and decode strict UTF-8") {
            val source = root.resolve("read.zip")
            writeArchive(
                source,
                ArchiveEntry("extension.json", "safe".encodeToByteArray()),
                ArchiveEntry("../outside.json", "ignored".encodeToByteArray()),
            )

            val failure = shouldThrow<IllegalStateException> {
                BoundedExtensionArchive.readText(source, "extension.json")
            }

            failure.message shouldBe FAILURE_MESSAGE
            failure.toString() shouldNotContain source.toString()
            failure.toString() shouldNotContain "outside.json"
        }

        test("bounded reads reject malformed UTF-8 without exposing archive content") {
            val source = root.resolve("malformed-text.zip")
            writeArchive(
                source,
                ArchiveEntry(SENSITIVE_ENTRY_NAME, byteArrayOf(0xc3.toByte(), 0x28)),
            )

            val failure = shouldThrow<IllegalStateException> {
                BoundedExtensionArchive.readText(source, SENSITIVE_ENTRY_NAME)
            }

            failure.message shouldBe FAILURE_MESSAGE
            failure.toString() shouldNotContain source.toString()
            failure.toString() shouldNotContain SENSITIVE_ENTRY_NAME
        }

        test("control-file inspection succeeds before one atomic extraction") {
            val source = root.resolve("inspected.zip")
            writeArchive(
                source,
                ArchiveEntry("extension.json", "accepted".encodeToByteArray()),
                ArchiveEntry("asset", "published".encodeToByteArray()),
            )
            val destination = root.resolve("inspected-output")

            val inspected = BoundedExtensionArchive.extractAfterInspectingText(
                source = source,
                destination = destination,
                relativePath = "extension.json",
                maxTextBytes = 8,
            ) { text ->
                text.uppercase()
            }

            inspected shouldBe "ACCEPTED"
            readText(destination.resolve("asset")) shouldBe "published"
            stagingDirectories(root) shouldBe emptyList()
        }

        test("rejected or malformed control text publishes no archive data") {
            val rejected = root.resolve("inspection-rejected.zip")
            writeArchive(
                rejected,
                ArchiveEntry("extension.json", "rejected".encodeToByteArray()),
                ArchiveEntry("asset", "must-not-publish".encodeToByteArray()),
            )
            val malformed = root.resolve("inspection-malformed.zip")
            writeArchive(
                malformed,
                ArchiveEntry("extension.json", byteArrayOf(0xc3.toByte(), 0x28)),
            )

            listOf(rejected, malformed).forEachIndexed { index, source ->
                val destination = root.resolve("inspection-rejected-output-$index")
                shouldThrow<IllegalStateException> {
                    BoundedExtensionArchive.extractAfterInspectingText(
                        source = source,
                        destination = destination,
                        relativePath = "extension.json",
                        maxTextBytes = 16,
                    ) { text ->
                        check(text != "rejected")
                    }
                }
                Files.notExists(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("unsafe requested paths fail without exposing the path") {
            val source = root.resolve("private-source.zip")
            writeArchive(source, ArchiveEntry("extension.json", "safe".encodeToByteArray()))
            val requestedPath = "../private-control-file"

            val failure = shouldThrow<IllegalStateException> {
                BoundedExtensionArchive.readText(source, requestedPath)
            }

            failure.message shouldBe FAILURE_MESSAGE
            failure.toString() shouldNotContain source.toString()
            failure.toString() shouldNotContain requestedPath
        }

        test("archive path syntax and dimensions are bounded") {
            val unsafePaths = listOf(
                "/absolute",
                "C:/drive",
                "parent\\child",
                "./dot",
                "parent/../child",
                "control\nname",
                "double//segment",
            )
            unsafePaths.forEachIndexed { index, path ->
                val source = root.resolve("unsafe-path-$index.zip")
                val writerPath = path.replace('\\', '/')
                writeArchive(source, ArchiveEntry(writerPath, "data".encodeToByteArray()))
                if (writerPath != path) {
                    patchEntryName(source, writerPath, path)
                }
                withClue("unsafe path case $index") {
                    shouldThrow<IllegalStateException> {
                        BoundedExtensionArchive.extract(source, root.resolve("unsafe-output-$index"))
                    }
                }
            }

            val dimensionCases = listOf(
                Triple("ééé", testLimits(maxPathBytes = 5), "path-bytes"),
                Triple("abcdef", testLimits(maxSegmentBytes = 5), "segment-bytes"),
                Triple("a/b/c", testLimits(maxDepth = 2), "depth"),
            )
            dimensionCases.forEach { (path, limits, marker) ->
                val source = root.resolve("$marker.zip")
                writeArchive(source, ArchiveEntry(path, "data".encodeToByteArray()))
                withClue(marker) {
                    shouldThrow<IllegalStateException> {
                        BoundedExtensionArchive.extract(source, root.resolve("$marker-output"), limits)
                    }
                }
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("duplicate and file-directory conflicts are rejected") {
            val duplicate = root.resolve("duplicate.zip")
            writeArchive(
                duplicate,
                ArchiveEntry("same.json", "first".encodeToByteArray()),
                ArchiveEntry("same.json", "second".encodeToByteArray()),
            )
            val conflicting = root.resolve("conflicting.zip")
            writeArchive(
                conflicting,
                ArchiveEntry("container/child.json", "child".encodeToByteArray()),
                ArchiveEntry("container", "file".encodeToByteArray()),
            )

            listOf(duplicate, conflicting).forEachIndexed { index, source ->
                val destination = Files.createDirectory(root.resolve("collision-output-$index"))
                shouldThrow<IllegalStateException> {
                    BoundedExtensionArchive.extract(source, destination)
                }
                Files.list(destination).use { it.iterator().hasNext() } shouldBe false
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("links, encryption, and unsupported compression are rejected") {
            val symbolicLink = root.resolve("symbolic-link.zip")
            writeArchive(
                symbolicLink,
                ArchiveEntry(
                    path = "linked-file",
                    bytes = "target".encodeToByteArray(),
                    unixMode = UnixStat.LINK_FLAG or 0b111_101_101,
                ),
            )
            val unsupported = root.resolve("unsupported-method.zip")
            writeArchive(
                unsupported,
                ArchiveEntry(
                    path = "extension.json",
                    bytes = "data".encodeToByteArray(),
                    stored = true,
                ),
            )
            patchCompressionMethod(unsupported, unsupportedMethod = 12)
            val encrypted = root.resolve("encrypted.zip")
            writeArchive(
                encrypted,
                ArchiveEntry(
                    path = "extension.json",
                    bytes = "data".encodeToByteArray(),
                    stored = true,
                ),
            )
            patchGeneralPurposeFlags(encrypted, addedFlags = 1)

            listOf(symbolicLink, unsupported, encrypted).forEachIndexed { index, source ->
                val destination = root.resolve("unsupported-output-$index")
                shouldThrow<IllegalStateException> {
                    BoundedExtensionArchive.extract(source, destination)
                }
                Files.notExists(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("declared and actual byte budgets are both enforced") {
            val oversized = root.resolve("oversized.zip")
            writeArchive(oversized, ArchiveEntry("large.bin", ByteArray(17) { it.toByte() }))
            val corrupt = root.resolve("corrupt.zip")
            val original = "crc-marker".encodeToByteArray()
            writeArchive(corrupt, ArchiveEntry("stored.bin", original, stored = true))
            mutateFirstPayloadByte(corrupt, original)
            val limits = testLimits(maxExpandedBytes = 16, maxEntryBytes = 16)

            listOf(oversized, corrupt).forEachIndexed { index, source ->
                val destination = Files.createDirectory(root.resolve("budget-output-$index"))
                shouldThrow<IllegalStateException> {
                    BoundedExtensionArchive.extract(source, destination, limits)
                }
                Files.list(destination).use { it.iterator().hasNext() } shouldBe false
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("archive and entry-count limits are enforced before staging") {
            val source = root.resolve("container-limits.zip")
            writeArchive(
                source,
                ArchiveEntry("first", "1".encodeToByteArray()),
                ArchiveEntry("second", "2".encodeToByteArray()),
            )
            val archiveBytes = Files.size(source)

            listOf(
                testLimits(maxEntries = 1),
                testLimits(maxArchiveBytes = archiveBytes - 1),
            ).forEachIndexed { index, limits ->
                val destination = root.resolve("container-limit-output-$index")
                shouldThrow<IllegalStateException> {
                    BoundedExtensionArchive.extract(source, destination, limits)
                }
                Files.notExists(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("trailing data and the authoritative latest end record are rejected") {
            val trailingData = root.resolve("trailing-data.zip")
            writeArchive(trailingData, ArchiveEntry(SENSITIVE_ENTRY_NAME, "data".encodeToByteArray()))
            Files.write(trailingData, Files.readAllBytes(trailingData) + byteArrayOf(0x7f))

            val forgedLatestRecord = root.resolve("forged-latest-record.zip")
            writeArchive(forgedLatestRecord, ArchiveEntry(SENSITIVE_ENTRY_NAME, "data".encodeToByteArray()))
            val originalBytes = Files.readAllBytes(forgedLatestRecord)
            val fakeEndRecord = ByteArray(END_RECORD_BYTES).apply {
                putU32(0, END_RECORD_SIGNATURE)
                putU16(END_DISK_OFFSET, 1)
                putU32(END_CENTRAL_OFFSET_OFFSET, originalBytes.size.toLong())
            }
            Files.write(forgedLatestRecord, originalBytes + fakeEndRecord)

            listOf(trailingData, forgedLatestRecord).forEachIndexed { index, source ->
                assertArchiveRejected(source, root.resolve("trailing-output-$index"))
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("forged low entry counts and central layouts cannot hide records") {
            val underreported = root.resolve("underreported-count.zip")
            writeArchive(
                underreported,
                ArchiveEntry("first", "1".encodeToByteArray()),
                ArchiveEntry(SENSITIVE_ENTRY_NAME, "2".encodeToByteArray()),
            )
            mutateArchive(underreported) { bytes ->
                val endRecord = bytes.endRecordOffset()
                bytes.putU16(endRecord + END_ENTRIES_ON_DISK_OFFSET, 1)
                bytes.putU16(endRecord + END_TOTAL_ENTRIES_OFFSET, 1)
            }

            val shortenedDirectory = root.resolve("shortened-central-directory.zip")
            writeArchive(
                shortenedDirectory,
                ArchiveEntry("first", "1".encodeToByteArray()),
                ArchiveEntry(SENSITIVE_ENTRY_NAME, "2".encodeToByteArray()),
            )
            mutateArchive(shortenedDirectory) { bytes ->
                val endRecord = bytes.endRecordOffset()
                bytes.putU32(
                    endRecord + END_CENTRAL_SIZE_OFFSET,
                    bytes.u32(endRecord + END_CENTRAL_SIZE_OFFSET) - 1,
                )
            }

            listOf(underreported, shortenedDirectory).forEachIndexed { index, source ->
                assertArchiveRejected(source, root.resolve("forged-layout-output-$index"))
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("ZIP64 locators and sentinel fields are rejected") {
            val mutations: List<Pair<String, (ByteArray) -> Unit>> = listOf(
                "locator" to { bytes ->
                    bytes.putU32(
                        bytes.endRecordOffset() - ZIP64_LOCATOR_BYTES,
                        ZIP64_LOCATOR_SIGNATURE,
                    )
                },
                "entry-count" to { bytes ->
                    val endRecord = bytes.endRecordOffset()
                    bytes.putU16(endRecord + END_ENTRIES_ON_DISK_OFFSET, ZIP64_U16_SENTINEL)
                    bytes.putU16(endRecord + END_TOTAL_ENTRIES_OFFSET, ZIP64_U16_SENTINEL)
                },
                "central-size" to { bytes ->
                    bytes.putU32(
                        bytes.endRecordOffset() + END_CENTRAL_SIZE_OFFSET,
                        ZIP64_U32_SENTINEL,
                    )
                },
                "central-offset" to { bytes ->
                    bytes.putU32(
                        bytes.endRecordOffset() + END_CENTRAL_OFFSET_OFFSET,
                        ZIP64_U32_SENTINEL,
                    )
                },
                "compressed-size" to { bytes ->
                    bytes.putU32(
                        bytes.centralHeaderOffset() + CENTRAL_COMPRESSED_SIZE_OFFSET,
                        ZIP64_U32_SENTINEL,
                    )
                },
                "expanded-size" to { bytes ->
                    bytes.putU32(
                        bytes.centralHeaderOffset() + CENTRAL_EXPANDED_SIZE_OFFSET,
                        ZIP64_U32_SENTINEL,
                    )
                },
                "local-offset" to { bytes ->
                    bytes.putU32(
                        bytes.centralHeaderOffset() + CENTRAL_LOCAL_HEADER_OFFSET,
                        ZIP64_U32_SENTINEL,
                    )
                },
            )

            mutations.forEachIndexed { index, (marker, mutation) ->
                val source = root.resolve("zip64-$marker.zip")
                writeArchive(source, ArchiveEntry(SENSITIVE_ENTRY_NAME, "data".encodeToByteArray()))
                mutateArchive(source, mutation)
                assertArchiveRejected(source, root.resolve("zip64-output-$index"))
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("multi-disk metadata is rejected at archive and entry levels") {
            val mutations: List<Pair<String, (ByteArray) -> Unit>> = listOf(
                "end-disk" to { bytes ->
                    bytes.putU16(bytes.endRecordOffset() + END_DISK_OFFSET, 1)
                },
                "central-disk" to { bytes ->
                    bytes.putU16(bytes.endRecordOffset() + END_CENTRAL_DISK_OFFSET, 1)
                },
                "split-count" to { bytes ->
                    bytes.putU16(bytes.endRecordOffset() + END_ENTRIES_ON_DISK_OFFSET, 0)
                },
                "entry-disk" to { bytes ->
                    bytes.putU16(bytes.centralHeaderOffset() + CENTRAL_DISK_START_OFFSET, 1)
                },
            )

            mutations.forEachIndexed { index, (marker, mutation) ->
                val source = root.resolve("multi-disk-$marker.zip")
                writeArchive(source, ArchiveEntry(SENSITIVE_ENTRY_NAME, "data".encodeToByteArray()))
                mutateArchive(source, mutation)
                assertArchiveRejected(source, root.resolve("multi-disk-output-$index"))
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("local headers must match central flags method and name") {
            val mutations: List<Pair<String, (ByteArray) -> Unit>> = listOf(
                "flags" to { bytes ->
                    val localHeader = bytes.localHeaderOffset()
                    bytes.putU16(
                        localHeader + LOCAL_FLAGS_OFFSET,
                        bytes.u16(localHeader + LOCAL_FLAGS_OFFSET) xor UTF8_NAME_FLAG,
                    )
                },
                "method" to { bytes ->
                    val localHeader = bytes.localHeaderOffset()
                    bytes.putU16(
                        localHeader + LOCAL_METHOD_OFFSET,
                        bytes.u16(localHeader + LOCAL_METHOD_OFFSET) xor 1,
                    )
                },
                "name" to { bytes ->
                    val localNameOffset = bytes.localHeaderOffset() + LOCAL_HEADER_BYTES
                    bytes[localNameOffset] = (bytes[localNameOffset].toInt() xor 1).toByte()
                },
            )

            mutations.forEachIndexed { index, (marker, mutation) ->
                val source = root.resolve("header-mismatch-$marker.zip")
                writeArchive(source, ArchiveEntry(SENSITIVE_ENTRY_NAME, "data".encodeToByteArray()))
                mutateArchive(source, mutation)
                assertArchiveRejected(source, root.resolve("header-mismatch-output-$index"))
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("entry extra and comment metadata is bounded and exactly framed") {
            val boundary = root.resolve("metadata-boundary.zip")
            writeArchive(
                boundary,
                ArchiveEntry(
                    path = SENSITIVE_ENTRY_NAME,
                    bytes = "data".encodeToByteArray(),
                    stored = true,
                    extra = framedExtra(MAX_ENTRY_EXTRA_BYTES - EXTRA_FIELD_HEADER_BYTES),
                    comment = "c".repeat(MAX_ENTRY_COMMENT_BYTES),
                ),
            )
            val boundaryOutput = root.resolve("metadata-boundary-output")

            BoundedExtensionArchive.extract(boundary, boundaryOutput)

            readText(boundaryOutput.resolve(SENSITIVE_ENTRY_NAME)) shouldBe "data"

            val mutations: List<Pair<String, (ByteArray) -> Unit>> = listOf(
                "central-extra-length" to { bytes ->
                    bytes.putU16(
                        bytes.centralHeaderOffset() + CENTRAL_EXTRA_LENGTH_OFFSET,
                        MAX_ENTRY_EXTRA_BYTES + 1,
                    )
                },
                "local-extra-length" to { bytes ->
                    bytes.putU16(
                        bytes.localHeaderOffset() + LOCAL_EXTRA_LENGTH_OFFSET,
                        MAX_ENTRY_EXTRA_BYTES + 1,
                    )
                },
                "comment-length" to { bytes ->
                    bytes.putU16(
                        bytes.centralHeaderOffset() + CENTRAL_COMMENT_LENGTH_OFFSET,
                        MAX_ENTRY_COMMENT_BYTES + 1,
                    )
                },
                "malformed-extra" to { bytes ->
                    val centralHeader = bytes.centralHeaderOffset()
                    val extraOffset = centralHeader + CENTRAL_HEADER_BYTES +
                        bytes.u16(centralHeader + CENTRAL_NAME_LENGTH_OFFSET)
                    bytes.putU16(extraOffset + Short.SIZE_BYTES, 1)
                },
                "extra-field-count" to { bytes ->
                    val centralHeader = bytes.centralHeaderOffset()
                    bytes.putU16(
                        centralHeader + CENTRAL_EXTRA_LENGTH_OFFSET,
                        (MAX_ENTRY_EXTRA_FIELDS + 1) * EXTRA_FIELD_HEADER_BYTES,
                    )
                },
            )

            mutations.forEachIndexed { index, (marker, mutation) ->
                val source = root.resolve("metadata-$marker.zip")
                val extra = if (marker == "extra-field-count") {
                    framedExtraFields(MAX_ENTRY_EXTRA_FIELDS + 1)
                } else {
                    framedExtra(0)
                }
                writeArchive(
                    source,
                    ArchiveEntry(
                        path = SENSITIVE_ENTRY_NAME,
                        bytes = "data".encodeToByteArray(),
                        extra = extra,
                    ),
                )
                mutateArchive(source, mutation)
                assertArchiveRejected(source, root.resolve("metadata-output-$index"))
            }
            stagingDirectories(root) shouldBe emptyList()
        }

        test("validated archive facts are charged to one workspace admission") {
            val source = root.resolve("workspace-budget.zip")
            writeArchive(
                source,
                ArchiveEntry("first", "12".encodeToByteArray()),
                ArchiveEntry("second", "3".encodeToByteArray()),
            )
            val budget = ExtensionImportBudget(
                ExtensionImportBudget.Limits(
                    maxInputs = 1,
                    maxSourceBytes = 1_024,
                    maxExpandedBytes = 3,
                    maxEntries = 2,
                ),
            )

            budget.beginInput().use { input ->
                input.addSourceBytes(Files.size(source).toInt())
                BoundedExtensionArchive.extract(
                    source = source,
                    destination = root.resolve("workspace-budget-output"),
                    admission = input,
                )
                input.commit()
            }

            budget.usage().apply {
                inputs shouldBe 1
                sourceBytes shouldBe Files.size(source)
                expandedBytes shouldBe 3
                entries shouldBe 2
            }
        }

        test("an aggregate archive rejection leaves no output or reservation") {
            val source = root.resolve("workspace-budget-rejected.zip")
            writeArchive(source, ArchiveEntry("entry", "1234".encodeToByteArray()))
            val budget = ExtensionImportBudget(
                ExtensionImportBudget.Limits(
                    maxInputs = 1,
                    maxSourceBytes = 1_024,
                    maxExpandedBytes = 3,
                    maxEntries = 1,
                ),
            )
            val destination = root.resolve("workspace-budget-rejected-output")

            shouldThrow<ExtensionImportLimitException> {
                budget.beginInput().use { input ->
                    input.addSourceBytes(Files.size(source).toInt())
                    BoundedExtensionArchive.extract(source, destination, admission = input)
                    input.commit()
                }
            }

            Files.notExists(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
            budget.usage().apply {
                inputs shouldBe 0
                sourceBytes shouldBe 0
                expandedBytes shouldBe 0
                entries shouldBe 0
            }
        }

        test("entry reads enforce their separate control-file budget") {
            val source = root.resolve("read-limit.zip")
            writeArchive(source, ArchiveEntry("extension.json", "12345".encodeToByteArray()))

            shouldThrow<IllegalStateException> {
                BoundedExtensionArchive.readText(
                    source,
                    "extension.json",
                    testLimits(maxReadBytes = 4),
                )
            }
        }

        test("a failed trusted copy leaves the empty destination and no staging tree") {
            val destination = Files.createDirectory(root.resolve("trusted-output"))

            shouldThrow<IllegalStateException> {
                BoundedExtensionArchive.publishTrustedDirectory(destination) { staging ->
                    writeText(staging.resolve("partial"), "partial")
                    error("private-copy-failure")
                }
            }

            Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
            Files.list(destination).use { it.iterator().hasNext() } shouldBe false
            stagingDirectories(root) shouldBe emptyList()
        }

        test("interruption cancels publication and cleans staged data") {
            val destination = Files.createDirectory(root.resolve("interrupted-output"))

            shouldThrow<InterruptedException> {
                BoundedExtensionArchive.publishTrustedDirectory(destination) { staging ->
                    writeText(staging.resolve("partial"), "partial")
                    Thread.currentThread().interrupt()
                }
            }

            Thread.currentThread().isInterrupted shouldBe false
            Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
            Files.list(destination).use { it.iterator().hasNext() } shouldBe false
            stagingDirectories(root) shouldBe emptyList()
        }

        test("an existing populated destination is preserved") {
            val source = root.resolve("preserve.zip")
            writeArchive(source, ArchiveEntry("replacement", "new".encodeToByteArray()))
            val destination = Files.createDirectory(root.resolve("preserved-output"))
            val existing = writeText(destination.resolve("existing"), "keep")

            shouldThrow<IllegalStateException> {
                BoundedExtensionArchive.extract(source, destination)
            }

            readText(existing) shouldBe "keep"
            Files.notExists(destination.resolve("replacement"), LinkOption.NOFOLLOW_LINKS) shouldBe true
            stagingDirectories(root) shouldBe emptyList()
        }
    })

private class ArchiveEntry(
    val path: String,
    val bytes: ByteArray = byteArrayOf(),
    val unixMode: Int? = null,
    val stored: Boolean = false,
    val extra: ByteArray? = null,
    val comment: String? = null,
)

private fun writeText(path: Path, text: String): Path = Files.write(path, text.encodeToByteArray())

private fun readText(path: Path): String = Files.readAllBytes(path).decodeToString()

private fun writeArchive(destination: Path, vararg entries: ArchiveEntry) {
    ZipArchiveOutputStream(destination.toFile()).use { output ->
        entries.forEach { source ->
            val entry = ZipArchiveEntry(source.path)
            source.unixMode?.let { entry.unixMode = it }
            source.extra?.let { entry.extra = it }
            source.comment?.let { entry.comment = it }
            if (source.stored) {
                val crc = CRC32().apply { update(source.bytes) }
                entry.method = ZipMethod.STORED.code
                entry.size = source.bytes.size.toLong()
                entry.compressedSize = source.bytes.size.toLong()
                entry.crc = crc.value
            }
            output.putArchiveEntry(entry)
            output.write(source.bytes)
            output.closeArchiveEntry()
        }
        output.finish()
    }
}

private fun assertArchiveRejected(source: Path, destination: Path) {
    val failure = shouldThrow<IllegalStateException> {
        BoundedExtensionArchive.extract(source, destination)
    }

    failure.message shouldBe FAILURE_MESSAGE
    failure.toString() shouldNotContain source.toString()
    failure.toString() shouldNotContain SENSITIVE_ENTRY_NAME
    Files.notExists(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
}

private fun mutateArchive(archive: Path, mutation: (ByteArray) -> Unit) {
    val bytes = Files.readAllBytes(archive)
    mutation(bytes)
    Files.write(archive, bytes)
}

private fun ByteArray.endRecordOffset(): Int = lastSignatureOffset(END_RECORD_SIGNATURE)

private fun ByteArray.centralHeaderOffset(): Int = u32(endRecordOffset() + END_CENTRAL_OFFSET_OFFSET).toInt()

private fun ByteArray.localHeaderOffset(): Int = u32(centralHeaderOffset() + CENTRAL_LOCAL_HEADER_OFFSET).toInt()

private fun ByteArray.lastSignatureOffset(signature: Long): Int {
    for (offset in size - Int.SIZE_BYTES downTo 0) {
        if (u32(offset) == signature) return offset
    }
    error("Required fixture record is missing")
}

private fun framedExtra(dataBytes: Int): ByteArray = ByteArray(EXTRA_FIELD_HEADER_BYTES + dataBytes).apply {
    putU16(0, TEST_EXTRA_FIELD_ID)
    putU16(Short.SIZE_BYTES, dataBytes)
}

private fun framedExtraFields(fieldCount: Int): ByteArray = ByteArray(fieldCount * EXTRA_FIELD_HEADER_BYTES).apply {
    repeat(fieldCount) { index ->
        val offset = index * EXTRA_FIELD_HEADER_BYTES
        putU16(offset, TEST_EXTRA_FIELD_ID + index)
        putU16(offset + Short.SIZE_BYTES, 0)
    }
}

private fun mutateFirstPayloadByte(archive: Path, payload: ByteArray) {
    val bytes = Files.readAllBytes(archive)
    val offset = bytes.indexOf(payload)
    check(offset >= 0)
    bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
    Files.write(archive, bytes)
}

private fun patchCompressionMethod(archive: Path, unsupportedMethod: Int) {
    val bytes = Files.readAllBytes(archive)
    var patchedHeaders = 0
    for (offset in 0..bytes.size - Int.SIZE_BYTES) {
        when (bytes.u32(offset)) {
            LOCAL_FILE_HEADER_SIGNATURE -> {
                bytes.putU16(offset + LOCAL_METHOD_OFFSET, unsupportedMethod)
                patchedHeaders++
            }

            CENTRAL_FILE_HEADER_SIGNATURE -> {
                bytes.putU16(offset + CENTRAL_METHOD_OFFSET, unsupportedMethod)
                patchedHeaders++
            }
        }
    }
    check(patchedHeaders == 2)
    Files.write(archive, bytes)
}

private fun patchGeneralPurposeFlags(archive: Path, addedFlags: Int) {
    val bytes = Files.readAllBytes(archive)
    var patchedHeaders = 0
    for (offset in 0..bytes.size - Int.SIZE_BYTES) {
        when (bytes.u32(offset)) {
            LOCAL_FILE_HEADER_SIGNATURE -> {
                bytes.orU16(offset + LOCAL_FLAGS_OFFSET, addedFlags)
                patchedHeaders++
            }

            CENTRAL_FILE_HEADER_SIGNATURE -> {
                bytes.orU16(offset + CENTRAL_FLAGS_OFFSET, addedFlags)
                patchedHeaders++
            }
        }
    }
    check(patchedHeaders == 2)
    Files.write(archive, bytes)
}

private fun patchEntryName(archive: Path, current: String, replacement: String) {
    val currentBytes = current.encodeToByteArray()
    val replacementBytes = replacement.encodeToByteArray()
    check(currentBytes.size == replacementBytes.size)
    val bytes = Files.readAllBytes(archive)
    var patchedNames = 0
    for (offset in 0..bytes.size - currentBytes.size) {
        if (currentBytes.indices.all { index -> bytes[offset + index] == currentBytes[index] }) {
            replacementBytes.copyInto(bytes, offset)
            patchedNames++
        }
    }
    check(patchedNames == 2)
    Files.write(archive, bytes)
}

private fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    for (offset in 0..size - needle.size) {
        if (needle.indices.all { index -> this[offset + index] == needle[index] }) return offset
    }
    return -1
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xff) or
    ((this[offset + 1].toInt() and 0xff) shl Byte.SIZE_BITS)

private fun ByteArray.u32(offset: Int): Long = (this[offset].toLong() and 0xffL) or
    ((this[offset + 1].toLong() and 0xffL) shl 8) or
    ((this[offset + 2].toLong() and 0xffL) shl 16) or
    ((this[offset + 3].toLong() and 0xffL) shl 24)

private fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

private fun ByteArray.putU32(offset: Int, value: Long) {
    repeat(Int.SIZE_BYTES) { index ->
        this[offset + index] = (value ushr (Byte.SIZE_BITS * index)).toByte()
    }
}

private fun ByteArray.orU16(offset: Int, value: Int) {
    val current = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    putU16(offset, current or value)
}

private fun stagingDirectories(root: Path): List<Path> = buildList {
    Files.list(root).use { children ->
        children.forEach { child ->
            if (child.fileName.toString().startsWith(".extension-stage-")) add(child)
        }
    }
}

private fun testLimits(
    maxArchiveBytes: Long = 1L shl 20,
    maxEntries: Int = 32,
    maxExpandedBytes: Long = 1L shl 10,
    maxEntryBytes: Long = 1L shl 10,
    maxReadBytes: Long = 1L shl 10,
    maxPathBytes: Int = 128,
    maxSegmentBytes: Int = 64,
    maxDepth: Int = 8,
): BoundedExtensionArchive.Limits = BoundedExtensionArchive.Limits(
    maxArchiveBytes = maxArchiveBytes,
    maxEntries = maxEntries,
    maxExpandedBytes = maxExpandedBytes,
    maxEntryBytes = maxEntryBytes,
    maxReadBytes = maxReadBytes,
    maxPathBytes = maxPathBytes,
    maxSegmentBytes = maxSegmentBytes,
    maxDepth = maxDepth,
)

private const val FAILURE_MESSAGE = "Extension data is invalid or exceeds safety limits."
private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
private const val CENTRAL_FILE_HEADER_SIGNATURE = 0x02014b50L
private const val END_RECORD_SIGNATURE = 0x06054b50L
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
private const val ZIP64_U16_SENTINEL = 0xffff
private const val ZIP64_U32_SENTINEL = 0xffff_ffffL
private const val ZIP64_LOCATOR_BYTES = 20
private const val END_RECORD_BYTES = 22
private const val LOCAL_HEADER_BYTES = 30
private const val CENTRAL_HEADER_BYTES = 46
private const val END_DISK_OFFSET = 4
private const val END_CENTRAL_DISK_OFFSET = 6
private const val END_ENTRIES_ON_DISK_OFFSET = 8
private const val END_TOTAL_ENTRIES_OFFSET = 10
private const val END_CENTRAL_SIZE_OFFSET = 12
private const val END_CENTRAL_OFFSET_OFFSET = 16
private const val LOCAL_METHOD_OFFSET = 8
private const val CENTRAL_METHOD_OFFSET = 10
private const val LOCAL_FLAGS_OFFSET = 6
private const val CENTRAL_FLAGS_OFFSET = 8
private const val CENTRAL_COMPRESSED_SIZE_OFFSET = 20
private const val CENTRAL_EXPANDED_SIZE_OFFSET = 24
private const val CENTRAL_NAME_LENGTH_OFFSET = 28
private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
private const val CENTRAL_DISK_START_OFFSET = 34
private const val CENTRAL_LOCAL_HEADER_OFFSET = 42
private const val LOCAL_EXTRA_LENGTH_OFFSET = 28
private const val UTF8_NAME_FLAG = 0x0800
private const val MAX_ENTRY_EXTRA_BYTES = 8 * 1_024
private const val MAX_ENTRY_COMMENT_BYTES = 1 * 1_024
private const val EXTRA_FIELD_HEADER_BYTES = 4
private const val MAX_ENTRY_EXTRA_FIELDS = 64
private const val TEST_EXTRA_FIELD_ID = 0xb000
private const val SENSITIVE_ENTRY_NAME = "private-extension-control"
