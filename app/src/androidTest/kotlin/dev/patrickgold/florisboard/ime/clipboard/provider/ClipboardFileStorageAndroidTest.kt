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

import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.clipboard.ClipboardShareRequestFingerprint
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardFileStorageAndroidTest {
    @Test
    fun pendingShareSurvivesStartupStyleReconciliationOnlyUntilItsExpiry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source =
            Files.createTempFile(context.cacheDir.toPath(), "clipboard-share-pending-", ".bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        var installed: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 3L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                shareOperationToken = ClipboardShareOperationToken.create(),
                shareRequestFingerprint = requireNotNull(
                    ClipboardShareRequestFingerprint.parse("c".repeat(64)),
                ),
            )
            val owned = requireNotNull(installed).ownedUri
            val info = requireNotNull(
                ClipboardFileStorage.fileInfo(context, owned),
            )
            val bootCount = requireNotNull(info.sharePendingBootCount)
            val deadline = info.sharePendingDeadlineElapsedRealtimeMs

            ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = false,
                observedBootCount = bootCount,
                shareElapsedRealtimeMs = deadline - 1L,
            )
            assertEquals(
                ClipboardMediaOwnershipState.PENDING,
                ClipboardFileStorage.fileInfo(context, owned)?.ownershipState,
            )

            ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = false,
                observedBootCount = bootCount,
                shareElapsedRealtimeMs = deadline,
            )
            assertNull(ClipboardFileStorage.fileInfo(context, owned))
        } finally {
            runCatching { installed?.cleanup() }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun unavailableBootCountDefersPendingShareUntilItCanBeVerified() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source =
            Files.createTempFile(context.cacheDir.toPath(), "clipboard-share-unknown-boot-", ".bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        var installed: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 3L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                shareOperationToken = ClipboardShareOperationToken.create(),
                shareRequestFingerprint = requireNotNull(
                    ClipboardShareRequestFingerprint.parse("d".repeat(64)),
                ),
            )
            val owned = requireNotNull(installed).ownedUri
            val info = requireNotNull(ClipboardFileStorage.fileInfo(context, owned))
            val bootCount = requireNotNull(info.sharePendingBootCount)
            val beforeDeadline = info.sharePendingDeadlineElapsedRealtimeMs - 1L

            ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = false,
                observedBootCount = -1,
                shareElapsedRealtimeMs = beforeDeadline,
            )
            assertEquals(
                ClipboardMediaOwnershipState.PENDING,
                ClipboardFileStorage.fileInfo(context, owned)?.ownershipState,
            )

            ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = false,
                observedBootCount = bootCount,
                shareElapsedRealtimeMs = beforeDeadline,
            )
            assertEquals(
                ClipboardMediaOwnershipState.PENDING,
                ClipboardFileStorage.fileInfo(context, owned)?.ownershipState,
            )
        } finally {
            runCatching { installed?.cleanup() }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun rebootExpiresPendingShareBeforeItsMonotonicDeadline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source =
            Files.createTempFile(context.cacheDir.toPath(), "clipboard-share-reboot-", ".bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        var installed: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 3L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                shareOperationToken = ClipboardShareOperationToken.create(),
                shareRequestFingerprint = requireNotNull(
                    ClipboardShareRequestFingerprint.parse("e".repeat(64)),
                ),
            )
            val owned = requireNotNull(installed).ownedUri
            val info = requireNotNull(ClipboardFileStorage.fileInfo(context, owned))
            val bootCount = requireNotNull(info.sharePendingBootCount)
            val nextBootCount =
                if (bootCount == Int.MAX_VALUE) bootCount - 1 else bootCount + 1

            ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = false,
                observedBootCount = nextBootCount,
                shareElapsedRealtimeMs = info.sharePendingDeadlineElapsedRealtimeMs - 1L,
            )

            assertNull(ClipboardFileStorage.fileInfo(context, owned))
        } finally {
            runCatching { installed?.cleanup() }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun shareOperationTokenResolvesOneExactTypedInstall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstBytes = byteArrayOf(1, 2, 3)
        val laterBytes = byteArrayOf(7, 8, 9, 10)
        val firstSource =
            Files.createTempFile(context.cacheDir.toPath(), "clipboard-share-first-", ".bin")
        val laterSource =
            Files.createTempFile(context.cacheDir.toPath(), "clipboard-share-later-", ".bin")
        Files.write(firstSource, firstBytes)
        Files.write(laterSource, laterBytes)
        val token = ClipboardShareOperationToken.create()
        val fingerprint = requireNotNull(
            ClipboardShareRequestFingerprint.parse("a".repeat(64)),
        )
        var installed: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = firstSource,
                expectedBytes = firstBytes.size.toLong(),
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                shareOperationToken = token,
                shareRequestFingerprint = fingerprint,
            )
            val retried = ClipboardFileStorage.installFromBackup(
                context = context,
                source = laterSource,
                expectedBytes = laterBytes.size.toLong(),
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/jpeg"),
                shareOperationToken = token,
                shareRequestFingerprint = fingerprint,
            )

            assertEquals(installed.ownedUri, retried.ownedUri)
            assertArrayEquals(
                firstBytes,
                Files.readAllBytes(
                    requireNotNull(
                        ClipboardFileStorage.ownedFile(context, retried.ownedUri),
                    ).toPath(),
                ),
            )
            assertEquals(
                token.value,
                ClipboardFileStorage.fileInfo(context, retried.ownedUri)?.shareOperationToken,
            )
            assertEquals(
                fingerprint.value,
                ClipboardFileStorage.fileInfo(context, retried.ownedUri)
                    ?.shareRequestFingerprint,
            )

            val mismatchedType = runCatching {
                ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = laterSource,
                    expectedBytes = laterBytes.size.toLong(),
                    type = ItemType.VIDEO,
                    mimeTypes = listOf("video/mp4"),
                    shareOperationToken = token,
                    shareRequestFingerprint = fingerprint,
                )
            }.exceptionOrNull() as? ClipboardMediaStorageException
            assertEquals(ClipboardMediaStorageFailure.INVALID_METADATA, mismatchedType?.failure)
            val mismatchedFingerprint = runCatching {
                ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = laterSource,
                    expectedBytes = laterBytes.size.toLong(),
                    type = ItemType.IMAGE,
                    mimeTypes = listOf("image/jpeg"),
                    shareOperationToken = token,
                    shareRequestFingerprint = requireNotNull(
                        ClipboardShareRequestFingerprint.parse("b".repeat(64)),
                    ),
                )
            }.exceptionOrNull() as? ClipboardMediaStorageException
            assertEquals(
                ClipboardMediaStorageFailure.INVALID_METADATA,
                mismatchedFingerprint?.failure,
            )
            assertNull(ClipboardShareOperationToken.parse(token.value.uppercase()))
            assertNull(ClipboardShareOperationToken.parse("not-a-token"))
        } finally {
            runCatching { installed?.cleanup() }
            Files.deleteIfExists(firstSource)
            Files.deleteIfExists(laterSource)
        }
    }

    @Test
    fun pendingSharePublicationClaimIsExactDurableAndOneShot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source =
            Files.createTempFile(context.cacheDir.toPath(), "clipboard-share-claim-", ".bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        val token = ClipboardShareOperationToken.create()
        val fingerprint = requireNotNull(
            ClipboardShareRequestFingerprint.parse("1".repeat(64)),
        )
        var installed: InstalledClipboardMedia? = null
        var claimedBootCount: Int? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 3L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                shareOperationToken = token,
                shareRequestFingerprint = fingerprint,
            )
            val owned = requireNotNull(installed).ownedUri
            assertNull(
                ClipboardFileStorage.claimPendingShareForPublication(
                    context = context,
                    ownedUri = owned,
                    token = token,
                    requestFingerprint = requireNotNull(
                        ClipboardShareRequestFingerprint.parse("2".repeat(64)),
                    ),
                ),
            )
            assertEquals(
                ClipboardMediaOwnershipState.PENDING,
                ClipboardFileStorage.fileInfo(context, owned)?.ownershipState,
            )

            val claimed = requireNotNull(
                ClipboardFileStorage.claimPendingShareForPublication(
                    context = context,
                    ownedUri = owned,
                    token = token,
                    requestFingerprint = fingerprint,
                ),
            )
            claimedBootCount = claimed.externalCapabilityBootCount
            assertEquals(ClipboardMediaOwnershipState.ACTIVE, claimed.ownershipState)
            assertTrue(claimed.isSystemRoot)
            assertNull(claimed.sharePendingBootCount)
            assertEquals(0L, claimed.sharePendingDeadlineElapsedRealtimeMs)
            assertNull(
                ClipboardFileStorage.claimPendingShareForPublication(
                    context = context,
                    ownedUri = owned,
                    token = token,
                    requestFingerprint = fingerprint,
                ),
            )
        } finally {
            val owned = installed?.ownedUri
            if (owned != null) {
                runCatching {
                    ClipboardFileStorage.recordSystemRoots(
                        context,
                        emptySet(),
                        observedBootCount = claimedBootCount ?: ACTIVE_BOOT_COUNT,
                    )
                }
                runCatching {
                    val stamped = claimedBootCount ?: ACTIVE_BOOT_COUNT
                    val laterBoot = if (stamped == Int.MAX_VALUE) stamped - 1 else stamped + 1
                    ClipboardFileStorage.deleteOwned(
                        context,
                        owned,
                        observedBootCount = laterBoot,
                    )
                }
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun installsTypedMediaDurablyAndCleansUpOnlyItsReceipt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceBytes = byteArrayOf(1, 3, 3, 7)
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-source-", ".bin")
        Files.write(source, sourceBytes)
        var image: InstalledClipboardMedia? = null
        var video: InstalledClipboardMedia? = null

        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                image = ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = source,
                    expectedBytes = sourceBytes.size.toLong(),
                    type = ItemType.IMAGE,
                    mimeTypes = listOf("application/octet-stream", "IMAGE/PNG"),
                )
                video = ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = source,
                    expectedBytes = sourceBytes.size.toLong(),
                    type = ItemType.VIDEO,
                    mimeTypes = listOf("video/mp4"),
                )
            }
            val installedImage = requireNotNull(image)
            val installedVideo = requireNotNull(video)

            assertTrue(installedImage.ownedUri.id > 0L)
            assertTrue(installedVideo.ownedUri.id > 0L)
            assertNotEquals(installedImage.ownedUri.id, installedVideo.ownedUri.id)
            assertEquals(
                listOf("application/octet-stream", "image/png"),
                ClipboardFileStorage.fileInfo(context, installedImage.ownedUri)?.mimeTypes,
            )
            assertEquals(0, ClipboardFileStorage.fileInfo(context, installedVideo.ownedUri)?.orientation)
            assertNull(
                ClipboardFileStorage.fileInfo(
                    context,
                    requireNotNull(
                        OwnedClipboardMediaUri.create(installedImage.ownedUri.id, ItemType.VIDEO),
                    ),
                ),
            )
            val expectedProviderType =
                "image/png".takeIf {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                }
            assertEquals(
                expectedProviderType,
                context.contentResolver.getType(installedImage.ownedUri.uri),
            )
            ParcelFileDescriptor.AutoCloseInputStream(
                requireNotNull(
                    context.contentResolver.openFileDescriptor(installedImage.ownedUri.uri, "r"),
                ),
            ).use { input ->
                assertArrayEquals(sourceBytes, input.readBytes())
            }
            val writeResult = runCatching {
                context.contentResolver.openFileDescriptor(installedImage.ownedUri.uri, "w")?.close()
            }
            assertNotNull(writeResult.exceptionOrNull())

            assertTrue(installedImage.cleanup())
            assertTrue(installedImage.cleanup())
            assertNull(ClipboardFileStorage.fileInfo(context, installedImage.ownedUri))
            assertNotNull(ClipboardFileStorage.fileInfo(context, installedVideo.ownedUri))
        } finally {
            runCatching { image?.cleanup() }
            runCatching { video?.cleanup() }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun reconciliationRetainsLivePendingInstallWithoutPromotingIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-pending-", ".bin")
        Files.write(source, byteArrayOf(1))
        var installed: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 1L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val ownedUri = requireNotNull(installed).ownedUri

            val failed = ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = true,
            )

            assertTrue(failed.isEmpty())
            assertEquals(
                ClipboardMediaOwnershipState.PENDING,
                ClipboardFileStorage.fileInfo(context, ownedUri)?.ownershipState,
            )
            assertTrue(requireNotNull(installed).cleanup())
            assertNull(ClipboardFileStorage.fileInfo(context, ownedUri))
        } finally {
            runCatching { installed?.cleanup() }
            installed?.ownedUri?.let { runCatching { ClipboardFileStorage.deleteOwned(context, it) } }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun reconciliationRetainsDurablePasteRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-paste-", ".bin")
        Files.write(source, byteArrayOf(1))
        var installed: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 1L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val ownedUri = requireNotNull(installed).ownedUri
            ClipboardFileStorage.markPasteRoot(
                context,
                ownedUri,
                observedBootCount = ACTIVE_BOOT_COUNT,
            )

            val failed = ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = true,
            )

            assertTrue(failed.isEmpty())
            assertEquals(setOf(ownedUri), ClipboardFileStorage.pasteRoots(context))
            assertTrue(
                requireNotNull(
                    ClipboardFileStorage.fileInfo(context, ownedUri),
                ).pasteRetainedUntilMs > System.currentTimeMillis(),
            )
            assertFalse(requireNotNull(installed).cleanup())
            assertNotNull(ClipboardFileStorage.fileInfo(context, ownedUri))
        } finally {
            ClipboardFileStorage.trimPasteRoots(context, now = Long.MAX_VALUE)
            installed?.ownedUri?.let {
                runCatching {
                    ClipboardFileStorage.deleteOwned(
                        context,
                        it,
                        observedBootCount = NEXT_BOOT_COUNT,
                    )
                }
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun directDeletionCannotUnlinkDurableDeliveryRoots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-durable-", ".bin")
        Files.write(source, byteArrayOf(1))
        val installed = mutableListOf<InstalledClipboardMedia>()

        try {
            repeat(2) {
                installed += ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = source,
                    expectedBytes = 1L,
                    type = ItemType.IMAGE,
                    mimeTypes = listOf("image/png"),
                )
            }
            val systemRoot = installed[0].ownedUri
            val pasteRoot = installed[1].ownedUri
            ClipboardFileStorage.prepareSystemRoots(
                context,
                setOf(systemRoot),
                observedBootCount = ACTIVE_BOOT_COUNT,
            )
            ClipboardFileStorage.markPasteRoot(
                context,
                pasteRoot,
                observedBootCount = ACTIVE_BOOT_COUNT,
            )

            assertFalse(
                ClipboardFileStorage.deleteOwned(
                    context,
                    systemRoot,
                    observedBootCount = ACTIVE_BOOT_COUNT,
                ),
            )
            assertFalse(
                ClipboardFileStorage.deleteOwned(
                    context,
                    pasteRoot,
                    observedBootCount = ACTIVE_BOOT_COUNT,
                ),
            )
            assertNotNull(ClipboardFileStorage.fileInfo(context, systemRoot))
            assertNotNull(ClipboardFileStorage.fileInfo(context, pasteRoot))

            ClipboardFileStorage.recordSystemRoots(
                context,
                emptySet(),
                observedBootCount = ACTIVE_BOOT_COUNT,
            )
            ClipboardFileStorage.trimPasteRoots(context, now = Long.MAX_VALUE)
            assertFalse(
                ClipboardFileStorage.deleteOwned(
                    context,
                    systemRoot,
                    observedBootCount = ACTIVE_BOOT_COUNT,
                ),
            )
            assertFalse(
                ClipboardFileStorage.deleteOwned(
                    context,
                    pasteRoot,
                    observedBootCount = ACTIVE_BOOT_COUNT,
                ),
            )
            assertTrue(
                ClipboardFileStorage.deleteOwned(
                    context,
                    systemRoot,
                    observedBootCount = NEXT_BOOT_COUNT,
                ),
            )
            assertTrue(
                ClipboardFileStorage.deleteOwned(
                    context,
                    pasteRoot,
                    observedBootCount = NEXT_BOOT_COUNT,
                ),
            )
        } finally {
            ClipboardFileStorage.recordSystemRoots(
                context,
                emptySet(),
                observedBootCount = ACTIVE_BOOT_COUNT,
            )
            ClipboardFileStorage.trimPasteRoots(context, now = Long.MAX_VALUE)
            installed.forEach { media ->
                runCatching {
                    ClipboardFileStorage.deleteOwned(
                        context,
                        media.ownedUri,
                        observedBootCount = NEXT_BOOT_COUNT,
                    )
                }
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun rejectedPasteRestoresOnlyItsPreviousRetention() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-rejected-", ".bin")
        Files.write(source, byteArrayOf(1))
        val installed = mutableListOf<InstalledClipboardMedia>()

        try {
            installed += ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 1L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val ownedUri = installed.single().ownedUri
            val first = ClipboardFileStorage.markPasteRoot(
                context,
                ownedUri,
                observedBootCount = ACTIVE_BOOT_COUNT,
            )
            val firstReceipt = requireNotNull(first.receipt)
            ClipboardFileStorage.completePasteAdmission(firstReceipt)
            val firstDeadline =
                ClipboardFileStorage.fileInfo(context, ownedUri)?.pasteRetainedUntilMs
            val second = ClipboardFileStorage.markPasteRoot(
                context,
                ownedUri,
                observedBootCount = ACTIVE_BOOT_COUNT,
            )

            val expired = ClipboardFileStorage.abortPasteAdmission(
                context,
                requireNotNull(second.receipt),
            )

            assertTrue(expired.isEmpty())
            assertEquals(
                firstDeadline,
                ClipboardFileStorage.fileInfo(context, ownedUri)?.pasteRetainedUntilMs,
            )

            val fresh = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 1L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            installed += fresh
            val freshAdmission = ClipboardFileStorage.markPasteRoot(
                context,
                fresh.ownedUri,
                observedBootCount = ACTIVE_BOOT_COUNT,
            )
            assertEquals(
                setOf(fresh.ownedUri),
                ClipboardFileStorage.abortPasteAdmission(
                    context,
                    requireNotNull(freshAdmission.receipt),
                ),
            )
            assertTrue(ClipboardFileStorage.pasteRoots(context).contains(ownedUri))
            assertFalse(ClipboardFileStorage.pasteRoots(context).contains(fresh.ownedUri))
            assertNull(
                ClipboardFileStorage.fileInfo(context, fresh.ownedUri)
                    ?.externalCapabilityBootCount,
            )
            assertTrue(
                ClipboardFileStorage.deleteOwned(
                    context,
                    fresh.ownedUri,
                    observedBootCount = ACTIVE_BOOT_COUNT,
                ),
            )
        } finally {
            ClipboardFileStorage.trimPasteRoots(context, now = Long.MAX_VALUE)
            installed.forEach { media ->
                runCatching {
                    ClipboardFileStorage.deleteOwned(
                        context,
                        media.ownedUri,
                        observedBootCount = NEXT_BOOT_COUNT,
                    )
                }
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun anotherAdmissionCannotInvalidateAnUnresolvedReceipt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-receipt-", ".bin")
        Files.write(source, byteArrayOf(1))
        val installed = mutableListOf<InstalledClipboardMedia>()

        try {
            repeat(2) {
                installed += ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = source,
                    expectedBytes = 1L,
                    type = ItemType.IMAGE,
                    mimeTypes = listOf("image/png"),
                )
            }
            val first = installed[0].ownedUri
            val second = installed[1].ownedUri
            val firstAdmission = ClipboardFileStorage.markPasteRoot(
                context,
                first,
                observedBootCount = ACTIVE_BOOT_COUNT,
            )
            val firstReceipt = requireNotNull(firstAdmission.receipt)
            val firstDeadline =
                ClipboardFileStorage.fileInfo(context, first)?.pasteRetainedUntilMs
            Thread.sleep(5)

            val secondAdmission = ClipboardFileStorage.markPasteRoot(
                context = context,
                ownedUri = second,
                protectedRoots = setOf(first, second),
                observedBootCount = ACTIVE_BOOT_COUNT,
            )

            assertEquals(
                firstDeadline,
                ClipboardFileStorage.fileInfo(context, first)?.pasteRetainedUntilMs,
            )
            assertEquals(
                setOf(first),
                ClipboardFileStorage.abortPasteAdmission(context, firstReceipt),
            )
            assertFalse(ClipboardFileStorage.pasteRoots(context).contains(first))
            ClipboardFileStorage.abortPasteAdmission(
                context,
                requireNotNull(secondAdmission.receipt),
            )
        } finally {
            ClipboardFileStorage.trimPasteRoots(context, now = Long.MAX_VALUE)
            installed.forEach { media ->
                runCatching {
                    ClipboardFileStorage.deleteOwned(
                        context,
                        media.ownedUri,
                        observedBootCount = NEXT_BOOT_COUNT,
                    )
                }
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun reconciliationHonorsDeletionReservation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-reserved-", ".bin")
        Files.write(source, byteArrayOf(1))
        var installed: InstalledClipboardMedia? = null

        try {
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 1L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val ownedUri = requireNotNull(installed).ownedUri
            ClipboardFileStorage.markActive(context, listOf(ownedUri))
            ClipboardFileStorage.markRetiring(context, listOf(ownedUri))
            var released = false

            val failed = ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = true,
                reserveDeletion = { false },
                releaseDeletion = { released = true },
            )

            assertTrue(ownedUri in failed)
            assertFalse(released)
            assertNotNull(ClipboardFileStorage.fileInfo(context, ownedUri))

            var stabilityChecks = 0
            val interrupted = ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = emptySet(),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = true,
                reserveDeletion = { true },
                releaseDeletion = { released = true },
                isSystemStateCurrent = {
                    stabilityChecks += 1
                    stabilityChecks < 3
                },
            )

            assertTrue(ownedUri in interrupted)
            assertTrue(released)
            assertNotNull(ClipboardFileStorage.fileInfo(context, ownedUri))

            assertTrue(
                ClipboardFileStorage.reconcileOwnership(
                    context = context,
                    historyRoots = emptySet(),
                    observedSystemRoots = emptySet(),
                    systemClipboardObserved = true,
                ).isEmpty(),
            )
            assertNull(ClipboardFileStorage.fileInfo(context, ownedUri))
        } finally {
            runCatching { installed?.cleanup() }
            installed?.ownedUri?.let { runCatching { ClipboardFileStorage.deleteOwned(context, it) } }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun reconciliationRemovesInterruptedInstallsAndRetainsDurableRoots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-owner-", ".bin")
        Files.write(source, byteArrayOf(1))
        val installed = mutableListOf<InstalledClipboardMedia>()

        try {
            repeat(3) {
                installed += ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = source,
                    expectedBytes = 1L,
                    type = ItemType.IMAGE,
                    mimeTypes = listOf("image/png"),
                )
            }
            val interrupted = installed[0].ownedUri
            val historyRoot = installed[1].ownedUri
            val systemRoot = installed[2].ownedUri
            ClipboardFileStorage.markActive(context, installed.map { it.ownedUri })
            ClipboardFileStorage.markRetiring(context, listOf(interrupted))
            ClipboardFileStorage.recordSystemRoots(
                context,
                setOf(systemRoot),
                observedBootCount = ACTIVE_BOOT_COUNT,
            )

            val failed = ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = setOf(historyRoot),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = false,
            )

            assertEquals(setOf(interrupted), failed)
            assertNotNull(ClipboardFileStorage.fileInfo(context, interrupted))
            assertNotNull(ClipboardFileStorage.fileInfo(context, historyRoot))
            assertNotNull(ClipboardFileStorage.fileInfo(context, systemRoot))
            assertEquals(setOf(systemRoot), ClipboardFileStorage.systemRoots(context))

            ClipboardFileStorage.markRetiring(context, listOf(systemRoot))
            ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = setOf(historyRoot),
                observedSystemRoots = emptySet(),
                systemClipboardObserved = true,
                observedBootCount = NEXT_BOOT_COUNT,
            )
            assertNull(ClipboardFileStorage.fileInfo(context, interrupted))
            assertNull(ClipboardFileStorage.fileInfo(context, systemRoot))
            assertNotNull(ClipboardFileStorage.fileInfo(context, historyRoot))
        } finally {
            installed.forEach { runCatching { it.cleanup() } }
            runCatching {
                ClipboardFileStorage.recordSystemRoots(
                    context,
                    emptySet(),
                    observedBootCount = ACTIVE_BOOT_COUNT,
                )
            }
            installed.forEach {
                runCatching {
                    ClipboardFileStorage.deleteOwned(
                        context,
                        it.ownedUri,
                        observedBootCount = NEXT_BOOT_COUNT,
                    )
                }
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun recoversMissingAndInvalidLegacyMetadataIdempotently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ClipboardFileStorage.initialize(context)
        val directory = context.noBackupFilesDir.toPath()
            .resolve(ClipboardFileStorage.CLIPBOARD_FILES_PATH)
        val missingId = unusedMediaId(directory)
        val invalidId = unusedMediaId(directory, setOf(missingId))
        val missingFile = directory.resolve(missingId.toString())
        val invalidFile = directory.resolve(invalidId.toString())
        val historyRows = mutableListOf<Long>()

        try {
            Files.write(missingFile, byteArrayOf(1, 2, 3))
            Files.write(invalidFile, byteArrayOf(4, 5, 6, 7))
            historyRows += insertHistoryRef(context, missingId, ItemType.IMAGE)
            historyRows += insertHistoryRef(context, invalidId, ItemType.VIDEO)
            ClipboardFilesDatabase.new(context).let { database ->
                try {
                    database.clipboardFilesDao().insert(
                        ClipboardFileInfo(
                            id = invalidId,
                            displayName = "Legacy video",
                            size = 99L,
                            orientation = 270,
                            mimeTypes = listOf("application/octet-stream"),
                            ownershipState = ClipboardMediaOwnershipState.LEGACY,
                            isSystemRoot = true,
                            pasteRetainedUntilMs = 42L,
                        ),
                    )
                } finally {
                    database.close()
                }
            }

            ClipboardFileStorage.recoverLegacyHistoryMedia(context)

            val missingUri = requireNotNull(
                OwnedClipboardMediaUri.create(missingId, ItemType.IMAGE),
            )
            val invalidUri = requireNotNull(
                OwnedClipboardMediaUri.create(invalidId, ItemType.VIDEO),
            )
            val missingInfo = requireNotNull(ClipboardFileStorage.fileInfo(context, missingUri))
            val repairedInfo = requireNotNull(ClipboardFileStorage.fileInfo(context, invalidUri))
            assertEquals("Image", missingInfo.displayName)
            assertEquals(3L, missingInfo.size)
            assertEquals(0, missingInfo.orientation)
            assertEquals(listOf("image/unknown"), missingInfo.mimeTypes)
            assertEquals(ClipboardMediaOwnershipState.ACTIVE, missingInfo.ownershipState)
            assertNotNull(missingInfo.externalCapabilityBootCount)
            assertNotEquals(
                LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT,
                missingInfo.externalCapabilityBootCount,
            )
            assertEquals("Legacy video", repairedInfo.displayName)
            assertEquals(4L, repairedInfo.size)
            assertEquals(0, repairedInfo.orientation)
            assertEquals(listOf("video/unknown"), repairedInfo.mimeTypes)
            assertEquals(ClipboardMediaOwnershipState.ACTIVE, repairedInfo.ownershipState)
            assertTrue(repairedInfo.isSystemRoot)
            assertEquals(42L, repairedInfo.pasteRetainedUntilMs)
            assertNotNull(repairedInfo.externalCapabilityBootCount)

            ClipboardFileStorage.recoverLegacyHistoryMedia(context)

            assertEquals(missingInfo, ClipboardFileStorage.fileInfo(context, missingUri))
            assertEquals(repairedInfo, ClipboardFileStorage.fileInfo(context, invalidUri))
        } finally {
            deleteHistoryRows(context, historyRows)
            deleteIfPresent(missingFile)
            deleteIfPresent(invalidFile)
            runCatching { ClipboardFileStorage.recoverLegacyHistoryMedia(context) }
        }
    }

    @Test
    fun reconciliationSanitizesValidLegacyDisplayNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ClipboardFileStorage.initialize(context)
        val directory = context.noBackupFilesDir.toPath()
            .resolve(ClipboardFileStorage.CLIPBOARD_FILES_PATH)
        val mediaId = unusedMediaId(directory)
        val mediaFile = directory.resolve(mediaId.toString())
        val marker = "private-legacy-name"
        val rawDisplayName =
            "\u0000$marker" + "x".repeat(ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH)
        val expectedDisplayName =
            "_$marker" +
                "x".repeat(
                    ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH - marker.length - 1,
                )
        val historyRows = mutableListOf<Long>()
        val legacyInfo = ClipboardFileInfo(
            id = mediaId,
            displayName = rawDisplayName,
            size = 3L,
            orientation = 0,
            mimeTypes = listOf("image/png"),
            ownershipState = ClipboardMediaOwnershipState.LEGACY,
        )

        try {
            Files.write(mediaFile, byteArrayOf(1, 2, 3))
            historyRows += insertHistoryRef(context, mediaId, ItemType.IMAGE)
            ClipboardFilesDatabase.new(context).let { database ->
                try {
                    database.clipboardFilesDao().insert(legacyInfo)
                } finally {
                    database.close()
                }
            }

            ClipboardFileStorage.recoverLegacyHistoryMedia(context)

            val ownedUri = requireNotNull(
                OwnedClipboardMediaUri.create(mediaId, ItemType.IMAGE),
            )
            val recoveredInfo = requireNotNull(
                ClipboardFileStorage.fileInfo(context, ownedUri),
            )
            assertEquals(expectedDisplayName, recoveredInfo.displayName)
            assertEquals(
                ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH,
                recoveredInfo.displayName.length,
            )
            assertTrue(recoveredInfo.displayName.none { it.isISOControl() })

            val persistedInfo = ClipboardFilesDatabase.new(context).let { database ->
                try {
                    requireNotNull(
                        database.clipboardFilesDao().getAll().singleOrNull { it.id == mediaId },
                    )
                } finally {
                    database.close()
                }
            }
            assertEquals(expectedDisplayName, persistedInfo.displayName)

            requireNotNull(
                context.contentResolver.query(
                    ownedUri.uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                ),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    expectedDisplayName,
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME),
                    ),
                )
                assertFalse(cursor.moveToNext())
            }

            listOf(
                legacyInfo.toString(),
                recoveredInfo.toString(),
                persistedInfo.toString(),
                ownedUri.toString(),
            ).forEach { summary ->
                assertFalse(summary.contains(marker))
                assertFalse(summary.contains(rawDisplayName))
                assertFalse(summary.contains(mediaId.toString()))
            }
        } finally {
            deleteHistoryRows(context, historyRows)
            deleteIfPresent(mediaFile)
            runCatching { ClipboardFileStorage.recoverLegacyHistoryMedia(context) }
        }
    }

    @Test
    fun reconciliationStreamsLargeMediaHistoryAndKeepsStorageUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ClipboardFileStorage.initialize(context)
        val directory = context.noBackupFilesDir.toPath()
            .resolve(ClipboardFileStorage.CLIPBOARD_FILES_PATH)
        val mediaIds = unusedMediaIdRange(context, directory, LARGE_HISTORY_ROW_COUNT)
        val deletableId = mediaIds.first
        val recoveredId = mediaIds.last
        val deletableFile = directory.resolve(deletableId.toString())
        val recoveredFile = directory.resolve(recoveredId.toString())
        var historyRows = emptyList<Long>()

        try {
            Files.write(deletableFile, byteArrayOf(1))
            Files.write(recoveredFile, byteArrayOf(2))
            ClipboardFilesDatabase.new(context).let { database ->
                try {
                    database.clipboardFilesDao().insert(
                        ClipboardFileInfo(
                            id = deletableId,
                            displayName = "Image",
                            size = 1L,
                            orientation = 0,
                            mimeTypes = listOf("image/png"),
                            ownershipState = ClipboardMediaOwnershipState.ACTIVE,
                        ),
                    )
                } finally {
                    database.close()
                }
            }
            historyRows = insertHistoryRefs(context, mediaIds)
            assertEquals(LARGE_HISTORY_ROW_COUNT, historyRows.size)

            ClipboardFileStorage.recoverLegacyHistoryMedia(context)

            val recoveredUri = requireNotNull(
                OwnedClipboardMediaUri.create(recoveredId, ItemType.IMAGE),
            )
            val deletableUri = requireNotNull(
                OwnedClipboardMediaUri.create(deletableId, ItemType.IMAGE),
            )
            assertNotNull(ClipboardFileStorage.fileInfo(context, recoveredUri))
            assertTrue(ClipboardFileStorage.deleteOwned(context, deletableUri))
            assertFalse(Files.exists(deletableFile, LinkOption.NOFOLLOW_LINKS))

            ClipboardFileStorage.recoverLegacyHistoryMedia(context)

            assertNotNull(ClipboardFileStorage.fileInfo(context, recoveredUri))
            assertNull(ClipboardFileStorage.fileInfo(context, deletableUri))
        } finally {
            deleteHistoryRows(context, historyRows)
            deleteIfPresent(deletableFile)
            deleteIfPresent(recoveredFile)
            runCatching { ClipboardFileStorage.recoverLegacyHistoryMedia(context) }
        }
    }

    @Test
    fun quarantinesConflictsButRejectsNoncanonicalAndNonregularLegacyFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ClipboardFileStorage.initialize(context)
        val directory = context.noBackupFilesDir.toPath()
            .resolve(ClipboardFileStorage.CLIPBOARD_FILES_PATH)
        val conflictId = unusedMediaId(directory)
        val userQualifiedId = unusedMediaId(directory, setOf(conflictId))
        val malformedId = unusedMediaId(directory, setOf(conflictId, userQualifiedId))
        val linkId = unusedMediaId(
            directory,
            setOf(conflictId, userQualifiedId, malformedId),
        )
        val directoryId = unusedMediaId(
            directory,
            setOf(conflictId, userQualifiedId, malformedId, linkId),
        )
        val conflictFile = directory.resolve(conflictId.toString())
        val userQualifiedFile = directory.resolve(userQualifiedId.toString())
        val malformedFile = directory.resolve(malformedId.toString())
        val linkedFile = directory.resolve(linkId.toString())
        val nonregularFile = directory.resolve(directoryId.toString())
        val linkTarget = Files.createTempFile(
            context.cacheDir.toPath(),
            "legacy-clipboard-link-target-",
            ".bin",
        )
        val historyRows = mutableListOf<Long>()

        try {
            Files.write(conflictFile, byteArrayOf(1))
            Files.write(userQualifiedFile, byteArrayOf(2))
            Files.write(malformedFile, byteArrayOf(3))
            Files.write(linkTarget, byteArrayOf(4))
            Files.createSymbolicLink(linkedFile, linkTarget)
            Files.createDirectory(nonregularFile)
            historyRows += insertHistoryRef(context, conflictId, ItemType.IMAGE)
            historyRows += insertHistoryRef(context, conflictId, ItemType.VIDEO)
            historyRows += insertHistoryRef(
                context = context,
                id = userQualifiedId,
                type = ItemType.IMAGE,
                rawUri = "content://10@${ClipboardMediaProvider.AUTHORITY}/clips/images/" +
                    userQualifiedId,
            )
            historyRows += insertHistoryRef(
                context = context,
                id = malformedId,
                type = ItemType.IMAGE,
                rawUri = requireNotNull(
                    OwnedClipboardMediaUri.create(malformedId, ItemType.IMAGE),
                ).uri.toString() + "?replayed=1",
            )
            historyRows += insertHistoryRef(context, linkId, ItemType.IMAGE)
            historyRows += insertHistoryRef(context, directoryId, ItemType.IMAGE)

            ClipboardFileStorage.recoverLegacyHistoryMedia(context)

            assertNull(
                ClipboardFileStorage.fileInfo(
                    context,
                    requireNotNull(
                        OwnedClipboardMediaUri.create(conflictId, ItemType.IMAGE),
                    ),
                ),
            )
            assertNull(
                ClipboardFileStorage.fileInfo(
                    context,
                    requireNotNull(
                        OwnedClipboardMediaUri.create(conflictId, ItemType.VIDEO),
                    ),
                ),
            )
            assertTrue(Files.isRegularFile(conflictFile, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(userQualifiedFile, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(malformedFile, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(linkedFile, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(nonregularFile, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(linkTarget, LinkOption.NOFOLLOW_LINKS))

            ClipboardFileStorage.recoverLegacyHistoryMedia(context)
            assertTrue(Files.isRegularFile(conflictFile, LinkOption.NOFOLLOW_LINKS))
        } finally {
            deleteHistoryRows(context, historyRows)
            deleteIfPresent(linkedFile)
            deleteIfPresent(nonregularFile)
            deleteIfPresent(conflictFile)
            deleteIfPresent(userQualifiedFile)
            deleteIfPresent(malformedFile)
            deleteIfPresent(linkTarget)
            runCatching { ClipboardFileStorage.recoverLegacyHistoryMedia(context) }
        }
    }

    @Test
    fun rejectsSymbolicLinkBackupSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = Files.createTempFile(context.cacheDir.toPath(), "clipboard-target-", ".bin")
        val link = target.resolveSibling("${target.fileName}.link")
        Files.write(target, byteArrayOf(1))

        try {
            Files.createSymbolicLink(link, target)
            val result = runCatching {
                ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = link,
                    expectedBytes = 1L,
                    type = ItemType.IMAGE,
                    mimeTypes = listOf("image/png"),
                )
            }

            assertEquals(
                ClipboardMediaStorageFailure.INVALID_SOURCE,
                (result.exceptionOrNull() as? ClipboardMediaStorageException)?.failure,
            )
        } finally {
            deleteIfPresent(link)
            deleteIfPresent(target)
        }
    }

    @Test
    fun rejectsBackupSourcesWhoseVerifiedSizeChanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-size-", ".bin")
        Files.write(source, byteArrayOf(1))

        try {
            val result = runCatching {
                ClipboardFileStorage.installFromBackup(
                    context = context,
                    source = source,
                    expectedBytes = 2L,
                    type = ItemType.IMAGE,
                    mimeTypes = listOf("image/png"),
                )
            }

            assertEquals(
                ClipboardMediaStorageFailure.INVALID_SOURCE,
                (result.exceptionOrNull() as? ClipboardMediaStorageException)?.failure,
            )
        } finally {
            deleteIfPresent(source)
        }
    }

    private fun deleteIfPresent(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    private fun unusedMediaId(directory: Path, excluded: Set<Long> = emptySet()): Long {
        val random = SecureRandom()
        repeat(128) {
            val id = random.nextLong().ushr(1)
            if (id > 0L &&
                id !in excluded &&
                Files.notExists(directory.resolve(id.toString()), LinkOption.NOFOLLOW_LINKS)
            ) {
                return id
            }
        }
        error("Could not allocate a legacy clipboard test ID.")
    }

    private fun unusedMediaIdRange(
        context: android.content.Context,
        directory: Path,
        count: Int,
    ): LongRange {
        require(count > 0)
        val occupiedIds = ClipboardFilesDatabase.new(context).let { database ->
            try {
                database.clipboardFilesDao().getAll().mapTo(mutableSetOf()) { it.id }
            } finally {
                database.close()
            }
        }
        Files.newDirectoryStream(directory).use { children ->
            children.mapNotNullTo(occupiedIds) { child ->
                child.fileName.toString().toLongOrNull()?.takeIf { it > 0L }
            }
        }
        val maximumStart = Long.MAX_VALUE - count.toLong()
        val random = SecureRandom()
        repeat(128) {
            val start = random.nextLong().ushr(1) % maximumStart + 1L
            val range = start..Math.addExact(start, count.toLong() - 1L)
            if (occupiedIds.none(range::contains)) return range
        }
        error("Could not allocate a legacy clipboard test ID range.")
    }

    private fun insertHistoryRef(
        context: android.content.Context,
        id: Long,
        type: ItemType,
        rawUri: String = requireNotNull(OwnedClipboardMediaUri.create(id, type)).uri.toString(),
    ): Long {
        val database = ClipboardHistoryDatabase.new(context)
        return try {
            database.clipboardItemDao().insert(
                ClipboardItem(
                    type = type,
                    text = null,
                    uri = rawUri.toUri(),
                    creationTimestampMs = 1L,
                    isPinned = true,
                    mimeTypes = listOf(
                        when (type) {
                            ItemType.IMAGE -> "image/unknown"
                            ItemType.VIDEO -> "video/unknown"
                            ItemType.TEXT -> error("Media history type expected.")
                        },
                    ),
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun insertHistoryRefs(
        context: android.content.Context,
        ids: LongRange,
    ): List<Long> {
        val database = ClipboardHistoryDatabase.new(context)
        return try {
            val rowIds = ArrayList<Long>(ids.count())
            database.runInTransaction {
                ids.forEach { id ->
                    rowIds += database.clipboardItemDao().insert(
                        ClipboardItem(
                            type = ItemType.IMAGE,
                            text = null,
                            uri = requireNotNull(
                                OwnedClipboardMediaUri.create(id, ItemType.IMAGE),
                            ).uri,
                            creationTimestampMs = 1L,
                            isPinned = true,
                            mimeTypes = listOf("image/unknown"),
                        ),
                    )
                }
            }
            rowIds
        } finally {
            database.close()
        }
    }

    private fun deleteHistoryRows(
        context: android.content.Context,
        rowIds: List<Long>,
    ) {
        if (rowIds.isEmpty()) return
        val database = ClipboardHistoryDatabase.new(context)
        try {
            database.runInTransaction {
                rowIds.forEach(database.clipboardItemDao()::delete)
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val ACTIVE_BOOT_COUNT = 42
        const val LARGE_HISTORY_ROW_COUNT = 4_097
        const val NEXT_BOOT_COUNT = 43
    }
}
