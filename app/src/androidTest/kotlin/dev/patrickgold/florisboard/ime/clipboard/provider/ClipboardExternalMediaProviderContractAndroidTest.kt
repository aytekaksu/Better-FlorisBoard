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

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.BuildConfig
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardExternalMediaProviderContractAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetSource() {
        ClipboardExternalMediaTestSource.reset()
    }

    @After
    fun releaseSource() {
        ClipboardExternalMediaTestSource.releaseBlockingOpen()
        ClipboardExternalMediaTestSource.revokeReadAccess()
    }

    @Test
    fun targetProcessCanReadOnlyAnExplicitlyGrantedSourceUntilRevocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        @Suppress("DEPRECATION")
        val provider = instrumentation.context.packageManager.resolveContentProvider(
            ClipboardExternalMediaTestSource.AUTHORITY,
            0,
        )
        @Suppress("DEPRECATION")
        val controlProvider = instrumentation.context.packageManager.resolveContentProvider(
            ClipboardExternalMediaTestSource.CONTROL_AUTHORITY,
            0,
        )
        assertNotNull(provider)
        assertNotNull(controlProvider)
        assertNotEquals(context.applicationInfo.uid, provider?.applicationInfo?.uid)
        assertFalse(provider?.exported ?: true)
        assertTrue(provider?.grantUriPermissions == true)
        assertTrue(controlProvider?.exported == true)
        assertFalse(controlProvider?.grantUriPermissions ?: true)
        assertEquals(provider?.applicationInfo?.uid, controlProvider?.applicationInfo?.uid)
        assertNotEquals(provider?.processName, controlProvider?.processName)
        assertTargetCannotQueryOrOpen(ClipboardExternalMediaTestSource.healthyUri)

        ClipboardExternalMediaTestSource.grantReadAccess(
            ClipboardExternalMediaTestSource.healthyUri,
        )
        assertEquals(
            "application/octet-stream",
            context.contentResolver.getType(ClipboardExternalMediaTestSource.healthyUri),
        )
        assertTargetCanQueryAndOpen(ClipboardExternalMediaTestSource.healthyUri)
        assertTargetCannotQueryOrOpen(ClipboardExternalMediaTestSource.svgUri)

        ClipboardExternalMediaTestSource.revokeReadAccess(
            ClipboardExternalMediaTestSource.healthyUri,
        )
        assertTargetCannotQueryOrOpen(ClipboardExternalMediaTestSource.healthyUri)
    }

    @Test
    fun importWorkerIsPrivateSameUidRemoteAndExternallyUnacquirable() {
        val authority = "${BuildConfig.APPLICATION_ID}.provider.clipboard-import-worker"
        @Suppress("DEPRECATION")
        val provider = context.packageManager.resolveContentProvider(authority, 0)

        assertNotNull(provider)
        val resolved = requireNotNull(provider)
        assertEquals(authority, resolved.authority)
        assertTrue(resolved.enabled)
        assertFalse(resolved.exported)
        assertFalse(resolved.grantUriPermissions)
        assertEquals(context.applicationInfo.uid, resolved.applicationInfo.uid)
        assertEquals("${BuildConfig.APPLICATION_ID}:clipboard_import", resolved.processName)

        val internalClient =
            context.contentResolver.acquireUnstableContentProviderClient(authority)
        try {
            assertNotNull(internalClient)
            assertNull(internalClient?.localContentProvider)
        } finally {
            internalClient?.close()
        }
        assertFalse(ClipboardExternalMediaTestSource.externalProcessCanAcquireImportWorker())
    }

    private fun assertTargetCanQueryAndOpen(uri: Uri) {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
            CancellationSignal(),
        )?.use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(
                " \u0000vector.svg ",
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)),
            )
        } ?: throw AssertionError("Granted provider query was unavailable.")

        context.contentResolver.openAssetFileDescriptor(
            uri,
            "r",
            CancellationSignal(),
        )?.use { descriptor ->
            descriptor.createInputStream().use { input ->
                assertArrayEquals(byteArrayOf(1, 3, 3, 7), input.readBytes())
            }
        } ?: throw AssertionError("Granted provider data was unavailable.")
    }

    private fun assertTargetCannotQueryOrOpen(uri: Uri) {
        assertThrows(SecurityException::class.java) {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
                CancellationSignal(),
            )?.close()
        }
        assertThrows(SecurityException::class.java) {
            context.contentResolver.openAssetFileDescriptor(
                uri,
                "r",
                CancellationSignal(),
            )?.close()
        }
    }
}
