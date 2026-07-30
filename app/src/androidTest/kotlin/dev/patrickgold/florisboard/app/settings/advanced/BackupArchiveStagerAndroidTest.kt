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

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.lib.io.ZipUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupArchiveStagerAndroidTest {
    @Test
    fun capturesAContentUriWithinPrivateStorageOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val providerRoot = Files.createDirectories(context.cacheDir.toPath().resolve("backup-and-restore"))
        val testRoot = Files.createTempDirectory(providerRoot, "backup-snapshot-android-")
        val source = testRoot.resolve("source.zip")
        val workspace = Files.createDirectory(testRoot.resolve("workspace"))
        val destination = workspace.resolve("snapshot.zip")
        val expected = byteArrayOf(1, 2, 3, 4)

        try {
            Files.write(source, expected)
            val sourceUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider.file",
                source.toFile(),
            )

            val result = BackupArchiveSnapshot.capture(
                contentResolver = context.contentResolver,
                uri = sourceUri,
                workspaceDir = workspace,
                destination = destination,
            )

            result as ArchiveSnapshotResult.Valid
            assertArrayEquals(expected, Files.readAllBytes(result.snapshot.path))
            assertFalse(Files.exists(destination.resolveSibling("snapshot.zip.partial")))
        } finally {
            testRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun stagesAndCleansAValidatedTreeOnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = Files.createTempDirectory(context.cacheDir.toPath(), "backup-stager-android-")
        val archivePath = testRoot.resolve("backup.zip")
        val stagingParent = Files.createDirectory(testRoot.resolve("staging"))
        val preferenceBytes = byteArrayOf(1, 2, 3, 4)
        val stageId = UUID.fromString("148a2d16-1e5b-4b90-b913-c1ab46ed8f41")

        try {
            ZipOutputStream(Files.newOutputStream(archivePath)).use { archive ->
                archive.writeEntry(
                    BackupArchive.METADATA_JSON_NAME,
                    """{"package":"dev.patrickgold.florisboard","versionCode":64,"versionName":"test","timestamp":1}"""
                        .encodeToByteArray(),
                )
                archive.writeEntry(BackupArchive.PREFERENCES_PATH, preferenceBytes)
                archive.putNextEntry(ZipEntry("${BackupArchive.KEYBOARD_ROOT}/"))
                archive.closeEntry()
            }

            val snapshot = ArchiveSnapshot(archivePath, Files.size(archivePath))
            val session = (BackupArchiveSession.open(snapshot) as BackupArchiveSessionResult.Valid).session
            session.use {
                val plan = (
                    session.createPlan(
                        RestoreRequest(
                            mode = RestoreMode.MERGE,
                            selectedComponents = setOf(
                                BackupComponent.PREFERENCES,
                                BackupComponent.KEYBOARD_EXTENSIONS,
                            ),
                        ),
                    ) as RestorePlanResult.Valid
                    ).plan
                val stageResult = BackupArchiveStager.stage(
                    session = session,
                    plan = plan,
                    stagingParent = stagingParent,
                    budget = RestoreStagingBudget(
                        maxBytes = preferenceBytes.size.toLong(),
                        requiredFreeBytes = 0,
                    ),
                    stageId = stageId,
                )
                val staged = when (stageResult) {
                    is BackupArchiveStagingResult.Valid -> stageResult.stagedRestore

                    is BackupArchiveStagingResult.Invalid -> {
                        throw AssertionError("Staging failed: ${stageResult.failure.name}")
                    }
                }

                assertArrayEquals(
                    preferenceBytes,
                    Files.readAllBytes(staged.root.resolve(BackupArchive.PREFERENCES_PATH)),
                )
                assertTrue(Files.isDirectory(staged.root.resolve(BackupArchive.KEYBOARD_ROOT)))
                assertPrivatePermissions(staged.root)
                assertPrivatePermissions(staged.root.resolve(BackupArchive.PREFERENCES_PATH))

                staged.close()
                staged.close()

                assertTrue(staged.isClosed)
                assertFalse(
                    Files.exists(
                        stagingParent.resolve(".restore-stage-$stageId"),
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                )
            }
        } finally {
            testRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun boundedWriterPublishesAValidArchiveAndRejectsSourceLinks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = Files.createTempDirectory(context.cacheDir.toPath(), "backup-writer-android-")
        val source = Files.createDirectory(testRoot.resolve("source"))
        val preferences = Files.createDirectories(source.resolve("jetpref_datastore"))
            .resolve("florisboard-app-prefs.jetpref")
        val destination = testRoot.resolve("backup.zip")
        val linkedSource = Files.createDirectory(testRoot.resolve("linked-source"))
        val outside = Files.write(testRoot.resolve("outside"), "private".encodeToByteArray())
        val link = linkedSource.resolve("link")

        try {
            Files.write(
                source.resolve(BackupArchive.METADATA_JSON_NAME),
                """{"package":"dev.patrickgold.florisboard","versionCode":64,"versionName":"test","timestamp":1}"""
                    .encodeToByteArray(),
            )
            Files.write(preferences, "preferences".encodeToByteArray())
            val limits = ArchiveLimits.Default

            ZipUtils.zip(
                source.toFile(),
                destination.toFile(),
                ZipUtils.WriteLimits(
                    maxEntries = limits.maxEntries,
                    maxSourceBytes = limits.maxExpandedBytes,
                    maxFileBytes = limits.maxEntryBytes,
                    maxPathBytes = limits.maxPathBytes,
                    maxPathSegmentBytes = limits.maxPathSegmentBytes,
                    maxOutputBytes = limits.maxArchiveBytes,
                    maxFileBytesForPath = limits::maxEntryBytesFor,
                    checkCancelled = {},
                ),
            )

            val snapshot = ArchiveSnapshot(destination, Files.size(destination))
            val result = BackupArchiveSession.open(snapshot)
            assertTrue(result is BackupArchiveSessionResult.Valid)
            (result as BackupArchiveSessionResult.Valid).session.close()

            Files.createSymbolicLink(link, outside)
            val copyFailure = runCatching {
                ZipUtils.copyDirectoryNoFollow(
                    linkedSource.toFile(),
                    testRoot.resolve("linked-copy").toFile(),
                )
            }
            assertTrue(copyFailure.exceptionOrNull() is IllegalStateException)
            assertFalse(Files.exists(testRoot.resolve("linked-copy"), LinkOption.NOFOLLOW_LINKS))
        } finally {
            Files.deleteIfExists(link)
            testRoot.toFile().deleteRecursively()
        }
    }
}

private fun ZipOutputStream.writeEntry(path: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(path))
    write(bytes)
    closeEntry()
}

private fun assertPrivatePermissions(path: Path) {
    if (
        Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) != null
    ) {
        assertTrue(Files.getPosixFilePermissions(path).none(NON_OWNER_PERMISSIONS::contains))
    }
}

private val NON_OWNER_PERMISSIONS = setOf(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE,
    PosixFilePermission.OTHERS_EXECUTE,
)
