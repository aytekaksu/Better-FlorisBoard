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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveSessionTest :
    FunSpec({
        val root = Files.createTempDirectory("backup-archive-session")

        afterSpec {
            root.toFile().deleteRecursively()
        }

        test("valid legacy sessions retain exact entries and create plans") {
            val snapshot = root.snapshot(
                "legacy.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to "preferences".encodeToByteArray(),
            )

            BackupArchiveSession.open(snapshot).validSession().use { session ->
                session.archive.source shouldBe ArchiveSource.LEGACY
                session.archive.availableComponents shouldBe setOf(BackupComponent.PREFERENCES)
                val plan = session.createPlan(
                    RestoreRequest(RestoreMode.MERGE, setOf(BackupComponent.PREFERENCES)),
                ).validPlan()
                session.owns(plan) shouldBe true
                val entry = plan.componentsToStage.single().entries.single()
                session.withEntry(entry) { _, zipEntry -> zipEntry.name } shouldBe
                    BackupArchive.PREFERENCES_PATH
            }
        }

        test("valid declared manifests are decoded from their retained entry") {
            val snapshot = root.snapshot(
                "manifest.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.MANIFEST_JSON_NAME to manifestJson(BackupComponent.PREFERENCES),
                BackupArchive.PREFERENCES_PATH to "preferences".encodeToByteArray(),
            )

            BackupArchiveSession.open(snapshot).validSession().use { session ->
                session.archive.source shouldBe ArchiveSource.DECLARED
                session.archive.availableComponents shouldBe setOf(BackupComponent.PREFERENCES)
            }
        }

        test("valid explicit empty component roots remain restorable") {
            val snapshot = root.snapshot(
                "empty-keyboard-root.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.MANIFEST_JSON_NAME to manifestJson(BackupComponent.KEYBOARD_EXTENSIONS),
                "${BackupArchive.KEYBOARD_ROOT}/" to byteArrayOf(),
            )

            BackupArchiveSession.open(snapshot).validSession().use { session ->
                session.archive.availableComponents shouldBe setOf(BackupComponent.KEYBOARD_EXTENSIONS)
                val plan = session.createPlan(
                    RestoreRequest(
                        RestoreMode.REPLACE_SELECTED,
                        setOf(BackupComponent.KEYBOARD_EXTENSIONS),
                    ),
                ).validPlan()
                session.owns(plan) shouldBe true
                plan.componentsToStage.single().entries.single().kind shouldBe ArchiveEntryKind.DIRECTORY
            }
        }

        test("empty component roots require an intact local file header") {
            val snapshot = root.snapshot(
                "broken-empty-keyboard-root.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.MANIFEST_JSON_NAME to manifestJson(BackupComponent.KEYBOARD_EXTENSIONS),
                "${BackupArchive.KEYBOARD_ROOT}/" to byteArrayOf(),
            ).patchLocal("${BackupArchive.KEYBOARD_ROOT}/") { bytes, offset ->
                bytes.putU32(offset + LOCAL_SIGNATURE_OFFSET, 0)
            }

            BackupArchiveSession.open(snapshot) shouldRejectArchiveWith ArchiveFailure.INVALID_ENTRY
        }

        test("entry and plan provenance uses identity rather than matching paths") {
            val snapshot = root.snapshot(
                "provenance.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to "preferences".encodeToByteArray(),
            )
            val first = BackupArchiveSession.open(snapshot).validSession()
            val second = BackupArchiveSession.open(snapshot).validSession()
            try {
                val firstPlan = first.createPlan(
                    RestoreRequest(RestoreMode.MERGE, setOf(BackupComponent.PREFERENCES)),
                ).validPlan()
                val firstEntry = firstPlan.componentsToStage.single().entries.single()

                first.owns(firstPlan) shouldBe true
                second.owns(firstPlan) shouldBe false
                second.withEntry(firstEntry) { _, entry -> entry.name } shouldBe null
            } finally {
                first.close()
                second.close()
            }
        }

        test("malformed UTF-8 and malformed JSON fail as typed archive rejections") {
            val malformedUtf8 = root.snapshot(
                "malformed-utf8.zip",
                BackupArchive.METADATA_JSON_NAME to byteArrayOf(0xc3.toByte(), 0x28),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            )
            BackupArchiveSession.open(malformedUtf8) shouldRejectArchiveWith ArchiveFailure.INVALID_METADATA

            val malformedManifest = root.snapshot(
                "malformed-manifest.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.MANIFEST_JSON_NAME to "{invalid".encodeToByteArray(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            )
            BackupArchiveSession.open(malformedManifest) shouldRejectArchiveWith ArchiveFailure.INVALID_MANIFEST
        }

        test("encrypted and unsupported compression entries fail before control decoding") {
            val encrypted = root.snapshot(
                "encrypted.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            ).patchCentral(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                bytes.putU16(offset + CENTRAL_FLAGS_OFFSET, bytes.u16(offset + CENTRAL_FLAGS_OFFSET) or 1)
            }
            BackupArchiveSession.open(encrypted) shouldRejectArchiveWith ArchiveFailure.UNSUPPORTED_ENTRY

            val unsupported = root.snapshot(
                "unsupported-method.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            ).patchCentral(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                bytes.putU16(offset + CENTRAL_METHOD_OFFSET, 12)
            }
            BackupArchiveSession.open(unsupported) shouldRejectArchiveWith ArchiveFailure.UNSUPPORTED_ENTRY
        }

        test("local headers must match retained central directory facts and bounds") {
            fun snapshot(name: String) = root.snapshot(
                name,
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            )

            val methodMismatch = snapshot("local-method.zip")
                .patchLocal(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                    bytes.putU16(offset + LOCAL_METHOD_OFFSET, ZipEntry.STORED)
                }
            BackupArchiveSession.open(methodMismatch) shouldRejectArchiveWith ArchiveFailure.INVALID_ENTRY

            val flagMismatch = snapshot("local-flags.zip")
                .patchLocal(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                    bytes.putU16(
                        offset + LOCAL_FLAGS_OFFSET,
                        bytes.u16(offset + LOCAL_FLAGS_OFFSET) or LOCAL_ENCRYPTION_FLAG,
                    )
                }
            BackupArchiveSession.open(flagMismatch) shouldRejectArchiveWith ArchiveFailure.INVALID_ENTRY

            val unsupportedFlags = snapshot("unsupported-local-flags.zip")
                .patchCentral(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                    bytes.putU16(
                        offset + CENTRAL_FLAGS_OFFSET,
                        bytes.u16(offset + CENTRAL_FLAGS_OFFSET) or PATCHED_DATA_FLAG,
                    )
                }.patchLocal(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                    bytes.putU16(
                        offset + LOCAL_FLAGS_OFFSET,
                        bytes.u16(offset + LOCAL_FLAGS_OFFSET) or PATCHED_DATA_FLAG,
                    )
                }
            BackupArchiveSession.open(unsupportedFlags) shouldRejectArchiveWith ArchiveFailure.INVALID_ENTRY

            val nameMismatch = snapshot("local-name.zip")
                .patchLocal(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                    bytes[offset + LOCAL_HEADER_BYTES] = 'x'.code.toByte()
                }
            BackupArchiveSession.open(nameMismatch) shouldRejectArchiveWith ArchiveFailure.INVALID_ENTRY

            val boundsMismatch = snapshot("local-bounds.zip")
                .patchLocal(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                    bytes.putU16(offset + LOCAL_EXTRA_LENGTH_OFFSET, UShort.MAX_VALUE.toInt())
                }
            BackupArchiveSession.open(boundsMismatch) shouldRejectArchiveWith ArchiveFailure.INVALID_ENTRY
        }

        test("data descriptor local sizes are not trusted") {
            val snapshot = root.snapshot(
                "descriptor-local-sizes.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            ).patchLocal(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                check(bytes.u16(offset + LOCAL_FLAGS_OFFSET) and DATA_DESCRIPTOR_FLAG != 0)
                bytes.putU32(offset + LOCAL_COMPRESSED_SIZE_OFFSET, UInt.MAX_VALUE.toLong())
                bytes.putU32(offset + LOCAL_UNCOMPRESSED_SIZE_OFFSET, UInt.MAX_VALUE.toLong())
            }

            BackupArchiveSession.open(snapshot).validSession().close()
        }

        test("Unix links and special files fail while absent type bits use compatible fallback") {
            val symlink = root.snapshot(
                "symlink.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            ).withUnixMode(BackupArchive.PREFERENCES_PATH, 0xa1ff)
            BackupArchiveSession.open(symlink) shouldRejectArchiveWith ArchiveFailure.UNSUPPORTED_ENTRY

            val special = root.snapshot(
                "special.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            ).withUnixMode(BackupArchive.PREFERENCES_PATH, 0x11a4)
            BackupArchiveSession.open(special) shouldRejectArchiveWith ArchiveFailure.UNSUPPORTED_ENTRY

            val fallback = root.snapshot(
                "unix-fallback.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            ).withUnixMode(BackupArchive.PREFERENCES_PATH, 0)
            BackupArchiveSession.open(fallback).validSession().use { session ->
                session.archive.availableComponents shouldBe setOf(BackupComponent.PREFERENCES)
            }
        }

        test("snapshot size and control CRC must match the exact captured bytes") {
            val regular = root.snapshot(
                "size.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            )
            val wrongSize = ArchiveSnapshot(regular.path, regular.size + 1)
            BackupArchiveSession.open(wrongSize) shouldBe BackupArchiveSessionResult.Invalid(
                BackupArchiveSessionFailure.SnapshotSizeMismatch,
            )

            val wrongCrc = root.snapshot(
                "crc.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            ).patchCentral(BackupArchive.METADATA_JSON_NAME) { bytes, offset ->
                bytes.putU32(offset + CENTRAL_CRC_OFFSET, 0)
            }
            BackupArchiveSession.open(wrongCrc) shouldRejectArchiveWith ArchiveFailure.INVALID_METADATA
        }

        test("ZIP gate failures remain typed and content-free") {
            val marker = "private-provider-marker"
            val file = root.resolve("$marker.zip")
            Files.write(file, byteArrayOf(1, 2, 3))
            val result = BackupArchiveSession.open(ArchiveSnapshot(file, Files.size(file)))

            result shouldBe BackupArchiveSessionResult.Invalid(
                BackupArchiveSessionFailure.ZipGateRejected(
                    BackupArchiveZipGateFailure.END_RECORD_NOT_FOUND,
                ),
            )
            result.toString() shouldNotContain marker
            result.toString() shouldNotContain file.toString()
        }

        test("close is idempotent and removes all exact-entry authority") {
            val snapshot = root.snapshot(
                "close.zip",
                BackupArchive.METADATA_JSON_NAME to metadataJson(),
                BackupArchive.PREFERENCES_PATH to byteArrayOf(1),
            )
            val session = BackupArchiveSession.open(snapshot).validSession()
            val plan = session.createPlan(
                RestoreRequest(RestoreMode.REPLACE_SELECTED, setOf(BackupComponent.PREFERENCES)),
            ).validPlan()
            val entry = plan.componentsToStage.single().entries.single()

            session.close()
            session.close()

            session.owns(plan) shouldBe false
            session.withEntry(entry) { _, zipEntry -> zipEntry.name } shouldBe null
            session.toString() shouldBe "BackupArchiveSession(componentCount=1, closed=true)"
            session.toString() shouldNotContain snapshot.path.toString()
        }
    })

private fun BackupArchiveSessionResult.validSession(): BackupArchiveSession =
    (this as BackupArchiveSessionResult.Valid).session

private fun RestorePlanResult.validPlan(): RestorePlan = (this as RestorePlanResult.Valid).plan

private infix fun BackupArchiveSessionResult.shouldRejectArchiveWith(failure: ArchiveFailure) {
    this shouldBe BackupArchiveSessionResult.Invalid(
        BackupArchiveSessionFailure.ArchiveRejected(failure),
    )
}

private fun Path.snapshot(name: String, vararg entries: Pair<String, ByteArray>): ArchiveSnapshot {
    val file = resolve(name)
    ZipOutputStream(Files.newOutputStream(file)).use { zip ->
        entries.forEach { (entryName, contents) ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(contents)
            zip.closeEntry()
        }
    }
    return ArchiveSnapshot(file, Files.size(file))
}

private fun ArchiveSnapshot.withUnixMode(entryName: String, mode: Int): ArchiveSnapshot =
    patchCentral(entryName) { bytes, offset ->
        bytes[offset + CENTRAL_VERSION_MADE_BY_OFFSET + 1] = UNIX_PLATFORM.toByte()
        bytes.putU32(offset + CENTRAL_EXTERNAL_ATTRIBUTES_OFFSET, mode.toLong() shl 16)
    }

private fun ArchiveSnapshot.patchCentral(
    entryName: String,
    patch: (bytes: ByteArray, offset: Int) -> Unit,
): ArchiveSnapshot {
    val bytes = Files.readAllBytes(path)
    val offset = bytes.findCentralEntry(entryName)
    patch(bytes, offset)
    Files.write(path, bytes)
    return ArchiveSnapshot(path, Files.size(path))
}

private fun ArchiveSnapshot.patchLocal(
    entryName: String,
    patch: (bytes: ByteArray, offset: Int) -> Unit,
): ArchiveSnapshot {
    val bytes = Files.readAllBytes(path)
    val centralOffset = bytes.findCentralEntry(entryName)
    val localOffset = bytes.u32(centralOffset + CENTRAL_LOCAL_HEADER_OFFSET)
    check(localOffset <= Int.MAX_VALUE.toLong()) { "Fixture local header is too large." }
    patch(bytes, localOffset.toInt())
    Files.write(path, bytes)
    return ArchiveSnapshot(path, Files.size(path))
}

private fun ByteArray.findCentralEntry(entryName: String): Int {
    var offset = findSignature(CENTRAL_SIGNATURE)
    while (offset >= 0 && u32(offset) == CENTRAL_SIGNATURE) {
        val nameBytes = u16(offset + CENTRAL_NAME_LENGTH_OFFSET)
        val extraBytes = u16(offset + CENTRAL_EXTRA_LENGTH_OFFSET)
        val commentBytes = u16(offset + CENTRAL_COMMENT_LENGTH_OFFSET)
        val actualName = String(
            this,
            offset + CENTRAL_HEADER_BYTES,
            nameBytes,
            StandardCharsets.UTF_8,
        )
        if (actualName == entryName) {
            return offset
        }
        offset += CENTRAL_HEADER_BYTES + nameBytes + extraBytes + commentBytes
    }
    error("Missing fixture entry.")
}

private fun ByteArray.findSignature(signature: Long): Int {
    for (index in 0..size - Int.SIZE_BYTES) {
        if (u32(index) == signature) return index
    }
    return -1
}

private fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xff) or
    ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

private fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

private fun ByteArray.putU32(offset: Int, value: Long) {
    putU16(offset, value.toInt())
    putU16(offset + 2, (value ushr 16).toInt())
}

private fun metadataJson(): ByteArray =
    """{"package":"dev.patrickgold.florisboard","versionCode":64,"versionName":"test","timestamp":1}"""
        .encodeToByteArray()

private fun manifestJson(vararg components: BackupComponent): ByteArray =
    """{"formatVersion":1,"components":[${components.joinToString { "\"${it.wireId}\"" }}]}"""
        .encodeToByteArray()

private const val CENTRAL_SIGNATURE = 0x02014b50L
private const val CENTRAL_HEADER_BYTES = 46
private const val CENTRAL_VERSION_MADE_BY_OFFSET = 4
private const val CENTRAL_FLAGS_OFFSET = 8
private const val CENTRAL_METHOD_OFFSET = 10
private const val CENTRAL_CRC_OFFSET = 16
private const val CENTRAL_NAME_LENGTH_OFFSET = 28
private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
private const val CENTRAL_EXTERNAL_ATTRIBUTES_OFFSET = 38
private const val CENTRAL_LOCAL_HEADER_OFFSET = 42
private const val UNIX_PLATFORM = 3

private const val LOCAL_SIGNATURE_OFFSET = 0
private const val LOCAL_FLAGS_OFFSET = 6
private const val LOCAL_METHOD_OFFSET = 8
private const val LOCAL_COMPRESSED_SIZE_OFFSET = 18
private const val LOCAL_UNCOMPRESSED_SIZE_OFFSET = 22
private const val LOCAL_EXTRA_LENGTH_OFFSET = 28
private const val LOCAL_HEADER_BYTES = 30
private const val LOCAL_ENCRYPTION_FLAG = 1
private const val PATCHED_DATA_FLAG = 1 shl 5
private const val DATA_DESCRIPTOR_FLAG = 1 shl 3
