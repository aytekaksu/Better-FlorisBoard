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
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.lib.io.ZipUtils
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardManagerBackupAndroidTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: ClipboardManager
        get() = context.clipboardManager().value

    @Before
    fun prepareEmptyHistory() = runBlocking {
        withTimeout(5_000L) {
            manager.awaitInitialization()
        }
        clearHistory()
    }

    @After
    fun restoreEmptyHistory() = runBlocking {
        clearHistory()
    }

    @Test
    fun persistedTextHistoryProducesAValidBackupPayload() = runBlocking {
        manager.commitHistoryRestore(
            items = listOf(
                ClipboardItem.text("manager backup text").copy(
                    creationTimestampMs = 1L,
                ),
            ),
            selectedTypes = setOf(ItemType.TEXT),
            replaceSelected = true,
        )

        val snapshot = manager.acquireBackupSnapshot(setOf(ItemType.TEXT))
        val workspace = Files.createTempDirectory(
            context.cacheDir.toPath(),
            "clipboard-manager-backup-",
        ).toFile()
        try {
            val stored = snapshot.items.single()
            assertNull(stored.uri)

            ClipboardBackupPayload.write(
                context = context,
                stagedRoot = workspace,
                sourcePackageName = context.packageName,
                selectedTypes = setOf(ItemType.TEXT),
                items = snapshot.items,
                transferBudget = transferBudget(),
                checkActive = {},
            )

            val prepared = ClipboardRestorePayload.prepare(
                stagedRoot = workspace.toPath(),
                sourcePackageName = context.packageName,
                selectedTypes = setOf(ItemType.TEXT),
            ) as ClipboardRestorePayloadResult.Valid
            assertEquals("manager backup text", prepared.payload.items.single().text)
            assertNull(prepared.payload.items.single().mediaRef)
        } finally {
            try {
                snapshot.release()
            } finally {
                workspace.deleteRecursively()
            }
        }
    }

    private suspend fun clearHistory() {
        manager.commitHistoryRestore(
            items = emptyList(),
            selectedTypes = ItemType.entries.toSet(),
            replaceSelected = true,
        )
    }

    private fun transferBudget() = ZipUtils.TransferBudget(
        maxEntries = 4,
        maxBytes = MAX_BACKUP_BYTES,
        maxFileBytes = MAX_BACKUP_BYTES,
        checkCancelled = {},
    )

    companion object {
        private const val MAX_BACKUP_BYTES = 1_048_576L
    }
}
