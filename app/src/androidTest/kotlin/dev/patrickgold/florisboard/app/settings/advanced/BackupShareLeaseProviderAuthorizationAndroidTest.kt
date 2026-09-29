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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupShareLeaseProviderAuthorizationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val recipientPackage: String get() = instrumentation.context.packageName
    private val probeUri = Uri.parse("content://${BackupShareProbeProvider.AUTHORITY}")

    @Test
    fun separateUidNeedsExactReadGrantAndCannotWrite() {
        assertTrue(context.applicationInfo.uid != instrumentation.context.applicationInfo.uid)
        val store = BackupShareLeaseStore(context)
        val (lease, bytes) = publishProbeZip(store)
        val (sibling, _) = publishProbeZip(store)
        try {
            assertHidden(probe(lease.uri))
            context.grantUriPermission(recipientPackage, lease.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val granted = probe(lease.uri)
            assertEquals(lease.uri.lastPathSegment, granted.getString(BackupShareProbeProvider.NAME))
            assertEquals(bytes.size.toLong(), granted.getLong(BackupShareProbeProvider.SIZE))
            assertEquals(bytes.size.toLong(), granted.getLong(BackupShareProbeProvider.READ_SIZE))
            val expectedDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
            assertArrayEquals(expectedDigest, granted.getByteArray(BackupShareProbeProvider.READ_SHA256))
            assertEquals(bytes.size.toLong(), granted.getLong(BackupShareProbeProvider.TYPED_READ_SIZE))
            assertArrayEquals(expectedDigest, granted.getByteArray(BackupShareProbeProvider.TYPED_READ_SHA256))
            assertEquals("application/zip", granted.getString(BackupShareProbeProvider.TYPE))
            assertFalse(granted.getBoolean(BackupShareProbeProvider.WRITABLE))
            // Even a mistakenly granted write capability cannot change the provider's read-only mode.
            context.grantUriPermission(recipientPackage, lease.uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertFalse(probe(lease.uri).getBoolean(BackupShareProbeProvider.WRITABLE))
            assertTrue(runCatching {
                context.contentResolver.openFileDescriptor(lease.uri, "rw")?.close()
            }.isFailure)
            assertHidden(probe(sibling.uri))

            context.revokeUriPermission(lease.uri, GRANT_FLAGS)
            assertHidden(probe(lease.uri))
        } finally {
            context.revokeUriPermission(lease.uri, GRANT_FLAGS)
            store.release(lease)
            store.release(sibling)
        }
    }

    @Test
    fun grantedMalformedPathsAndSymlinksCannotEscapeTheLease() {
        val store = BackupShareLeaseStore(context)
        val (lease, _) = publishProbeZip(store)
        val id = lease.id
        val linkedName = "linked.zip"
        val link = context.noBackupFilesDir.toPath()
            .resolve("backup-share-leases").resolve(id).resolve(linkedName)
        val outside = context.noBackupFilesDir.toPath().resolve("outside-${UUID.randomUUID()}.zip")
        val malformed = listOf(
            lease.uri.buildUpon().appendQueryParameter("extra", "1").build(),
            Uri.parse("content://${BackupShareLeaseStore.AUTHORITY}/backups/$id/../probe.zip"),
            Uri.parse("content://${BackupShareLeaseStore.AUTHORITY}/backups/$id/%2Fprobe.zip"),
        )
        try {
            Files.write(outside, byteArrayOf(1, 2, 3))
            Files.createSymbolicLink(link, outside)
            val linked = Uri.parse("content://${BackupShareLeaseStore.AUTHORITY}/backups/$id/$linkedName")
            for (uri in malformed + linked) {
                // Grant each bad URI itself so a denial cannot be credited to a missing grant.
                context.grantUriPermission(recipientPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                assertHidden(probe(uri), checkPublicType = false)
                assertTrue(runCatching {
                    requireNotNull(context.contentResolver.openInputStream(uri)).close()
                }.isFailure)
            }
        } finally {
            for (uri in malformed) {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.revokeUriPermission(
                Uri.parse("content://${BackupShareLeaseStore.AUTHORITY}/backups/$id/$linkedName"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            Files.deleteIfExists(link)
            Files.deleteIfExists(outside)
            store.release(lease)
        }
    }

    private fun assertHidden(result: Bundle, checkPublicType: Boolean = true) {
        if (checkPublicType) {
            // Pre-14 exposes only the fixed ZIP category; it does not confirm a live lease.
            val publicType = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                "application/zip"
            } else {
                null
            }
            assertEquals(publicType, result.getString(BackupShareProbeProvider.TYPE))
        }
        assertNull(result.getString(BackupShareProbeProvider.NAME))
        assertFalse(result.containsKey(BackupShareProbeProvider.READ_SIZE))
        assertFalse(result.containsKey(BackupShareProbeProvider.TYPED_READ_SIZE))
        assertFalse(result.getBoolean(BackupShareProbeProvider.WRITABLE))
    }

    private fun probe(uri: Uri): Bundle = requireNotNull(context.contentResolver.call(
        probeUri,
        BackupShareProbeProvider.METHOD_PROBE,
        uri.toString(),
        null,
    ))

    private fun publishProbeZip(store: BackupShareLeaseStore): Pair<BackupShareLeaseStore.Lease, ByteArray> {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("probe.txt"))
                zip.write(byteArrayOf(1, 3, 3, 7))
                zip.closeEntry()
            }
        }.toByteArray()
        val source = context.cacheDir.toPath().resolve("probe-${UUID.randomUUID()}.zip")
        try {
            Files.write(source, bytes)
            return store.publish(source.toFile()) to bytes
        } finally {
            Files.deleteIfExists(source)
        }
    }

    companion object {
        private const val GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
