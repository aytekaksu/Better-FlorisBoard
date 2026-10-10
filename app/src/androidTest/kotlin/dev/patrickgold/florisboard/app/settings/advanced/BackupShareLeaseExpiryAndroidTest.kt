/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.lib.cache.CacheManager
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupShareLeaseExpiryAndroidTest {
    @Test
    fun expiredShareDeniesNewOpensBeforeItsWorkerRuns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = CacheManager(context).backupAndRestore.new()
        val store = BackupShareLeaseStore(context)
        var lease: BackupShareLeaseStore.Lease? = null

        try {
            val bytes = byteArrayOf(1, 3, 3, 7)
            val zip = workspace.outputDir.resolve("backup-expiry-test.zip")
            zip.writeBytes(bytes)
            lease = store.publish(zip)
            val uri = lease.uri
            context.contentResolver.openInputStream(uri).use { input ->
                assertArrayEquals(bytes, requireNotNull(input).readBytes())
            }

            // Advancing the device clock for an hour would disturb other tests.
            // Set only this lease's private expiry, leaving the scheduled worker asleep.
            val directory = context.noBackupFilesDir.toPath()
                .resolve("backup-share-leases")
                .resolve(lease.id)
            Files.write(
                directory.resolve(".expires"),
                ByteBuffer.allocate(Long.SIZE_BYTES).putLong(System.currentTimeMillis() - 1L).array(),
            )
            assertThrows(FileNotFoundException::class.java) {
                context.contentResolver.openInputStream(uri)?.close()
            }
            store.pruneExpired()
            assertFalse(Files.exists(directory))
        } finally {
            lease?.let(store::release)
            workspace.close()
        }
    }
}
