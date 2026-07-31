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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.lib.io.ZipUtils
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardBackupPayloadAndroidTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun oversizedIndexRemovesPartialOutputAndRedactsFailure() {
        withWorkspace("oversized") { workspace ->
            val secret = "private-clipboard-marker"
            val oversizedText = secret + "\"".repeat(600)

            val failure = assertThrows(ClipboardBackupPayloadException::class.java) {
                writePayload(
                    workspace = workspace,
                    selectedTypes = setOf(ItemType.TEXT),
                    items = listOf(ClipboardItem.text(oversizedText)),
                    maxIndexBytes = TEST_INDEX_BYTES,
                )
            }

            assertEquals(ClipboardBackupPayloadFailure.LIMIT_EXCEEDED, failure.failure)
            assertEquals(ClipboardBackupPayloadFailure.LIMIT_EXCEEDED.name, failure.message)
            assertFalse(failure.toString().contains(secret))
            assertFalse(failure.toString().contains(workspace.absolutePath))
            assertNoIndexOutput(workspace, BackupArchive.CLIPBOARD_TEXT_ITEMS_JSON_NAME)
        }
    }

    @Test
    fun cancellationDuringIndexWriteRemovesPartialOutput() {
        withWorkspace("cancelled-index") { workspace ->
            val activeChecks = AtomicInteger()

            assertThrows(CancellationException::class.java) {
                writePayload(
                    workspace = workspace,
                    selectedTypes = setOf(ItemType.TEXT),
                    items = listOf(ClipboardItem.text("private text")),
                    checkActive = {
                        if (activeChecks.incrementAndGet() >= 2) {
                            throw CancellationException("synthetic cancellation")
                        }
                    },
                )
            }

            assertNoIndexOutput(workspace, BackupArchive.CLIPBOARD_TEXT_ITEMS_JSON_NAME)
        }
    }

    @Test
    fun mediaDisplayNameSurvivesGeneratedPayloadValidation() {
        withWorkspace("display-name") { workspace ->
            val source = Files.createTempFile(
                context.cacheDir.toPath(),
                "clipboard-backup-display-name-",
                ".bin",
            )
            Files.write(source, byteArrayOf(1, 2, 3))
            val install = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = Files.size(source),
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                displayName = " \u0000holiday.png ",
            )
            try {
                writePayload(
                    workspace = workspace,
                    selectedTypes = setOf(ItemType.IMAGE),
                    items = listOf(
                        ClipboardItem(
                            type = ItemType.IMAGE,
                            text = null,
                            uri = install.ownedUri.uri,
                            creationTimestampMs = 1L,
                            isPinned = false,
                            mimeTypes = listOf("image/png"),
                        ),
                    ),
                )

                val result = ClipboardRestorePayload.prepare(
                    stagedRoot = workspace.toPath(),
                    sourcePackageName = context.packageName,
                    selectedTypes = setOf(ItemType.IMAGE),
                ) as ClipboardRestorePayloadResult.Valid

                assertEquals("_holiday.png", result.payload.media.single().displayName)
            } finally {
                install.cleanup()
                Files.deleteIfExists(source)
            }
        }
    }

    @Test
    fun truncatedMediaSourceRemovesPartialOutput() {
        withWorkspace("truncated-media") { workspace ->
            val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-backup-source-", ".bin")
            val sourceBytes = ByteArray(MEDIA_BYTES) { index -> index.toByte() }
            Files.write(source, sourceBytes)
            val install = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = sourceBytes.size.toLong(),
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val ownedFile = requireNotNull(
                ClipboardFileStorage.ownedFile(context, install.ownedUri),
            ).toPath()
            val mediaDirectory = File(
                workspace,
                "${BackupArchive.CLIPBOARD_ROOT}/${ClipboardFileStorage.CLIPBOARD_FILES_PATH}",
            )
            val partialOutput = File(mediaDirectory, ".${install.ownedUri.id}.partial")
            val sourceTruncated = AtomicBoolean(false)
            try {
                val failure = assertThrows(ClipboardBackupPayloadException::class.java) {
                    writePayload(
                        workspace = workspace,
                        selectedTypes = setOf(ItemType.IMAGE),
                        items = listOf(
                            ClipboardItem(
                                type = ItemType.IMAGE,
                                text = null,
                                uri = install.ownedUri.uri,
                                creationTimestampMs = 1L,
                                isPinned = false,
                                mimeTypes = listOf("image/png"),
                            ),
                        ),
                        transferBudget = transferBudget {
                            if (partialOutput.exists() &&
                                sourceTruncated.compareAndSet(false, true)
                            ) {
                                FileChannel.open(ownedFile, StandardOpenOption.WRITE).use {
                                    it.truncate(0L)
                                }
                            }
                        },
                    )
                }

                assertEquals(ClipboardBackupPayloadFailure.MEDIA_UNAVAILABLE, failure.failure)
                assertTrue(sourceTruncated.get())
                assertFalse(File(mediaDirectory, install.ownedUri.id.toString()).exists())
                assertTrue(
                    mediaDirectory.listFiles()
                        .orEmpty()
                        .none { it.name.endsWith(".partial") },
                )
            } finally {
                install.cleanup()
                Files.deleteIfExists(source)
            }
        }
    }

    private fun writePayload(
        workspace: File,
        selectedTypes: Set<ItemType>,
        items: List<ClipboardItem>,
        transferBudget: ZipUtils.TransferBudget = transferBudget(),
        checkActive: () -> Unit = {},
        maxIndexBytes: Long = ClipboardRestorePayloadLimits.Default.maxIndexBytes,
    ) {
        ClipboardBackupPayload.write(
            context = context,
            stagedRoot = workspace,
            sourcePackageName = context.packageName,
            selectedTypes = selectedTypes,
            items = items,
            transferBudget = transferBudget,
            checkActive = checkActive,
            maxIndexBytes = maxIndexBytes,
        )
    }

    private fun transferBudget(
        checkCancelled: () -> Unit = {},
    ) = ZipUtils.TransferBudget(
        maxEntries = 8,
        maxBytes = ClipboardFileStorage.MAX_MEDIA_BYTES,
        maxFileBytes = ClipboardFileStorage.MAX_MEDIA_BYTES,
        checkCancelled = checkCancelled,
    )

    private fun assertNoIndexOutput(workspace: File, name: String) {
        val clipboardDirectory = File(workspace, BackupArchive.CLIPBOARD_ROOT)
        assertFalse(File(clipboardDirectory, name).exists())
        assertFalse(File(clipboardDirectory, ".$name.partial").exists())
    }

    private inline fun withWorkspace(name: String, block: (File) -> Unit) {
        val workspace = Files.createTempDirectory(
            context.cacheDir.toPath(),
            "clipboard-backup-$name-",
        ).toFile()
        try {
            block(workspace)
        } finally {
            workspace.deleteRecursively()
        }
    }

    companion object {
        private const val MEDIA_BYTES = 128 * 1024
        private const val TEST_INDEX_BYTES = 1_024L
    }
}
