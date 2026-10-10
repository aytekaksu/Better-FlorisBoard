/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.lib.cache.CacheManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupShareIntentAndroidTest {
    @Suppress("DEPRECATION")
    @Test
    fun chooserSendsBackupZipMimeUriAndReadGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = CacheManager(context).backupAndRestore.new()
        val store = BackupShareLeaseStore(context)
        var lease: BackupShareLeaseStore.Lease? = null

        try {
            val backupBytes = byteArrayOf(1, 3, 3, 7)
            val zipFile = workspace.outputDir.resolve("backup-test.zip")
            zipFile.writeBytes(backupBytes)
            lease = store.publish(zipFile)
            val chooser = Backup.createShareIntent(context, lease.uri)
            val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            val stream = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/zip", send.type)
            assertEquals("content", stream.scheme)
            assertEquals(BackupShareLeaseStore.AUTHORITY, stream.authority)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(stream, chooser.clipData?.getItemAt(0)?.uri)
            context.contentResolver.openInputStream(stream).use { input ->
                assertArrayEquals(backupBytes, requireNotNull(input).readBytes())
            }
        } finally {
            workspace.close()
            lease?.let(store::release)
        }
    }
}
