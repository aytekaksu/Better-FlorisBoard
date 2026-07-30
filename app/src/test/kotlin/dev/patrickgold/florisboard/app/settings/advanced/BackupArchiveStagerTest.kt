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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveStagerTest :
    FunSpec({
        val root = Files.createTempDirectory("backup-archive-stager")

        afterSpec {
            root.toFile().deleteRecursively()
        }

        test("stages only selected files and preserves an explicit empty component") {
            val preferences = "selected preferences".encodeToByteArray()
            val snapshot = root.snapshot(
                "selected-only.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, preferences),
                fixtureDirectory(BackupArchive.KEYBOARD_ROOT),
                fixtureFile("${BackupArchive.THEME_ROOT}/theme.flex", "unselected".encodeToByteArray()),
            )
            val stagingParent = root.stagingParent("selected-only")

            snapshot.validSession().use { session ->
                val plan = session.validPlan(
                    RestoreMode.REPLACE_SELECTED,
                    BackupComponent.PREFERENCES,
                    BackupComponent.KEYBOARD_EXTENSIONS,
                )
                val staged = session.stageValid(plan, stagingParent)
                try {
                    Files.readAllBytes(staged.root.resolve(BackupArchive.PREFERENCES_PATH))
                        .contentEquals(preferences) shouldBe true
                    Files.isDirectory(staged.root.resolve(BackupArchive.KEYBOARD_ROOT)) shouldBe true
                    Files.notExists(staged.root.resolve(BackupArchive.THEME_ROOT)) shouldBe true
                    staged.entryCount shouldBe 2
                } finally {
                    staged.close()
                }
            }
        }

        test("creates safe implicit parents for nested selected files") {
            val keyboardPath = "${BackupArchive.KEYBOARD_ROOT}/nested/layout.flex"
            val contents = "keyboard".encodeToByteArray()
            val snapshot = root.snapshot(
                "implicit-parents.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(keyboardPath, contents),
            )
            val stagingParent = root.stagingParent("implicit-parents")

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.KEYBOARD_EXTENSIONS)
                val staged = session.stageValid(plan, stagingParent)
                try {
                    Files.readAllBytes(staged.root.resolve(keyboardPath)).contentEquals(contents) shouldBe true
                } finally {
                    staged.close()
                }
            }
        }

        test("rejects a foreign-session plan before creating a staging directory") {
            val snapshot = root.snapshot(
                "foreign-plan.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, byteArrayOf(1)),
            )
            val stagingParent = root.stagingParent("foreign-plan")
            val first = snapshot.validSession()
            val second = snapshot.validSession()
            try {
                val foreignPlan = first.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)

                BackupArchiveStager.stage(
                    session = second,
                    plan = foreignPlan,
                    stagingParent = stagingParent,
                    budget = testBudget(),
                ) shouldBe BackupArchiveStagingResult.Invalid(
                    BackupArchiveStagingFailure.PLAN_SESSION_MISMATCH,
                )
                stagingParent.childCount() shouldBe 0L
            } finally {
                first.close()
                second.close()
            }
        }

        test("enforces the declared payload at the runtime budget boundary") {
            val preferences = byteArrayOf(1, 2, 3, 4)
            val snapshot = root.snapshot(
                "budget.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, preferences),
            )

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                plan.declaredPayloadBytes shouldBe preferences.size.toLong()

                val exactParent = root.stagingParent("budget-exact")
                session.stageValid(
                    plan = plan,
                    stagingParent = exactParent,
                    budget = testBudget(preferences.size.toLong()),
                ).close()
                exactParent.childCount() shouldBe 0L

                val belowParent = root.stagingParent("budget-below")
                BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = belowParent,
                    budget = testBudget(preferences.size - 1L),
                ) shouldBe BackupArchiveStagingResult.Invalid(
                    BackupArchiveStagingFailure.PAYLOAD_BUDGET_EXCEEDED,
                )
                belowParent.childCount() shouldBe 0L

                val invalidParent = root.stagingParent("budget-invalid")
                BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = invalidParent,
                    budget = testBudget(-1L),
                ) shouldBe BackupArchiveStagingResult.Invalid(
                    BackupArchiveStagingFailure.INVALID_BUDGET,
                )
                invalidParent.childCount() shouldBe 0L
            }
        }

        test("rejects runtime checksum mismatch and removes its owned container") {
            val snapshot = root.snapshot(
                "checksum-mismatch.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, "checksum".encodeToByteArray()),
            ).patchCentral(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                val crc = bytes.u32(offset + CENTRAL_CRC_OFFSET)
                bytes.putU32(offset + CENTRAL_CRC_OFFSET, crc xor 1L)
            }
            val stagingParent = root.stagingParent("checksum-mismatch")
            val stageId = UUID(0L, 1L)

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = stagingParent,
                    budget = testBudget(),
                    stageId = stageId,
                ) shouldBe BackupArchiveStagingResult.Invalid(
                    BackupArchiveStagingFailure.ENTRY_CHECKSUM_MISMATCH,
                )
                Files.notExists(stagingParent.stageContainer(stageId), LinkOption.NOFOLLOW_LINKS) shouldBe true
            }
        }

        test("rejects in-place STORED payload mutation after opening the session") {
            val originalPreferences = "original".encodeToByteArray()
            val snapshot = root.storedSnapshot(
                "stored-payload-mutation.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, originalPreferences),
            )
            val stagingParent = root.stagingParent("stored-payload-mutation")
            val stageId = UUID(0L, 6L)

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                snapshot.overwriteStoredPayload(
                    BackupArchive.PREFERENCES_PATH,
                    "modified".encodeToByteArray(),
                )

                BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = stagingParent,
                    budget = testBudget(),
                    stageId = stageId,
                ) shouldBe BackupArchiveStagingResult.Invalid(
                    BackupArchiveStagingFailure.ENTRY_CHECKSUM_MISMATCH,
                )
                Files.notExists(stagingParent.stageContainer(stageId), LinkOption.NOFOLLOW_LINKS) shouldBe true
            }
        }

        test("rejects runtime size mismatch and removes its owned container") {
            val snapshot = root.snapshot(
                "size-mismatch.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, "size".encodeToByteArray()),
            ).patchCentral(BackupArchive.PREFERENCES_PATH) { bytes, offset ->
                val size = bytes.u32(offset + CENTRAL_UNCOMPRESSED_SIZE_OFFSET)
                bytes.putU32(offset + CENTRAL_UNCOMPRESSED_SIZE_OFFSET, size + 1L)
            }
            val stagingParent = root.stagingParent("size-mismatch")
            val stageId = UUID(0L, 2L)

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = stagingParent,
                    budget = testBudget(),
                    stageId = stageId,
                ) shouldBe BackupArchiveStagingResult.Invalid(
                    BackupArchiveStagingFailure.ENTRY_SIZE_MISMATCH,
                )
                Files.notExists(stagingParent.stageContainer(stageId), LinkOption.NOFOLLOW_LINKS) shouldBe true
            }
        }

        test("preserves a pre-existing partial container at a fixed stage ID") {
            val snapshot = root.snapshot(
                "container-collision.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, byteArrayOf(1)),
            )
            val stagingParent = root.stagingParent("container-collision")
            val stageId = UUID(0L, 3L)
            val sentinel = stagingParent.stageContainer(stageId).resolve("work/sentinel")
            Files.createDirectories(sentinel.parent)
            Files.write(sentinel, "keep".encodeToByteArray())

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = stagingParent,
                    budget = testBudget(),
                    stageId = stageId,
                ) shouldBe BackupArchiveStagingResult.Invalid(
                    BackupArchiveStagingFailure.DESTINATION_COLLISION,
                )
                Files.readAllBytes(sentinel).decodeToString() shouldBe "keep"
            }
        }

        test("preserves a symlink collision and its external sentinel") {
            val snapshot = root.snapshot(
                "symlink-collision.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, byteArrayOf(1)),
            )
            val stagingParent = root.stagingParent("symlink-collision")
            val stageId = UUID(0L, 4L)
            val externalRoot = Files.createDirectories(root.resolve("external-sentinel"))
            val sentinel = Files.write(externalRoot.resolve("sentinel"), "keep".encodeToByteArray())
            val collision = stagingParent.stageContainer(stageId)
            Files.createSymbolicLink(collision, externalRoot)

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = stagingParent,
                    budget = testBudget(),
                    stageId = stageId,
                ) shouldBe BackupArchiveStagingResult.Invalid(
                    BackupArchiveStagingFailure.SYMBOLIC_LINK_DETECTED,
                )
                Files.isSymbolicLink(collision) shouldBe true
                Files.readAllBytes(sentinel).decodeToString() shouldBe "keep"
            }
            Files.delete(collision)
        }

        test("stages from the open channel after the snapshot pathname is replaced") {
            val originalPreferences = "original preferences".encodeToByteArray()
            val snapshot = root.snapshot(
                "path-replacement.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, originalPreferences),
            )
            val replacement = root.snapshot(
                "path-replacement-new.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(
                    BackupArchive.PREFERENCES_PATH,
                    "replacement preferences".encodeToByteArray(),
                ),
            )
            val stagingParent = root.stagingParent("path-replacement")

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                Files.move(
                    replacement.path,
                    snapshot.path,
                    StandardCopyOption.REPLACE_EXISTING,
                )

                val staged = session.stageValid(plan, stagingParent)
                try {
                    Files.readAllBytes(staged.root.resolve(BackupArchive.PREFERENCES_PATH))
                        .contentEquals(originalPreferences) shouldBe true
                } finally {
                    staged.close()
                }
            }
        }

        test("successful staged restores are immutable redacted and idempotently closeable") {
            val marker = "private-stage-marker"
            val preferences = marker.encodeToByteArray()
            val snapshot = root.snapshot(
                "$marker.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, preferences),
            )
            val stagingParent = root.stagingParent(marker)

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                val result = BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = stagingParent,
                    budget = testBudget(),
                )
                val staged = result.validStagedRestore()
                Files.readAllBytes(staged.root.resolve(BackupArchive.PREFERENCES_PATH))
                    .contentEquals(preferences) shouldBe true
                staged.components shouldBe listOf(BackupComponent.PREFERENCES)
                shouldThrow<UnsupportedOperationException> {
                    (staged.components as MutableList).add(BackupComponent.THEME_EXTENSIONS)
                }

                val summary = result.toString()
                summary shouldNotContain marker
                summary shouldNotContain snapshot.path.toString()
                summary shouldNotContain staged.root.toString()

                staged.close()
                staged.close()

                staged.isClosed shouldBe true
                Files.notExists(staged.root, LinkOption.NOFOLLOW_LINKS) shouldBe true
                result.toString() shouldNotContain marker
            }
        }

        test("owned-tree cleanup unlinks inner symlinks without following them") {
            val snapshot = root.snapshot(
                "inner-symlink-cleanup.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, byteArrayOf(1)),
            )
            val stagingParent = root.stagingParent("inner-symlink-cleanup")
            val externalRoot = Files.createDirectory(root.resolve("inner-symlink-external"))
            val sentinel = Files.write(externalRoot.resolve("sentinel"), "keep".encodeToByteArray())

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                val staged = session.stageValid(plan, stagingParent)
                val innerLink = Files.createSymbolicLink(
                    staged.root.resolve("external-link"),
                    externalRoot,
                )

                staged.close()

                staged.isClosed shouldBe true
                Files.notExists(staged.root, LinkOption.NOFOLLOW_LINKS) shouldBe true
                Files.notExists(innerLink, LinkOption.NOFOLLOW_LINKS) shouldBe true
                Files.readAllBytes(sentinel).decodeToString() shouldBe "keep"
            }
        }

        test("cancellation after partial root creation cleans the owned container") {
            val snapshot = root.snapshot(
                "cancel.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.PREFERENCES_PATH, "cancel".encodeToByteArray()),
            )
            val stagingParent = root.stagingParent("cancel")
            val stageId = UUID(0L, 5L)

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.PREFERENCES)
                coroutineScope {
                    val partialRootCreated = CompletableDeferred<Path>()
                    val releaseStager = CountDownLatch(1)
                    val stage = async {
                        BackupArchiveStager.stage(
                            session = session,
                            plan = plan,
                            stagingParent = stagingParent,
                            budget = testBudget(),
                            stageId = stageId,
                            onPartialRootCreated = { createdRoot ->
                                partialRootCreated.complete(createdRoot)
                                releaseStager.await()
                            },
                        )
                    }
                    val partialRoot = partialRootCreated.await()
                    try {
                        partialRoot shouldBe stagingParent.stageContainer(stageId).resolve("work")
                    } finally {
                        stage.cancel()
                        releaseStager.countDown()
                        stage.join()
                    }
                    stage.isCancelled shouldBe true
                }
            }

            Files.notExists(stagingParent.stageContainer(stageId), LinkOption.NOFOLLOW_LINKS) shouldBe true
        }

        test("does not stage media for an unselected clipboard component") {
            val textIndex = "[]".encodeToByteArray()
            val snapshot = root.snapshot(
                "unselected-media.zip",
                fixtureFile(BackupArchive.METADATA_JSON_NAME, metadataJson()),
                fixtureFile(BackupArchive.CLIPBOARD_TEXT_PATH, textIndex),
                fixtureFile(BackupArchive.CLIPBOARD_IMAGES_PATH, "[]".encodeToByteArray()),
                fixtureFile("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/42", "media".encodeToByteArray()),
            )
            val stagingParent = root.stagingParent("unselected-media")

            snapshot.validSession().use { session ->
                val plan = session.validPlan(RestoreMode.MERGE, BackupComponent.CLIPBOARD_TEXT)
                plan.clipboardMediaCandidatesToStage shouldBe emptyList()
                val staged = session.stageValid(plan, stagingParent)
                try {
                    Files.readAllBytes(staged.root.resolve(BackupArchive.CLIPBOARD_TEXT_PATH))
                        .contentEquals(textIndex) shouldBe true
                    Files.notExists(staged.root.resolve(BackupArchive.CLIPBOARD_IMAGES_PATH)) shouldBe true
                    Files.notExists(staged.root.resolve(BackupArchive.CLIPBOARD_MEDIA_ROOT)) shouldBe true
                    staged.entryCount shouldBe 1
                } finally {
                    staged.close()
                }
            }
        }
    })

private data class FixtureEntry(val path: String, val contents: ByteArray?)

private fun fixtureFile(path: String, contents: ByteArray) = FixtureEntry(path, contents)

private fun fixtureDirectory(path: String) = FixtureEntry("${path.trimEnd('/')}/", null)

private fun Path.snapshot(name: String, vararg entries: FixtureEntry): ArchiveSnapshot {
    val file = resolve(name)
    ZipOutputStream(Files.newOutputStream(file)).use { zip ->
        entries.forEach { fixture ->
            zip.putNextEntry(ZipEntry(fixture.path))
            fixture.contents?.let(zip::write)
            zip.closeEntry()
        }
    }
    return ArchiveSnapshot(file, Files.size(file))
}

private fun Path.storedSnapshot(name: String, vararg entries: FixtureEntry): ArchiveSnapshot {
    val file = resolve(name)
    ZipOutputStream(Files.newOutputStream(file)).use { zip ->
        entries.forEach { fixture ->
            val contents = fixture.contents ?: byteArrayOf()
            val checksum = CRC32().apply { update(contents) }.value
            val entry = ZipEntry(fixture.path).apply {
                method = ZipEntry.STORED
                size = contents.size.toLong()
                compressedSize = size
                crc = checksum
            }
            zip.putNextEntry(entry)
            fixture.contents?.let(zip::write)
            zip.closeEntry()
        }
    }
    return ArchiveSnapshot(file, Files.size(file))
}

private suspend fun ArchiveSnapshot.validSession(): BackupArchiveSession =
    (BackupArchiveSession.open(this) as BackupArchiveSessionResult.Valid).session

private fun BackupArchiveSession.validPlan(mode: RestoreMode, vararg components: BackupComponent): RestorePlan = (
    createPlan(
        RestoreRequest(mode, components.toSet()),
    ) as RestorePlanResult.Valid
    ).plan

private suspend fun BackupArchiveSession.stageValid(
    plan: RestorePlan,
    stagingParent: Path,
    budget: RestoreStagingBudget = testBudget(),
): StagedRestore = BackupArchiveStager.stage(
    session = this,
    plan = plan,
    stagingParent = stagingParent,
    budget = budget,
).validStagedRestore()

private fun BackupArchiveStagingResult.validStagedRestore(): StagedRestore =
    (this as BackupArchiveStagingResult.Valid).stagedRestore

private fun Path.stagingParent(name: String): Path = Files.createDirectory(resolve("staging-$name"))

private fun Path.stageContainer(stageId: UUID): Path = resolve(".restore-stage-$stageId")

private fun Path.childCount(): Long = Files.list(this).use { children -> children.count() }

private fun testBudget(maxBytes: Long = 1L shl 20) = RestoreStagingBudget(
    maxBytes = maxBytes,
    requiredFreeBytes = 0L,
)

private fun ArchiveSnapshot.patchCentral(
    entryName: String,
    patch: (bytes: ByteArray, offset: Int) -> Unit,
): ArchiveSnapshot {
    val bytes = Files.readAllBytes(path)
    patch(bytes, bytes.findCentralEntry(entryName))
    Files.write(path, bytes)
    return ArchiveSnapshot(path, Files.size(path))
}

private fun ArchiveSnapshot.overwriteStoredPayload(entryName: String, replacement: ByteArray) {
    val bytes = Files.readAllBytes(path)
    val centralOffset = bytes.findCentralEntry(entryName)
    check(bytes.u16(centralOffset + CENTRAL_METHOD_OFFSET) == ZipEntry.STORED)
    check(bytes.u32(centralOffset + CENTRAL_UNCOMPRESSED_SIZE_OFFSET) == replacement.size.toLong())
    val localOffset = bytes.u32(centralOffset + CENTRAL_LOCAL_HEADER_OFFSET_OFFSET).toInt()
    check(bytes.u32(localOffset) == LOCAL_SIGNATURE)
    val dataOffset = localOffset +
        LOCAL_HEADER_BYTES +
        bytes.u16(localOffset + LOCAL_NAME_LENGTH_OFFSET) +
        bytes.u16(localOffset + LOCAL_EXTRA_LENGTH_OFFSET)

    FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
        val replacementBuffer = ByteBuffer.wrap(replacement)
        var writeOffset = dataOffset.toLong()
        while (replacementBuffer.hasRemaining()) {
            val written = channel.write(replacementBuffer, writeOffset)
            check(written > 0)
            writeOffset += written
        }
        channel.force(true)
    }
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
        if (actualName == entryName) return offset
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

private const val CENTRAL_SIGNATURE = 0x02014b50L
private const val CENTRAL_HEADER_BYTES = 46
private const val CENTRAL_METHOD_OFFSET = 10
private const val CENTRAL_CRC_OFFSET = 16
private const val CENTRAL_UNCOMPRESSED_SIZE_OFFSET = 24
private const val CENTRAL_NAME_LENGTH_OFFSET = 28
private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
private const val CENTRAL_LOCAL_HEADER_OFFSET_OFFSET = 42
private const val LOCAL_SIGNATURE = 0x04034b50L
private const val LOCAL_HEADER_BYTES = 30
private const val LOCAL_NAME_LENGTH_OFFSET = 26
private const val LOCAL_EXTRA_LENGTH_OFFSET = 28
