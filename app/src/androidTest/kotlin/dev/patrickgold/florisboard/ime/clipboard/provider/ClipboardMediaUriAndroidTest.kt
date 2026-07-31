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

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.clipboard.isExternalClipboardShareUri
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardMediaUriAndroidTest {
    @Test
    fun onlyTheRootScopedOreoProviderIsConditionallyExported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val privateProvider = context.packageManager.getProviderInfo(
            ComponentName(context, ClipboardMediaProvider::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        @Suppress("DEPRECATION")
        val oreoProvider = context.packageManager.getProviderInfo(
            ComponentName(context, OreoSystemClipboardMediaProvider::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val needsOreoProxy = Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1

        assertFalse(privateProvider.exported)
        assertEquals(needsOreoProxy, oreoProvider.enabled)
        assertEquals(needsOreoProxy, oreoProvider.exported)
    }

    @Test
    fun exportedClipboardShareAcceptsOnlyGrantedForeignContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val readGrant = Intent.FLAG_GRANT_READ_URI_PERMISSION

        assertTrue(
            context.isExternalClipboardShareUri(
                Uri.parse("content://settings/system/screen_brightness"),
                readGrant,
            ),
        )
        listOf(
            Uri.parse("content://settings/system/screen_brightness") to 0,
            Uri.parse("content://external.example/image/1") to readGrant,
            Uri.parse("file:///data/user/0/${context.packageName}/private.png") to readGrant,
            Uri.parse("content://${ClipboardMediaProvider.AUTHORITY}/clips/images/1") to readGrant,
            Uri.parse(
                "content://${OreoSystemClipboardMediaProvider.AUTHORITY}/clips/images/1",
            ) to readGrant,
            Uri.parse("content://10@${context.packageName}.provider.file/backup/private.png") to readGrant,
            Uri.parse(
                "content://${ClipboardMediaProvider.AUTHORITY.replace(".", "%2E")}" +
                    "/clips/images/1",
            ) to readGrant,
        ).forEach { (uri, flags) ->
            assertTrue(!context.isExternalClipboardShareUri(uri, flags))
        }
    }

    @Test
    fun exportedClipboardShareFailsClosedWhenProviderResolutionThrows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failingContext = object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager {
                throw IllegalStateException("provider lookup unavailable")
            }
        }

        assertFalse(
            failingContext.isExternalClipboardShareUri(
                Uri.parse("content://external.example/image/1"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ),
        )
    }

    @Test
    fun ownedUrisRoundTripOnlyForTheirExactTypeAndAuthority() {
        val image = requireNotNull(OwnedClipboardMediaUri.create(42L, ItemType.IMAGE))
        val video = requireNotNull(OwnedClipboardMediaUri.create(73L, ItemType.VIDEO))

        assertEquals(
            "content://${ClipboardMediaProvider.AUTHORITY}/clips/images/42",
            image.uri.toString(),
        )
        assertEquals(image, OwnedClipboardMediaUri.parse(image.uri, ItemType.IMAGE))
        assertEquals(video, OwnedClipboardMediaUri.parse(video.uri))
        assertNull(OwnedClipboardMediaUri.parse(image.uri, ItemType.VIDEO))
        assertNull(OwnedClipboardMediaUri.create(42L, ItemType.TEXT))
        assertNull(OwnedClipboardMediaUri.create(0L, ItemType.IMAGE))
    }

    @Test
    fun systemClipboardUrisUseAndCanonicalizeTheOreoProxyAuthority() {
        val image = requireNotNull(OwnedClipboardMediaUri.create(42L, ItemType.IMAGE))
        val oreoUri = systemClipboardMediaUri(image, sdkInt = 26)
        val modernUri = systemClipboardMediaUri(image, sdkInt = 28)

        assertEquals(
            "content://${OreoSystemClipboardMediaProvider.AUTHORITY}/clips/images/42",
            oreoUri.toString(),
        )
        assertEquals(image.uri, modernUri)
        assertNull(OwnedClipboardMediaUri.parse(oreoUri))
        assertEquals(
            image,
            OwnedClipboardMediaUri.parseOreoSystemClipboard(oreoUri, sdkInt = 26),
        )
        assertEquals(image.uri, requireNotNull(
            OwnedClipboardMediaUri.parseOreoSystemClipboard(oreoUri, sdkInt = 26),
        ).uri)
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
            assertNull(OwnedClipboardMediaUri.parseOreoSystemClipboard(oreoUri))
        }
    }

    @Test
    fun oreoProxyRequiresLiveClipboardAndDurableCurrentBootRoot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var platformClipboard: ClipboardManager
        instrumentation.runOnMainSync {
            platformClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        }
        val source = Files.createTempFile(context.cacheDir.toPath(), "oreo-proxy-", ".bin")
        Files.write(source, byteArrayOf(42))
        val previousRoots = ClipboardFileStorage.systemRoots(context)
        val previousClip = runCatching { platformClipboard.primaryClip }.getOrNull()
        var installed: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 1L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val owned = requireNotNull(installed).ownedUri
            val proxyUri = systemClipboardMediaUri(owned, sdkInt = 26)

            assertFalse(canOpen(context, proxyUri))
            assertNull(resolveObservedSystemClipboardMedia(context, proxyUri, ItemType.IMAGE))
            ClipboardFileStorage.prepareSystemRoots(context, setOf(owned))
            ClipboardFileStorage.markActive(context, listOf(owned))
            assertNotNull(ClipboardFileStorage.currentSystemRootFileInfo(context, owned))

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                platformClipboard.setPrimaryClip(
                    ClipData(
                        ClipDescription("Clipboard media test", arrayOf("image/png")),
                        ClipData.Item(proxyUri),
                    ),
                )
                assertOreoReadSurfacesAvailable(context, proxyUri)
                assertEquals(
                    owned,
                    resolveObservedSystemClipboardMedia(context, proxyUri, ItemType.IMAGE),
                )

                platformClipboard.setPrimaryClip(
                    ClipData(
                        ClipDescription("Clipboard media test", arrayOf("image/png")),
                        ClipData.Item(Intent(Intent.ACTION_VIEW, proxyUri)),
                    ),
                )
                assertOreoReadSurfacesAvailable(context, proxyUri)

                platformClipboard.setPrimaryClip(ClipData.newPlainText("Clipboard media test", "replacement"))
                ClipboardFileStorage.prepareSystemRoots(context, setOf(owned))
                ClipboardFileStorage.markActive(context, listOf(owned))
                assertNotNull(ClipboardFileStorage.currentSystemRootFileInfo(context, owned))
                assertOreoReadSurfacesUnavailable(context, proxyUri)

                val malformed = proxyUri.buildUpon().appendQueryParameter("unexpected", "1").build()
                assertOreoReadSurfacesUnavailable(context, malformed)
            } else {
                assertOreoReadSurfacesUnavailable(context, proxyUri)
            }

            ClipboardFileStorage.recordSystemRoots(context, previousRoots)
            ClipboardFileStorage.markRetiring(context, listOf(owned))
            assertFalse(canOpen(context, proxyUri))
            assertNull(resolveObservedSystemClipboardMedia(context, proxyUri, ItemType.IMAGE))
        } finally {
            runCatching {
                if (previousClip != null) {
                    platformClipboard.setPrimaryClip(previousClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    platformClipboard.clearPrimaryClip()
                } else {
                    platformClipboard.setPrimaryClip(
                        ClipData.newPlainText("Clipboard media test", ""),
                    )
                }
            }
            runCatching { ClipboardFileStorage.recordSystemRoots(context, previousRoots) }
            installed?.let { media ->
                runCatching { ClipboardFileStorage.markRetiring(context, listOf(media.ownedUri)) }
                runCatching {
                    ClipboardFileStorage.deleteOwned(
                        context,
                        media.ownedUri,
                        observedBootCount = Int.MAX_VALUE,
                    )
                }
                runCatching { media.cleanup() }
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun ownedUrisRejectEveryNoncanonicalShape() {
        val authority = ClipboardMediaProvider.AUTHORITY
        listOf(
            "content://foreign.provider.clipboard/clips/images/42",
            "CONTENT://$authority/clips/images/42",
            "content://$authority/clips/image/42",
            "content://$authority/clips/videos/42",
            "content://$authority/clips/images",
            "content://$authority/clips/images/",
            "content://$authority/clips/images/0",
            "content://$authority/clips/images/00",
            "content://$authority/clips/images/042",
            "content://$authority/clips/images/+42",
            "content://$authority/clips/images/-42",
            "content://$authority/clips/images/42/",
            "content://$authority/clips/images/42/extra",
            "content://$authority/clips/images/%34%32",
            "content://$authority/clips/images/42?query=1",
            "content://$authority/clips/images/42#fragment",
            "content://user@$authority/clips/images/42",
            "content://$authority/clips/images/9223372036854775808",
            "file://$authority/clips/images/42",
            "",
        ).forEach { raw ->
            assertNull(raw, OwnedClipboardMediaUri.parse(Uri.parse(raw), ItemType.IMAGE))
        }
    }

    @Test
    fun ownedUriSummariesDoNotRevealIds() {
        val marker = "98437561"
        val owned = OwnedClipboardMediaUri.create(marker.toLong(), ItemType.IMAGE)

        assertNotNull(owned)
        assertTrue(owned.toString(), marker !in owned.toString())
    }

    private fun canOpen(
        context: Context,
        uri: Uri,
    ): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun assertOreoReadSurfacesAvailable(
        context: Context,
        uri: Uri,
    ) {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        } ?: error("Oreo clipboard media query was unavailable.")
        assertEquals("image/png", context.contentResolver.getType(uri))
        assertArrayEquals(
            arrayOf("image/png"),
            context.contentResolver.getStreamTypes(uri, "image/*"),
        )
        assertTrue(canOpen(context, uri))
    }

    private fun assertOreoReadSurfacesUnavailable(
        context: Context,
        uri: Uri,
    ) {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { true } ?: false
        }.getOrDefault(false)
        assertFalse(queried)
        assertNull(runCatching { context.contentResolver.getType(uri) }.getOrNull())
        assertNull(
            runCatching {
                context.contentResolver.getStreamTypes(uri, "image/*")
            }.getOrNull(),
        )
        assertFalse(canOpen(context, uri))
    }
}
