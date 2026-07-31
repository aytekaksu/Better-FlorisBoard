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

package dev.patrickgold.florisboard.ime.clipboard

import android.content.ClipData
import android.content.Context
import android.content.ClipboardManager as AndroidClipboardManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaImporter
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaTestSource
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaOwnershipState
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.clipboard.provider.StagedClipboardMedia
import dev.patrickgold.florisboard.test.EditorHarnessActivity
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardSharePreviewLoaderAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetSource() {
        ClipboardExternalMediaTestSource.reset()
        ClipboardExternalMediaTestSource.grantReadAccess()
        warmImportWorker()
        ClipboardExternalMediaTestSource.reset()
        ClipboardExternalMediaTestSource.grantReadAccess()
    }

    @After
    fun releaseSource() {
        ClipboardExternalMediaTestSource.releaseBlockingOpen()
        ClipboardExternalMediaTestSource.revokeReadAccess()
    }

    @Test
    fun undecodableDeclaredImageIsInstalledAndPublishedWithoutAPreview() = runBlocking {
        var published: OwnedClipboardMediaUri? = null

        try {
            val preview = ClipboardSharePreviewLoader.load(
                context = context,
                uri = ClipboardExternalMediaTestSource.svgUri,
                declaredMimeType = "image/*",
                operation = shareOperation(
                    ClipboardExternalMediaTestSource.svgUri,
                    "image/*",
                ),
                publishOwnedMedia = { owned, _, _ ->
                    published = owned
                    true
                },
            )

            assertTrue(preview != null)
            assertNull(preview?.bitmap)
            assertNull(preview?.mimeType)
            val owned = requireNotNull(published)
            val info = requireNotNull(ClipboardFileStorage.fileInfo(context, owned))
            assertEquals(listOf("image/svg+xml"), info.mimeTypes)
            assertEquals("_vector.svg", info.displayName)
            assertEquals(ClipboardMediaOwnershipState.PENDING, info.ownershipState)
            assertArrayEquals(
                ClipboardExternalMediaTestSource.svgBytes,
                Files.readAllBytes(requireNotNull(ClipboardFileStorage.ownedFile(context, owned)).toPath()),
            )
            assertTrue(shareStagingPartials().isEmpty())
        } finally {
            published?.let(::deleteOwned)
        }
    }

    @Test
    fun transverseExifPreviewIsOrientedWithoutChangingInstalledBytes() = runBlocking {
        var preview: ClipboardSharePreview? = null
        var published: OwnedClipboardMediaUri? = null

        try {
            preview = ClipboardSharePreviewLoader.load(
                context = context,
                uri = ClipboardExternalMediaTestSource.orientedJpegUri,
                declaredMimeType = "image/*",
                operation = shareOperation(
                    ClipboardExternalMediaTestSource.orientedJpegUri,
                    "image/*",
                ),
                publishOwnedMedia = { owned, _, _ ->
                    published = owned
                    true
                },
            )

            val bitmap = requireNotNull(preview?.bitmap)
            assertEquals(20, bitmap.width)
            assertEquals(40, bitmap.height)
            assertPixelColor(bitmap.getPixel(5, 5), red = true, green = true, blue = false)
            assertPixelColor(bitmap.getPixel(15, 5), red = false, green = true, blue = false)
            assertPixelColor(bitmap.getPixel(5, 35), red = false, green = false, blue = true)
            assertPixelColor(bitmap.getPixel(15, 35), red = true, green = false, blue = false)

            val owned = requireNotNull(published)
            val info = requireNotNull(ClipboardFileStorage.fileInfo(context, owned))
            assertEquals(listOf("image/jpeg"), info.mimeTypes)
            assertEquals("oriented.jpg", info.displayName)
            assertArrayEquals(
                ClipboardExternalMediaTestSource.orientedJpegBytes,
                Files.readAllBytes(requireNotNull(ClipboardFileStorage.ownedFile(context, owned)).toPath()),
            )
        } finally {
            preview?.bitmap?.recycle()
            published?.let(::deleteOwned)
        }
    }

    @Test
    fun restoredAttemptedOperationNeverReopensOrRepublishesTheSource() = runBlocking {
        val uri = ClipboardExternalMediaTestSource.svgUri
        val operation = shareOperation(uri, "image/svg+xml")
        var firstOwned: OwnedClipboardMediaUri? = null

        try {
            ClipboardSharePreviewLoader.load(
                context = context,
                uri = uri,
                declaredMimeType = "image/svg+xml",
                operation = operation,
                publishOwnedMedia = { owned, token, fingerprint ->
                    assertEquals(operation.token, token)
                    assertEquals(operation.requestFingerprint, fingerprint)
                    firstOwned = owned
                    ClipboardFileStorage.markActive(context, listOf(owned))
                    true
                },
            )
            assertEquals(1, ClipboardExternalMediaTestSource.openCount())

            val retriedPreview = ClipboardSharePreviewLoader.load(
                context = context,
                uri = uri,
                declaredMimeType = "image/svg+xml",
                operation = ClipboardShareOperation.resolve(
                    sourceUri = uri.toString(),
                    declaredMimeType = "image/svg+xml",
                    restoredToken = operation.token.value,
                    restoredRequestFingerprint = operation.requestFingerprint.value,
                ) ?: error("Restored share operation is invalid."),
                publishOwnedMedia = { _, _, _ ->
                    throw AssertionError("An attempted share was republished.")
                },
            )

            assertNull(retriedPreview)
            assertEquals(1, ClipboardExternalMediaTestSource.openCount())
            val info = requireNotNull(
                firstOwned?.let { ClipboardFileStorage.fileInfo(context, it) },
            )
            assertEquals(operation.token.value, info.shareOperationToken)
        } finally {
            firstOwned?.let(::deleteOwned)
        }
    }

    @Test
    fun restoredOperationWithoutItsDurableRowNeverImportsAgain() = runBlocking {
        val uri = ClipboardExternalMediaTestSource.svgUri
        val operation = shareOperation(uri, "image/svg+xml")
        val restored = requireNotNull(
            ClipboardShareOperation.resolve(
                sourceUri = uri.toString(),
                declaredMimeType = "image/svg+xml",
                restoredToken = operation.token.value,
                restoredRequestFingerprint = operation.requestFingerprint.value,
            ),
        )

        val preview = ClipboardSharePreviewLoader.load(
            context = context,
            uri = uri,
            declaredMimeType = "image/svg+xml",
            operation = restored,
            publishOwnedMedia = { _, _, _ ->
                throw AssertionError("A missing restored share was published.")
            },
        )

        assertNull(preview)
        assertEquals(0, ClipboardExternalMediaTestSource.openCount())
    }

    @Test
    fun restoredPendingInstallIsClaimedOnceWithoutReopeningItsSource() = runBlocking {
        val uri = ClipboardExternalMediaTestSource.svgUri
        val original = shareOperation(uri, "image/svg+xml")
        val source = Files.createTempFile(
            context.cacheDir.toPath(),
            "restored-pending-share-",
            ".svg",
        )
        var owned: OwnedClipboardMediaUri? = null
        var publicationCount = 0

        try {
            Files.write(source, ClipboardExternalMediaTestSource.svgBytes)
            owned = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = ClipboardExternalMediaTestSource.svgBytes.size.toLong(),
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/svg+xml"),
                displayName = "restored.svg",
                shareOperationToken = original.token,
                shareRequestFingerprint = original.requestFingerprint,
            ).ownedUri
            val restored = requireNotNull(
                ClipboardShareOperation.resolve(
                    sourceUri = uri.toString(),
                    declaredMimeType = "image/svg+xml",
                    restoredToken = original.token.value,
                    restoredRequestFingerprint = original.requestFingerprint.value,
                ),
            )
            assertTrue(restored.isRestored)

            val preview = ClipboardSharePreviewLoader.load(
                context = context,
                uri = uri,
                declaredMimeType = "image/svg+xml",
                operation = restored,
                publishOwnedMedia = { candidate, token, fingerprint ->
                    publicationCount += 1
                    assertEquals(owned, candidate)
                    assertEquals(original.token, token)
                    assertEquals(original.requestFingerprint, fingerprint)
                    ClipboardFileStorage.claimPendingShareForPublication(
                        context = context,
                        ownedUri = candidate,
                        token = token,
                        requestFingerprint = fingerprint,
                    ) != null
                },
            )

            assertTrue(preview != null)
            assertEquals(1, publicationCount)
            assertEquals(0, ClipboardExternalMediaTestSource.openCount())
            val claimed = requireNotNull(ClipboardFileStorage.fileInfo(context, owned))
            assertEquals(ClipboardMediaOwnershipState.ACTIVE, claimed.ownershipState)
            assertTrue(claimed.isSystemRoot)
            assertNull(
                ClipboardFileStorage.claimPendingShareForPublication(
                    context = context,
                    ownedUri = requireNotNull(owned),
                    token = original.token,
                    requestFingerprint = original.requestFingerprint,
                ),
            )
        } finally {
            owned?.let(::deleteOwned)
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun managerRejectsAnAttemptedShareWithoutOverwritingNewerClipboardData() = runBlocking {
        val manager = context.clipboardManager().value
        withTimeout(AWAIT_MS) {
            manager.awaitInitialization()
        }
        val source = Files.createTempFile(context.cacheDir.toPath(), "attempted-share-", ".svg")
        Files.write(source, ClipboardExternalMediaTestSource.svgBytes)
        val operation = shareOperation(
            ClipboardExternalMediaTestSource.svgUri,
            "image/svg+xml",
        )
        var owned: OwnedClipboardMediaUri? = null

        try {
            owned = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = ClipboardExternalMediaTestSource.svgBytes.size.toLong(),
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/svg+xml"),
                shareOperationToken = operation.token,
                shareRequestFingerprint = operation.requestFingerprint,
            ).ownedUri
            ClipboardFileStorage.markActive(context, listOf(requireNotNull(owned)))
            ActivityScenario.launch(EditorHarnessActivity::class.java).use { scenario ->
                lateinit var platformClipboard: AndroidClipboardManager
                var previousClip: ClipData? = null
                try {
                    scenario.onActivity { activity ->
                        platformClipboard =
                            activity.getSystemService(Context.CLIPBOARD_SERVICE)
                                as AndroidClipboardManager
                        previousClip =
                            runCatching { platformClipboard.primaryClip }.getOrNull()
                        platformClipboard.setPrimaryClip(
                            ClipData.newPlainText("Clipboard test", "newer"),
                        )
                        assertEquals(
                            "newer",
                            platformClipboard.primaryClip
                                ?.getItemAt(0)
                                ?.text
                                ?.toString(),
                        )
                    }

                    assertEquals(
                        false,
                        manager.publishOwnedClipboardShare(
                            ownedUri = requireNotNull(owned),
                            operationToken = operation.token,
                            requestFingerprint = operation.requestFingerprint,
                        ),
                    )
                    scenario.onActivity {
                        assertEquals(
                            "newer",
                            platformClipboard.primaryClip
                                ?.getItemAt(0)
                                ?.text
                                ?.toString(),
                        )
                    }
                } finally {
                    scenario.onActivity {
                        val restored = previousClip?.let {
                            runCatching { platformClipboard.setPrimaryClip(it) }.isSuccess
                        } ?: false
                        if (!restored) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                platformClipboard.clearPrimaryClip()
                            } else {
                                platformClipboard.setPrimaryClip(
                                    ClipData.newPlainText("Clipboard test", ""),
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            owned?.let(::deleteOwned)
            Files.deleteIfExists(source)
        }
        Unit
    }

    @Test
    fun operationBoundToAnotherRequestCannotReuseOrPublishMedia() = runBlocking {
        val mismatched = shareOperation(
            ClipboardExternalMediaTestSource.healthyUri,
            "image/unknown",
        )
        var published = false

        val preview = ClipboardSharePreviewLoader.load(
            context = context,
            uri = ClipboardExternalMediaTestSource.svgUri,
            declaredMimeType = "image/svg+xml",
            operation = mismatched,
            publishOwnedMedia = { _, _, _ ->
                published = true
                true
            },
        )

        assertNull(preview)
        assertEquals(0, ClipboardExternalMediaTestSource.openCount())
        assertEquals(false, published)
    }

    @Test
    fun failureAndCancellationDeleteOnlyUnpublishedPendingInstalls() = runBlocking {
        var failedPending: OwnedClipboardMediaUri? = null
        var cancelledPending: OwnedClipboardMediaUri? = null
        var promoted: OwnedClipboardMediaUri? = null

        try {
            assertNull(
                ClipboardSharePreviewLoader.load(
                    context = context,
                    uri = ClipboardExternalMediaTestSource.svgUri,
                    declaredMimeType = "image/svg+xml",
                    operation = shareOperation(
                        ClipboardExternalMediaTestSource.svgUri,
                        "image/svg+xml",
                    ),
                    publishOwnedMedia = { owned, _, _ ->
                        failedPending = owned
                        false
                    },
                ),
            )
            assertNull(failedPending?.let { ClipboardFileStorage.fileInfo(context, it) })

            val publishEntered = CompletableDeferred<Unit>()
            val cancelledLoad = async {
                ClipboardSharePreviewLoader.load(
                    context = context,
                    uri = ClipboardExternalMediaTestSource.svgUri,
                    declaredMimeType = "image/svg+xml",
                    operation = shareOperation(
                        ClipboardExternalMediaTestSource.svgUri,
                        "image/svg+xml",
                    ),
                    publishOwnedMedia = { owned, _, _ ->
                        cancelledPending = owned
                        publishEntered.complete(Unit)
                        awaitCancellation()
                    },
                )
            }
            publishEntered.await()
            cancelledLoad.cancelAndJoin()
            assertNull(cancelledPending?.let { ClipboardFileStorage.fileInfo(context, it) })

            assertTrue(
                runCatching {
                    ClipboardSharePreviewLoader.load(
                        context = context,
                        uri = ClipboardExternalMediaTestSource.svgUri,
                        declaredMimeType = "image/svg+xml",
                        operation = shareOperation(
                            ClipboardExternalMediaTestSource.svgUri,
                            "image/svg+xml",
                        ),
                        publishOwnedMedia = { owned, _, _ ->
                            promoted = owned
                            ClipboardFileStorage.markActive(context, listOf(owned))
                            throw IOException("Synthetic post-promotion failure.")
                        },
                    )
                }.isFailure,
            )
            assertEquals(
                ClipboardMediaOwnershipState.ACTIVE,
                promoted?.let { ClipboardFileStorage.fileInfo(context, it) }?.ownershipState,
            )
            assertTrue(shareStagingPartials().isEmpty())
        } finally {
            failedPending?.let(::deleteOwned)
            cancelledPending?.let(::deleteOwned)
            promoted?.let(::deleteOwned)
        }
    }

    @Test
    fun cancelledHostileLoadRecoversBeforeTheProviderReturns() = runBlocking {
        var recovered: OwnedClipboardMediaUri? = null
        val blockedLoad = async(Dispatchers.Default) {
            ClipboardSharePreviewLoader.load(
                context = context,
                uri = ClipboardExternalMediaTestSource.blockingUri,
                declaredMimeType = "image/*",
                operation = shareOperation(
                    ClipboardExternalMediaTestSource.blockingUri,
                    "image/*",
                ),
                publishOwnedMedia = { _, _, _ ->
                    throw AssertionError("Abandoned import was published.")
                },
            )
        }

        try {
            assertTrue(ClipboardExternalMediaTestSource.awaitBlockingOpen(AWAIT_MS))
            blockedLoad.cancelAndJoin()

            var preview: ClipboardSharePreview? = null
            withTimeout(AWAIT_MS) {
                while (preview == null) {
                    preview = ClipboardSharePreviewLoader.load(
                        context = context,
                        uri = ClipboardExternalMediaTestSource.healthyUri,
                        declaredMimeType = "image/unknown",
                        operation = shareOperation(
                            ClipboardExternalMediaTestSource.healthyUri,
                            "image/unknown",
                        ),
                        publishOwnedMedia = { owned, _, _ ->
                            recovered = owned
                            true
                        },
                    )
                    if (preview == null) delay(POLL_MS)
                }
            }
            assertTrue(preview != null)
            assertTrue(recovered != null)
            assertEquals(2, ClipboardExternalMediaTestSource.openCount())
            assertTrue(shareStagingPartials().isEmpty())
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            blockedLoad.cancel()
            recovered?.let(::deleteOwned)
        }
    }

    private fun deleteOwned(owned: OwnedClipboardMediaUri) {
        runCatching {
            ClipboardFileStorage.recordSystemRoots(
                context = context,
                ownedUris = ClipboardFileStorage.systemRoots(context) - owned,
                observedBootCount = Int.MAX_VALUE,
            )
        }
        runCatching { ClipboardFileStorage.markRetiring(context, listOf(owned)) }
        runCatching {
            ClipboardFileStorage.deleteOwned(
                context = context,
                ownedUri = owned,
                observedBootCount = Int.MAX_VALUE,
            )
        }
    }

    private fun shareOperation(
        uri: Uri,
        declaredMimeType: String?,
    ): ClipboardShareOperation = requireNotNull(
        ClipboardShareOperation.resolve(uri.toString(), declaredMimeType),
    )

    private fun shareStagingPartials(): List<String> {
        val directory = context.cacheDir.toPath().resolve("clipboard-share-previews")
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.newDirectoryStream(directory).use { children ->
            children
                .map { it.fileName.toString() }
                .filter { it.startsWith(".clipboard-provider-") && it.endsWith(".partial") }
        }
    }

    private fun warmImportWorker() {
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = WARMUP_TIMEOUT_MS,
            stageCapacity = { 1_024L },
            stagingDirectory = "clipboard-share-worker-warmup",
        )
        var staged: StagedClipboardMedia? = null
        try {
            staged = importer.stage(ClipboardExternalMediaTestSource.healthyUri)
            requireNotNull(staged)
        } finally {
            staged?.close()
            importer.close()
        }
    }

    private fun assertPixelColor(
        pixel: Int,
        red: Boolean,
        green: Boolean,
        blue: Boolean,
    ) {
        val threshold = 128
        assertEquals(red, Color.red(pixel) > threshold)
        assertEquals(green, Color.green(pixel) > threshold)
        assertEquals(blue, Color.blue(pixel) > threshold)
    }

    companion object {
        private const val WARMUP_TIMEOUT_MS = 15_000L
        private const val AWAIT_MS = 15_000L
        private const val POLL_MS = 10L
    }
}
