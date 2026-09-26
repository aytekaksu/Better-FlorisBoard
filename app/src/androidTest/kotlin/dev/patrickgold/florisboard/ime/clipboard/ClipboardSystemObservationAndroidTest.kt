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

                assertTrue(
                    withTimeout(5_000L) {
                        pendingPublication.await()
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        withTimeout(5_000L) {
                            manager.awaitInitialization()
                        }
                    }.isFailure,
                )
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun ownedMediaStartupConvergesOnceAndMaintenanceReusesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owned = checkNotNull(OwnedClipboardMediaUri.create(42L, ItemType.IMAGE))
        val stored = ClipboardItem(
            id = 73L,
            type = ItemType.IMAGE,
            text = null,
            uri = owned.uri,
            creationTimestampMs = 1L,
            isPinned = true,
            mimeTypes = listOf("image/png"),
        )
        val observed = stored.copy(
            id = 0L,
            text = "updated caption",
            creationTimestampMs = 2L,
            isPinned = false,
            isSensitive = true,
            isRemoteDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        )

        val reused = checkNotNull(
            findReusableClipboardHistoryItem(listOf(stored), observed),
        )
        val converged = observed.copy(id = reused.id, isPinned = reused.isPinned)
        val systemClip = ClipData(
            ClipDescription("external", arrayOf("image/gif", "application/x-extra")),
            ClipData.Item(converged.text, null, owned.uri),
        ).apply {
            addItem(ClipData.Item("ignored extra item"))
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true)
                }
            }
        }

        assertEquals(stored.id, converged.id)
        assertTrue(converged.isPinned)
        repeat(128) {
            assertTrue(converged.isEqualTo(context, systemClip))
        }

        systemClip.description.extras = PersistableBundle()
        assertFalse(converged.isEqualTo(context, systemClip))
    }
}
