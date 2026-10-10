/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.InstalledClipboardMedia
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardMediaPasteLeaseAndroidTest {
    @Test
    fun resolvedGraceLeaseAllowsImmediateRepeatPaste() {
        val media = requireNotNull(OwnedClipboardMediaUri.create(1L, ItemType.IMAGE))
        val registry = ClipboardMediaPasteLeaseRegistry()
        val first = requireNotNull(
            (registry.acquireForPaste(media) as? ClipboardMediaPasteLeaseAcquisition.Acquired)
                ?.lease,
        )

        assertTrue(
            registry.acquireForPaste(media) ===
                ClipboardMediaPasteLeaseAcquisition.Busy,
        )
        first.renew()
        val second = requireNotNull(
            (registry.acquireForPaste(media) as? ClipboardMediaPasteLeaseAcquisition.Acquired)
                ?.lease,
        )
        assertFalse(registry.reserveDeletion(media))

        first.close()
        second.close()
        assertTrue(registry.reserveDeletion(media))
        registry.releaseDeletion(media)
    }

    @Test
    fun closingAdmissionLifecycleRollsBackBeforeRollbackScopeCompletes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(
            context.cacheDir.toPath(),
            "clipboard-paste-close-",
            ".bin",
        )
        Files.write(source, byteArrayOf(1))
        var installed: InstalledClipboardMedia? = null
        val rollbackJob = SupervisorJob()
        val rollbackScope = CoroutineScope(Dispatchers.IO + rollbackJob)

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 1L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val owned = requireNotNull(installed).ownedUri
            val receipt = requireNotNull(
                ClipboardFileStorage.markPasteRoot(context, owned).receipt,
            )
            val rolledBack = CompletableDeferred<Unit>()
            val registry = ClipboardMediaPasteAccessRegistry()
            lateinit var access: ClipboardMediaPasteAccess
            access = ClipboardMediaPasteAccess(
                mimeTypes = listOf("image/png"),
                acceptAction = {
                    ClipboardFileStorage.completePasteAdmission(receipt)
                },
                rejectAction = {
                    rollbackScope.launch {
                        try {
                            ClipboardFileStorage.abortPasteAdmission(context, receipt)
                        } finally {
                            rolledBack.complete(Unit)
                        }
                    }
                },
                resolvedAction = { registry.unregister(access) },
            )
            assertTrue(registry.register(access))

            registry.close()
            rollbackJob.complete()
            runBlocking {
                withTimeout(5_000L) {
                    rolledBack.await()
                    rollbackJob.join()
                }
            }

            assertFalse(ClipboardFileStorage.pasteRoots(context).contains(owned))
            assertEquals(
                0L,
                ClipboardFileStorage.fileInfo(context, owned)?.pasteRetainedUntilMs,
            )
        } finally {
            ClipboardFileStorage.trimPasteRoots(context, now = Long.MAX_VALUE)
            installed?.ownedUri?.let { owned ->
                runCatching { ClipboardFileStorage.deleteOwned(context, owned) }
            }
            runCatching { installed?.cleanup() }
            Files.deleteIfExists(source)
        }
    }
}
