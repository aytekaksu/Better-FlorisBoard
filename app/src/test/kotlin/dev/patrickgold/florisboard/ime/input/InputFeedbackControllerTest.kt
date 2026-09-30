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

package dev.patrickgold.florisboard.ime.input

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.lifecycle.LifecycleInputMethodService
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], application = Application::class, shadows = [RecordingFeedbackAudioManager::class])
class InputFeedbackControllerTest {
    private val prefs by FlorisPreferenceStore

    @Test
    fun acceptedFeedbackIsOwnedByImeLifecycle() {
        val restorePreferences = mutableListOf<suspend () -> Unit>()
        suspend fun <V : Any> set(preference: PreferenceData<V>, value: V) {
            val previous = preference.getOrNull()
            restorePreferences += {
                if (previous == null) preference.reset().getOrThrow() else preference.set(previous).getOrThrow()
            }
            preference.set(value).getOrThrow()
        }

        val boundary = FeedbackAudioBoundary()
        RecordingFeedbackAudioManager.boundary = boundary
        val serviceController = Robolectric.buildService(LifecycleInputMethodService::class.java).create()
        val service = serviceController.get()
        val lifecycleJob = requireNotNull(service.lifecycleScope.coroutineContext[Job])
        val mainLooper = shadowOf(Looper.getMainLooper())
        try {
            runBlocking {
                set(prefs.inputFeedback.audioEnabled, true)
                set(prefs.inputFeedback.audioActivationMode, InputFeedbackActivationMode.IGNORE_SYSTEM_SETTINGS)
                set(prefs.inputFeedback.audioFeatKeyPress, true)
                set(prefs.inputFeedback.audioVolume, 50)
                set(prefs.inputFeedback.hapticEnabled, false)
            }
            val feedback = InputFeedbackController(service, service.lifecycleScope)
            mainLooper.idle()
            val originalJobs = lifecycleJob.descendants().toSet()
            val mainThread = Looper.getMainLooper().thread
            assertSame("fixture must call feedback on Main", mainThread, Thread.currentThread())

            feedback.keyPress(TextKeyData.DELETE)
            assertTrue("feedback did not reach AudioManager", boundary.entered.await(FEEDBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            val request = requireNotNull(boundary.request)
            assertNotSame("feedback must stay off Main", mainThread, request.thread)
            assertEquals(AudioManager.FX_KEYPRESS_DELETE, request.effect)
            assertEquals(0.5f, request.volume, 0f)
            assertEquals("caller returned while the platform call is held", 1L, boundary.finished.count)

            val feedbackJobs = lifecycleJob.descendants().filter { it !in originalJobs && it.isActive }.toList()
            assertTrue("accepted feedback must descend from the IME lifecycle Job", feedbackJobs.isNotEmpty())
            serviceController.destroy()
            assertEquals(Lifecycle.State.DESTROYED, service.lifecycle.currentState)
            assertTrue("destroying the IME must cancel accepted feedback jobs", feedbackJobs.all { it.isCancelled })
            boundary.release.countDown()
            runBlocking { withTimeout(FEEDBACK_TIMEOUT_MS) { feedbackJobs.joinAll() } }
        } finally {
            boundary.release.countDown()
            try {
                if (boundary.entered.count == 0L) {
                    assertTrue("platform call did not finish", boundary.finished.await(FEEDBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                }
                if (service.lifecycle.currentState != Lifecycle.State.DESTROYED) serviceController.destroy()
                mainLooper.idle()
                runBlocking { withTimeout(FEEDBACK_TIMEOUT_MS) { lifecycleJob.join() } }
                assertFalse("fixture timed out holding the platform call", boundary.releaseTimedOut)
            } finally {
                runBlocking { restorePreferences.asReversed().forEach { it() } }
            }
        }
    }

    private fun Job.descendants(): Sequence<Job> = children.flatMap { sequenceOf(it) + it.descendants() }
}

@Implements(AudioManager::class)
class RecordingFeedbackAudioManager : ShadowAudioManager() {
    companion object {
        lateinit var boundary: FeedbackAudioBoundary

        @JvmStatic
        @Implementation
        fun playSoundEffect(effect: Int, volume: Float) {
            val current = boundary
            current.request = FeedbackSoundRequest(effect, volume, Thread.currentThread())
            current.entered.countDown()
            try {
                // A wrong-Main dispatch must fail the test assertion, not block its caller.
                if (Thread.currentThread() != Looper.getMainLooper().thread) {
                    current.releaseTimedOut = !current.release.await(FEEDBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            } finally {
                current.finished.countDown()
            }
        }
    }
}

class FeedbackAudioBoundary {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    @Volatile var request: FeedbackSoundRequest? = null
    @Volatile var releaseTimedOut = false
}

data class FeedbackSoundRequest(val effect: Int, val volume: Float, val thread: Thread)

private const val FEEDBACK_TIMEOUT_MS = 5_000L
