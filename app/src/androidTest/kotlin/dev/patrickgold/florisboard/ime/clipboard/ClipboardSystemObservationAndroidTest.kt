/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard

import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardShareOperationToken
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.florisboard.lib.android.systemService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardSystemObservationAndroidTest {
    @Test
    fun clipboardReadAppOpResolvesOnEverySupportedApi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals(
            AppOpsManager.MODE_ALLOWED,
            readClipboardAppOpMode(
                appOpsManager = context.systemService(AppOpsManager::class),
                uid = Process.myUid(),
                packageName = context.packageName,
            ),
        )
    }

    @Test
    fun initializationFailurePromptlyRejectsPendingNonCancellablePublication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var manager: ClipboardManager
        instrumentation.runOnMainSync {
            manager = ClipboardManager(context)
        }
        val media = checkNotNull(OwnedClipboardMediaUri.create(1L, ItemType.IMAGE))

        try {
            runBlocking {
                val pendingPublication = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        manager.publishOwnedClipboardShare(
                            media,
                            ClipboardShareOperationToken.create(),
                            requireNotNull(
                                ClipboardShareRequestFingerprint.parse("a".repeat(64)),
                            ),
                        )
                    }
                }
                assertFalse(pendingPublication.isCompleted)

                manager.failInitialization()

                val publicationFailure = withTimeout(5_000L) {
                    pendingPublication.await().exceptionOrNull()
                }
                assertTrue(publicationFailure is IllegalStateException)
                assertFalse(publicationFailure is CancellationException)

                val initializationFailure = withTimeout(5_000L) {
                    try {
                        manager.awaitInitialization()
                        null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IllegalStateException) {
                        error
                    }
                }
                assertTrue(initializationFailure is IllegalStateException)
                assertFalse(initializationFailure is CancellationException)
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun ownedMediaSelectionAndNativeClipEqualityHonorExactUriAndPrivacy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owned = checkNotNull(OwnedClipboardMediaUri.create(42L, ItemType.IMAGE))
        val otherOwned = checkNotNull(OwnedClipboardMediaUri.create(99L, ItemType.IMAGE))
        val stored = ClipboardItem(
            id = 73L,
            type = ItemType.IMAGE,
            text = null,
            uri = owned.uri,
            creationTimestampMs = 1L,
            isPinned = true,
            mimeTypes = listOf("image/png"),
        )
        val newerOtherOwner = stored.copy(
            id = 74L,
            uri = otherOwned.uri,
            creationTimestampMs = 3L,
            isPinned = false,
        )
        val isRemote = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val observed = stored.copy(
            id = 0L,
            text = "updated caption",
            creationTimestampMs = 2L,
            isPinned = false,
            isSensitive = true,
            isRemoteDevice = isRemote,
        )

        val reused = checkNotNull(
            findReusableClipboardHistoryItem(listOf(newerOtherOwner, stored), observed),
        )
        val systemClip = ClipData(
            ClipDescription("external", arrayOf("image/gif", "application/x-extra")),
            ClipData.Item(observed.text, null, owned.uri),
        ).apply {
            addItem(ClipData.Item("ignored extra item"))
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                if (isRemote) {
                    putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true)
                }
            }
        }

        assertEquals(stored.id, reused.id)
        assertEquals(owned.uri, reused.uri)
        assertTrue(reused.isPinned)
        assertTrue(observed.isEqualTo(context, systemClip))
        assertFalse(
            observed.isEqualTo(
                context,
                ClipData(systemClip.description, ClipData.Item(observed.text, null, otherOwned.uri)),
            ),
        )
        assertFalse(
            observed.isEqualTo(
                context,
                ClipData(systemClip.description, ClipData.Item("different caption", null, owned.uri)),
            ),
        )

        systemClip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
            if (isRemote) {
                putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true)
            }
        }
        assertFalse(observed.isEqualTo(context, systemClip))

        if (isRemote) {
            systemClip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, false)
            }
            assertFalse(observed.isEqualTo(context, systemClip))
        }
    }
}
