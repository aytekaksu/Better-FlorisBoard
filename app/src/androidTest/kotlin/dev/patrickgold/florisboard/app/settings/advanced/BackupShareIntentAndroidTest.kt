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

        try {
            val backupBytes = byteArrayOf(1, 3, 3, 7)
            val zipFile = workspace.outputDir.resolve("backup-test.zip")
            zipFile.writeBytes(backupBytes)
            val chooser = Backup.createShareIntent(context, zipFile)
            val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            val stream = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("application/zip", send.type)
            assertEquals("content", stream.scheme)
            assertEquals(Backup.FILE_PROVIDER_AUTHORITY, stream.authority)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            context.contentResolver.openInputStream(stream).use { input ->
                assertArrayEquals(backupBytes, requireNotNull(input).readBytes())
            }
        } finally {
            workspace.close()
        }
    }
}
