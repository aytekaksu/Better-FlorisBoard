/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: lifecycle and asynchronous-settings feedback regressions.
 */

package dev.patrickgold.florisboard.ime.input

import android.app.Application
import android.content.ContentResolver
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.lifecycle.LifecycleInputMethodService
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.jetpref.datastore.model.PreferenceData
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    sdk = [28],
    application = Application::class,
    shadows = [RecordingFeedbackAudioManager::class, RecordingFeedbackSettingsSystem::class],
)
class InputFeedbackControllerTest {
    private val prefs by FlorisPreferenceStore

    @Test
    fun acceptedFeedbackIsOwnedByImeLifecycle() = withFeedback {
        val originalJobs = jobs()
        feedback.keyPress(TextKeyData.DELETE)
        assertTrue(
            "feedback did not reach AudioManager",
            audio.entered.await(FEEDBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        val request = requireNotNull(audio.request)
        assertNotSame("feedback must stay off Main", mainThread, request.thread)
        assertEquals(AudioManager.FX_KEYPRESS_DELETE, request.effect)
        assertEquals(0.5f, request.volume, 0f)
        assertEquals("caller returned while the platform call is held", 1L, audio.finished.count)

        val feedbackJobs = newJobs(originalJobs).filter { it.isActive }
        assertTrue("accepted feedback must descend from the IME lifecycle Job", feedbackJobs.isNotEmpty())
        controller.destroy()
        assertEquals(Lifecycle.State.DESTROYED, service.lifecycle.currentState)
        assertTrue("destroying the IME must cancel accepted feedback jobs", feedbackJobs.all { it.isCancelled })
        audio.release.countDown()
        join(feedbackJobs)
    }

    @Test
    fun firstRespectKeyKeepsItsSnapshotWhileSettingsLoad() = withFeedback {
        audio.release.countDown()
        activationMode(InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS)
        val readers = holdSettings()
        val beforeKey = jobs()
        feedback.keyPress(TextKeyData.DELETE)
        val waiters = newJobs(beforeKey)
        assertTrue("first Respect key must wait in the IME scope", waiters.isNotEmpty())

        activationMode(InputFeedbackActivationMode.IGNORE_SYSTEM_SETTINGS)
        feedback.keyPress(TextKeyData.SPACE)
        sound(AudioManager.FX_KEYPRESS_SPACEBAR)
        assertTrue("first Respect key must still be waiting", waiters.all { it.isActive })

        activationMode(InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS)
        Settings.System.putInt(service.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0)
        val beforeRefresh = jobs()
        feedback.updateSystemPrefsState()
        feedback.keyPress(TextKeyData(code = KeyCode.ENTER))
        join(newJobs(beforeRefresh))
        assertTrue("new disabled snapshot must mute Enter", audio.requests.isEmpty())
        assertTrue("new snapshot must not cancel an accepted old key", waiters.all { it.isActive })

        settings.release.countDown()
        sound(AudioManager.FX_KEYPRESS_DELETE)
        join(readers + waiters)
        val beforeNextKey = jobs()
        feedback.keyPress(TextKeyData(code = KeyCode.ENTER))
        join(newJobs(beforeNextKey))
        assertTrue("late old snapshot must not replace current disabled settings", audio.requests.isEmpty())
    }

    @Test
    fun destroyCancelsFeedbackWaitingForSystemSettings() = withFeedback {
        audio.release.countDown()
        activationMode(InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS)
        val readers = holdSettings()
        val beforeKey = jobs()
        feedback.keyPress(TextKeyData.DELETE)
        val waiters = newJobs(beforeKey)
        assertTrue("Respect key must wait in the IME scope", waiters.isNotEmpty())

        controller.destroy()
        assertEquals(Lifecycle.State.DESTROYED, service.lifecycle.currentState)
        assertTrue("destroy must cancel readers and waiters", (readers + waiters).all { it.isCancelled })
        settings.release.countDown()
        join(listOf(lifecycleJob))
        assertTrue("destroyed IME must not play waiting feedback", audio.requests.isEmpty())
    }

    private fun withFeedback(block: FeedbackFixture.() -> Unit) {
        val restorePreferences = mutableListOf<suspend () -> Unit>()
        suspend fun <V : Any> set(preference: PreferenceData<V>, value: V) {
            val previous = preference.getOrNull()
            restorePreferences += {
                if (previous == null) preference.reset().getOrThrow() else preference.set(previous).getOrThrow()
            }
            preference.set(value).getOrThrow()
        }

        val audio = FeedbackAudioBoundary()
        val settings = FeedbackSettingsBoundary()
        RecordingFeedbackAudioManager.boundary = audio
        RecordingFeedbackSettingsSystem.boundary = null
        val controller = Robolectric.buildService(LifecycleInputMethodService::class.java).create()
        val service = controller.get()
        val lifecycleJob = requireNotNull(service.lifecycleScope.coroutineContext[Job])
        val mainLooper = shadowOf(Looper.getMainLooper())
        val resolver = service.contentResolver
        var restoreSound: (() -> Unit)? = null
        try {
            val previousSound = Settings.System.getInt(
                resolver,
                Settings.System.SOUND_EFFECTS_ENABLED,
                Int.MIN_VALUE,
            ).takeIf {
                it == Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, Int.MAX_VALUE)
            }
            val previousSoundString = Settings.System.getString(resolver, Settings.System.SOUND_EFFECTS_ENABLED)
            restoreSound = {
                if (previousSound != null) {
                    Settings.System.putInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, previousSound)
                } else {
                    Settings.System.putString(resolver, Settings.System.SOUND_EFFECTS_ENABLED, previousSoundString)
                }
            }
            runBlocking {
                set(prefs.inputFeedback.audioEnabled, true)
                set(prefs.inputFeedback.audioActivationMode, InputFeedbackActivationMode.IGNORE_SYSTEM_SETTINGS)
                set(prefs.inputFeedback.audioFeatKeyPress, true)
                set(prefs.inputFeedback.audioVolume, 50)
                set(prefs.inputFeedback.hapticEnabled, false)
            }
            Settings.System.putInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 1)
            assertEquals(1, Settings.System.getInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 0))
            val fixture = FeedbackFixture(controller, audio, settings)
            mainLooper.idle()
            assertSame("fixture must call feedback on Main", fixture.mainThread, Thread.currentThread())
            fixture.block()
        } finally {
            audio.release.countDown()
            settings.release.countDown()
            try {
                if (service.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy()
                mainLooper.idle()
                runBlocking { withTimeout(FEEDBACK_TIMEOUT_MS) { lifecycleJob.join() } }
                if (audio.entered.count == 0L) {
                    assertTrue(
                        "platform call did not finish",
                        audio.finished.await(FEEDBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    )
                }
                assertFalse("fixture timed out holding the platform call", audio.releaseTimedOut)
                assertFalse("fixture timed out holding the settings read", settings.releaseTimedOut)
            } finally {
                RecordingFeedbackSettingsSystem.boundary = null
                try {
                    restoreSound?.invoke()
                } finally {
                    runBlocking { restorePreferences.asReversed().forEach { it() } }
                }
            }
        }
    }

    private inner class FeedbackFixture(
        val controller: ServiceController<LifecycleInputMethodService>,
        val audio: FeedbackAudioBoundary,
        val settings: FeedbackSettingsBoundary,
    ) {
        val service = controller.get()
        val lifecycleJob = requireNotNull(service.lifecycleScope.coroutineContext[Job])
        val feedback = InputFeedbackController(service, service.lifecycleScope)
        val mainThread = Looper.getMainLooper().thread

        fun jobs(): Set<Job> = lifecycleJob.descendants().toSet()
        fun newJobs(before: Set<Job>): List<Job> = jobs().filter { it !in before }
        fun join(jobs: List<Job>) = runBlocking { withTimeout(FEEDBACK_TIMEOUT_MS) { jobs.joinAll() } }
        fun activationMode(mode: InputFeedbackActivationMode) = runBlocking {
            prefs.inputFeedback.audioActivationMode.set(mode).getOrThrow()
        }

        fun holdSettings(): List<Job> {
            val before = jobs()
            RecordingFeedbackSettingsSystem.boundary = settings
            feedback.updateSystemPrefsState()
            assertTrue(
                "settings read did not start",
                settings.entered.await(FEEDBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            assertNotSame("settings query must stay off Main", mainThread, requireNotNull(settings.thread))
            return newJobs(before).also { assertTrue("settings reader must belong to the IME scope", it.isNotEmpty()) }
        }

        fun sound(effect: Int) {
            val request = requireNotNull(audio.requests.poll(FEEDBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "feedback did not reach AudioManager"
            }
            assertEquals(effect, request.effect)
            assertEquals(0.5f, request.volume, 0f)
            assertNotSame("feedback must stay off Main", mainThread, request.thread)
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
            val request = FeedbackSoundRequest(effect, volume, Thread.currentThread())
            current.request = request
            current.requests.offer(request)
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

@Implements(Settings.System::class)
class RecordingFeedbackSettingsSystem : ShadowSettings.ShadowSystem() {
    companion object {
        @Volatile var boundary: FeedbackSettingsBoundary? = null

        @JvmStatic
        @Implementation(methodName = "getInt")
        fun readInt(resolver: ContentResolver, name: String, default: Int): Int {
            val value = ReflectionHelpers.callStaticMethod<Int>(
                ShadowSettings.ShadowSystem::class.java,
                "getInt",
                ClassParameter.from(ContentResolver::class.java, resolver),
                ClassParameter.from(String::class.java, name),
                ClassParameter.from(Int::class.javaPrimitiveType!!, default),
            )
            val current = boundary
            if (name == Settings.System.SOUND_EFFECTS_ENABLED &&
                current != null && current.firstRead.compareAndSet(false, true)
            ) {
                current.thread = Thread.currentThread()
                current.entered.countDown()
                // Hold the actual stored value's return; do not fabricate a settings result.
                if (Thread.currentThread() != Looper.getMainLooper().thread) {
                    current.releaseTimedOut = !current.release.await(FEEDBACK_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS)
                }
            }
            return value
        }
    }
}

class FeedbackAudioBoundary {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val requests = LinkedBlockingQueue<FeedbackSoundRequest>()

    @Volatile var request: FeedbackSoundRequest? = null

    @Volatile var releaseTimedOut = false
}

class FeedbackSettingsBoundary {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val firstRead = AtomicBoolean(false)

    @Volatile var thread: Thread? = null

    @Volatile var releaseTimedOut = false
}

data class FeedbackSoundRequest(val effect: Int, val volume: Float, val thread: Thread)

private const val FEEDBACK_TIMEOUT_MS = 5_000L
