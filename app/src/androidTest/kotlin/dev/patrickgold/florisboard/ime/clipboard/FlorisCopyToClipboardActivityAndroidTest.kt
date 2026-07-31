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

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaTestSource
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlorisCopyToClipboardActivityAndroidTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context
        get() = instrumentation.targetContext

    @Before
    fun resetSource() {
        ClipboardExternalMediaTestSource.reset()
    }

    @After
    fun releaseSource() {
        runCatching { ClipboardExternalMediaTestSource.releaseBlockingOpen() }
        runCatching { ClipboardExternalMediaTestSource.revokeReadAccess() }
    }

    @Test
    fun restoredOperationStateRequiresCanonicalNestedStrings() {
        val state = ClipboardShareSavedState(
            token = "123e4567-e89b-42d3-a456-426614174000",
            requestFingerprint = "a".repeat(64),
            completed = true,
        )
        val valid = Bundle().apply {
            putBundle(SHARE_OPERATION_NESTED_KEY, state.toBundle())
        }
        assertEquals(state, valid.clipboardShareSavedStateOrNull())

        assertNull(
            Bundle().apply {
                putString(SHARE_OPERATION_NESTED_KEY, "not-a-bundle")
            }.clipboardShareSavedStateOrNull(),
        )
        assertNull(
            Bundle().apply {
                putBundle(
                    SHARE_OPERATION_NESTED_KEY,
                    Bundle().apply {
                        putInt(SHARE_OPERATION_TOKEN_KEY, 42)
                        putString(SHARE_OPERATION_FINGERPRINT_KEY, "a".repeat(64))
                    },
                )
            }.clipboardShareSavedStateOrNull(),
        )
        assertNull(
            Bundle().apply {
                putBundle(
                    SHARE_OPERATION_NESTED_KEY,
                    state.toBundle().apply {
                        putString(SHARE_OPERATION_COMPLETED_KEY, "true")
                    },
                )
            }.clipboardShareSavedStateOrNull(),
        )
        assertEquals(
            state.copy(completed = false),
            Bundle().apply {
                putBundle(
                    SHARE_OPERATION_NESTED_KEY,
                    state.toBundle().apply {
                        remove(SHARE_OPERATION_COMPLETED_KEY)
                    },
                )
            }.clipboardShareSavedStateOrNull(),
        )
    }

    @Test
    fun restorationDistinguishesNotStartedOperationAndTerminalPhases() {
        val state = ClipboardShareSavedState(
            token = "123e4567-e89b-42d3-a456-426614174000",
            requestFingerprint = "a".repeat(64),
            completed = true,
        )
        assertEquals(
            ClipboardShareRestoration.NotStarted,
            Bundle().apply {
                putString(
                    SHARE_OPERATION_PHASE_KEY,
                    SHARE_OPERATION_PHASE_NOT_STARTED,
                )
            }.clipboardShareRestorationOrNull(),
        )
        assertEquals(
            ClipboardShareRestoration.Terminal,
            Bundle().apply {
                putString(SHARE_OPERATION_PHASE_KEY, SHARE_OPERATION_PHASE_TERMINAL)
            }.clipboardShareRestorationOrNull(),
        )
        assertEquals(
            ClipboardShareRestoration.Operation(state),
            Bundle().apply {
                putString(SHARE_OPERATION_PHASE_KEY, SHARE_OPERATION_PHASE_OPERATION)
                putBundle(SHARE_OPERATION_NESTED_KEY, state.toBundle())
            }.clipboardShareRestorationOrNull(),
        )

        listOf(
            Bundle(),
            Bundle().apply {
                putString(SHARE_OPERATION_PHASE_KEY, "unknown")
            },
            Bundle().apply {
                putString(SHARE_OPERATION_PHASE_KEY, SHARE_OPERATION_PHASE_NOT_STARTED)
                putBundle(SHARE_OPERATION_NESTED_KEY, state.toBundle())
            },
            Bundle().apply {
                putString(SHARE_OPERATION_PHASE_KEY, SHARE_OPERATION_PHASE_OPERATION)
            },
        ).forEach { malformed ->
            assertNull(malformed.clipboardShareRestorationOrNull())
        }
    }

    @Test
    fun completedCanonicalRestoreBypassesPublication() {
        val sourceUri = "content://external.example/image"
        val mimeType = "image/png"
        val original = requireNotNull(
            ClipboardShareOperation.resolve(
                sourceUri = sourceUri,
                declaredMimeType = mimeType,
            ),
        )
        val restoredState = ClipboardShareSavedState(
            token = original.token.value,
            requestFingerprint = original.requestFingerprint.value,
            completed = true,
        )

        val resolution = requireNotNull(
            resolveClipboardShareOperation(
                sourceUri = sourceUri,
                declaredMimeType = mimeType,
                restoredState = restoredState,
                restoredStateInvalid = false,
            ),
        )

        assertTrue(resolution.operation.isRestored)
        assertEquals(original.token, resolution.operation.token)
        assertFalse(resolution.requiresPublication)
    }

    @Test
    fun invalidOrMismatchedRestorationCannotMintANewOperation() {
        val sourceUri = "content://external.example/image"
        val mimeType = "image/png"
        val otherRequest = requireNotNull(
            ClipboardShareOperation.resolve(
                sourceUri = "content://external.example/other",
                declaredMimeType = mimeType,
            ),
        )
        val mismatchedState = ClipboardShareSavedState(
            token = otherRequest.token.value,
            requestFingerprint = otherRequest.requestFingerprint.value,
        )

        assertNull(
            resolveClipboardShareOperation(
                sourceUri = sourceUri,
                declaredMimeType = mimeType,
                restoredState = null,
                restoredStateInvalid = true,
            ),
        )
        assertNull(
            resolveClipboardShareOperation(
                sourceUri = sourceUri,
                declaredMimeType = mimeType,
                restoredState = mismatchedState,
                restoredStateInvalid = false,
            ),
        )

        val noRestoration = requireNotNull(
            resolveClipboardShareOperation(
                sourceUri = sourceUri,
                declaredMimeType = mimeType,
                restoredState = null,
                restoredStateInvalid = false,
            ),
        )
        assertFalse(noRestoration.operation.isRestored)
        assertTrue(noRestoration.requiresPublication)
    }

    @Test
    fun completionStaysMonotonicAcrossRetainedAndRestoredState() {
        val viewModel = ClipboardShareViewModel()
        assertFalse(
            clipboardShareCompletionWasObserved(
                previouslyCompleted = false,
                restoredState = null,
                copyState = viewModel.copyState,
            ),
        )

        viewModel.completeRestored()
        val retainedCompletion = clipboardShareCompletionWasObserved(
            previouslyCompleted = false,
            restoredState = null,
            copyState = viewModel.copyState,
        )
        assertTrue(retainedCompletion)

        viewModel.fail(FlorisCopyToClipboardActivity.CopyToClipboardError.UNKNOWN_ERROR)
        assertTrue(
            clipboardShareCompletionWasObserved(
                previouslyCompleted = retainedCompletion,
                restoredState = null,
                copyState = viewModel.copyState,
            ),
        )

        val processRestoredState = ClipboardShareSavedState(
            token = "123e4567-e89b-42d3-a456-426614174000",
            requestFingerprint = "a".repeat(64),
            completed = true,
        )
        assertTrue(
            clipboardShareCompletionWasObserved(
                previouslyCompleted = false,
                restoredState = processRestoredState,
                copyState = ClipboardShareCopyState.Loading,
            ),
        )
    }

    @Test
    fun recreationRetainsOneImportAndARealStopFinishesTheShareActivity() {
        val clipboardManager = context.clipboardManager().value
        runBlocking {
            withTimeout(AWAIT_MS) {
                clipboardManager.awaitInitialization()
            }
        }
        lateinit var platformClipboard: AndroidClipboardManager
        val previousClip = AtomicReference<ClipData?>()
        instrumentation.runOnMainSync {
            platformClipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager
            previousClip.set(runCatching { platformClipboard.primaryClip }.getOrNull())
            platformClipboard.setPrimaryClip(ClipData.newPlainText("Clipboard test", "before"))
        }
        val previousRoots = ClipboardFileStorage.systemRoots(context)
        var published: OwnedClipboardMediaUri? = null
        val application = context.applicationContext as Application
        val activityTracker = ActivityTracker()
        val firstShareActivity = AtomicReference<FlorisCopyToClipboardActivity?>()
        val recreationFailure = AtomicReference<Throwable?>()
        val recreationRequested = AtomicBoolean(false)
        val importWasActive = AtomicBoolean(false)
        val shareIntent = Intent(context, FlorisCopyToClipboardActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, ClipboardExternalMediaTestSource.delayedUri)
            // Exported callers may guess reserved names, but Intent defaults
            // are never a restoration source and wrong types must be inert.
            putExtra(SHARE_OPERATION_REGISTRY_KEY, 42)
            putExtra(SHARE_OPERATION_NESTED_KEY, "hostile")
            putExtra(SHARE_OPERATION_TOKEN_KEY, byteArrayOf(1, 2, 3))
            putExtra(SHARE_OPERATION_FINGERPRINT_KEY, Bundle())
        }
        ClipboardExternalMediaTestSource.grantReadAccess(
            ClipboardExternalMediaTestSource.delayedUri,
        )
        instrumentation.runOnMainSync {
            application.registerActivityLifecycleCallbacks(activityTracker)
        }
        val recreationCoordinator = Thread {
            try {
                check(ClipboardExternalMediaTestSource.awaitBlockingOpen(AWAIT_MS)) {
                    "The delayed import did not start."
                }
                val deadline = SystemClock.elapsedRealtime() + AWAIT_MS
                var activity = activityTracker.currentShareActivity.get()
                while (activity == null && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(POLL_MS)
                    activity = activityTracker.currentShareActivity.get()
                }
                val originalActivity = checkNotNull(activity) {
                    "The share activity did not resume."
                }
                firstShareActivity.set(originalActivity)
                Handler(Looper.getMainLooper()).post {
                    try {
                        importWasActive.set(
                            ClipboardExternalMediaTestSource.delayedOpenIsActive(),
                        )
                        recreationRequested.set(true)
                        ClipboardExternalMediaTestSource.releaseBlockingOpen()
                        originalActivity.recreate()
                    } catch (error: Exception) {
                        recreationFailure.compareAndSet(null, error)
                    }
                }
            } catch (error: Exception) {
                recreationFailure.compareAndSet(null, error)
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            ActivityScenario.launch<FlorisCopyToClipboardActivity>(shareIntent).use { scenario ->
                // ActivityScenario.launch() may wait for an idle app before
                // returning. The coordinator requests the real recreation as
                // soon as the activity resumes and the import is still active.
                awaitCondition {
                    recreationRequested.get() || recreationFailure.get() != null
                }
                recreationFailure.get()?.let { error ->
                    throw AssertionError("Could not request an active recreation.", error)
                }
                val firstActivity = requireNotNull(firstShareActivity.get())
                assertTrue(importWasActive.get())
                awaitCondition {
                    activityTracker.currentShareActivity.get()
                        ?.takeUnless { it === firstActivity } != null
                }
                val recreatedActivity =
                    requireNotNull(activityTracker.currentShareActivity.get())
                assertFalse(recreatedActivity.isFinishing)
                assertEquals(1, ClipboardExternalMediaTestSource.openCount())

                published = awaitPublishedMedia(platformClipboard)
                val owned = requireNotNull(published)
                val info = requireNotNull(ClipboardFileStorage.fileInfo(context, owned))
                assertEquals(listOf("image/svg+xml"), info.mimeTypes)
                assertEquals("_vector.svg", info.displayName)
                assertArrayEquals(
                    ClipboardExternalMediaTestSource.svgBytes,
                    Files.readAllBytes(
                        requireNotNull(ClipboardFileStorage.ownedFile(context, owned)).toPath(),
                    ),
                )
                assertEquals(1, ClipboardExternalMediaTestSource.openCount())

                instrumentation.runOnMainSync {
                    recreatedActivity.startActivity(
                        Intent(recreatedActivity, FlorisAppActivity::class.java),
                    )
                }
                awaitCondition {
                    recreatedActivity in activityTracker.destroyedShareActivities
                }
            }
        } finally {
            runCatching { ClipboardExternalMediaTestSource.releaseBlockingOpen() }
            recreationCoordinator.interrupt()
            runCatching { recreationCoordinator.join(POLL_MS * 5) }
            runCatching {
                ClipboardExternalMediaTestSource.revokeReadAccess(
                    ClipboardExternalMediaTestSource.delayedUri,
                )
            }
            restoreSystemClipboardBestEffort(platformClipboard, previousClip.get())
            published?.let { owned ->
                runCatching {
                    ClipboardFileStorage.recordSystemRoots(
                        context = context,
                        ownedUris = previousRoots,
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
            runCatching {
                instrumentation.runOnMainSync {
                    runCatching { activityTracker.currentCoverActivity.get()?.finish() }
                    runCatching {
                        application.unregisterActivityLifecycleCallbacks(activityTracker)
                    }
                }
            }
        }
    }

    private fun restoreSystemClipboardBestEffort(
        platformClipboard: AndroidClipboardManager,
        previousClip: ClipData?,
    ) {
        runCatching {
            instrumentation.runOnMainSync {
                val restored = previousClip?.let { clip ->
                    runCatching { platformClipboard.setPrimaryClip(clip) }.isSuccess
                } ?: false
                if (!restored) {
                    runCatching {
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
    }

    private class ActivityTracker : Application.ActivityLifecycleCallbacks {
        val currentShareActivity = AtomicReference<FlorisCopyToClipboardActivity?>()
        val currentCoverActivity = AtomicReference<FlorisAppActivity?>()
        val destroyedShareActivities =
            ConcurrentHashMap.newKeySet<FlorisCopyToClipboardActivity>()

        override fun onActivityResumed(activity: Activity) {
            when (activity) {
                is FlorisCopyToClipboardActivity -> currentShareActivity.set(activity)
                is FlorisAppActivity -> currentCoverActivity.set(activity)
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity is FlorisCopyToClipboardActivity) {
                destroyedShareActivities += activity
                currentShareActivity.compareAndSet(activity, null)
            }
            if (activity is FlorisAppActivity) {
                currentCoverActivity.compareAndSet(activity, null)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private fun awaitPublishedMedia(
        platformClipboard: AndroidClipboardManager,
    ): OwnedClipboardMediaUri {
        var published: OwnedClipboardMediaUri? = null
        awaitCondition {
            val uri = AtomicReference<Uri?>()
            instrumentation.runOnMainSync {
                uri.set(
                    runCatching {
                        platformClipboard.primaryClip
                            ?.takeIf { it.itemCount == 1 }
                            ?.getItemAt(0)
                            ?.uri
                    }.getOrNull(),
                )
            }
            published = uri.get()?.let { systemUri ->
                OwnedClipboardMediaUri.parse(systemUri)
                    ?: OwnedClipboardMediaUri.parseOreoSystemClipboard(systemUri)
            }
            published != null
        }
        return requireNotNull(published)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + AWAIT_MS
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError("Condition was not met before the timeout.")
            }
            Thread.sleep(POLL_MS)
        }
    }

    companion object {
        private const val AWAIT_MS = 15_000L
        private const val POLL_MS = 20L
    }
}
