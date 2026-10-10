/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardMediaProviderAuthorizationAndroidTest {
    @Test
    fun crossUidMetadataAndDataRequireTheExactLiveGrant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testPackage = instrumentation.context.packageName
        val source = Files.createTempFile(context.cacheDir.toPath(), "provider-auth-", ".bin")
        Files.write(source, byteArrayOf(1, 3, 3, 7))
        var installed: InstalledClipboardMedia? = null
        var sibling: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 4L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            sibling = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 4L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val owned = requireNotNull(installed).ownedUri
            val siblingOwned = requireNotNull(sibling).ownedUri
            val expectedProviderType =
                "image/png".takeIf {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                }
            assertEquals(expectedProviderType, context.contentResolver.getType(owned.uri))
            assertEquals(
                null,
                ClipboardExternalMediaTestSource.probeColdTargetTypeBeforeGrant(owned.uri),
            )
            val beforeGrant = ClipboardExternalMediaTestSource.probeTargetMedia(owned.uri)
            assertTrue(beforeGrant.toString(), beforeGrant.nothingVisible)

            context.grantUriPermission(
                testPackage,
                owned.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            assertEquals(
                expectedProviderType,
                ClipboardExternalMediaTestSource.probeColdTargetTypeWhileGranted(owned.uri),
            )
            val whileGranted = ClipboardExternalMediaTestSource.probeTargetMedia(owned.uri)
            assertEquals(
                TargetMediaProbe(
                    queryVisible = true,
                    typeVisible =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    streamTypesVisible = true,
                    openVisible = true,
                    typedOpenVisible = true,
                    typedOpenReadable = true,
                    typedMismatchRejected = true,
                    typedOptionsRejected = true,
                    typedCancellationObserved = true,
                ),
                whileGranted,
            )
            val ungrantedSibling =
                ClipboardExternalMediaTestSource.probeTargetMedia(siblingOwned.uri)
            assertTrue(ungrantedSibling.toString(), ungrantedSibling.nothingVisible)

            context.revokeUriPermission(
                owned.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            assertEquals(
                null,
                ClipboardExternalMediaTestSource.probeColdTargetTypeAfterRevoke(owned.uri),
            )
            val afterRevoke = ClipboardExternalMediaTestSource.probeTargetMedia(owned.uri)
            assertTrue(afterRevoke.toString(), afterRevoke.nothingVisible)
        } finally {
            installed?.ownedUri?.let { owned ->
                context.revokeUriPermission(
                    owned.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            runCatching { installed?.cleanup() }
            runCatching { sibling?.cleanup() }
            Files.deleteIfExists(source)
        }
    }
}
