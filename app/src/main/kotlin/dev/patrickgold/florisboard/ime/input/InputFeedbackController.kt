/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: lifecycle-owned feedback and asynchronous settings snapshots.
 */

package dev.patrickgold.florisboard.ime.input

import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.staticCompositionLocalOf
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.systemServiceOrNull
import org.florisboard.lib.android.systemVibratorOrNull
import org.florisboard.lib.android.vibrate

val LocalInputFeedbackController = staticCompositionLocalOf<InputFeedbackController> { error("not init") }

/** Plays audio and haptic feedback using the owner's coroutine scope. */
class InputFeedbackController(private val ims: InputMethodService, private val scope: CoroutineScope) {
    private val prefs by FlorisPreferenceStore

    private val audioManager = ims.systemServiceOrNull(AudioManager::class)
    private val vibrator = ims.systemVibratorOrNull()
    private val contentResolver = ims.contentResolver

    private data class SystemPrefsState(val audioEnabled: Boolean, val hapticEnabled: Boolean)

    // Before the first window show, keep feedback's original disabled state.
    private var systemPrefsState: Deferred<SystemPrefsState> =
        CompletableDeferred(SystemPrefsState(false, false))

    fun updateSystemPrefsState() {
        systemPrefsState = scope.async(Dispatchers.IO) {
            val audioEnabled = systemPref(Settings.System.SOUND_EFFECTS_ENABLED)
            ensureActive()
            SystemPrefsState(audioEnabled, systemPref(Settings.System.HAPTIC_FEEDBACK_ENABLED))
        }
    }

    fun keyPress(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyPress.get()) performAudioFeedback(data, 1.0)
        if (prefs.inputFeedback.hapticFeatKeyPress.get()) performHapticFeedback(1.0)
    }

    fun keyLongPress(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyLongPress.get()) performAudioFeedback(data, LONG_PRESS_AUDIO_FACTOR)
        if (prefs.inputFeedback.hapticFeatKeyLongPress.get()) performHapticFeedback(SOFT_FEEDBACK_FACTOR)
    }

    fun keyRepeatedAction(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatKeyRepeatedAction.get()) performAudioFeedback(data, SOFT_FEEDBACK_FACTOR)
        if (prefs.inputFeedback.hapticFeatKeyRepeatedAction.get()) performHapticFeedback(SUBTLE_HAPTIC_FACTOR)
    }

    fun gestureMovingSwipe(data: KeyData = TextKeyData.UNSPECIFIED) {
        if (prefs.inputFeedback.audioFeatGestureMovingSwipe.get()) performAudioFeedback(data, SOFT_FEEDBACK_FACTOR)
        if (prefs.inputFeedback.hapticFeatGestureMovingSwipe.get()) performHapticFeedback(SUBTLE_HAPTIC_FACTOR)
    }

    private fun systemPref(id: String): Boolean {
        if (contentResolver == null) return false
        return Settings.System.getInt(contentResolver, id, 0) != 0
    }

    private fun performAudioFeedback(data: KeyData, factor: Double) {
        if (audioManager == null) return
        if (!prefs.inputFeedback.audioEnabled.get()) return
        val systemPrefs = systemPrefsState.takeIf {
            prefs.inputFeedback.audioActivationMode.get() == InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS
        }

        scope.launch(Dispatchers.Default) {
            if (systemPrefs?.await()?.audioEnabled == false) return@launch
            val volume = (prefs.inputFeedback.audioVolume.get() * factor) / 100.0
            val effect = when (data.code) {
                KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyCode.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                KeyCode.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            if (volume in MIN_SOUND_VOLUME..1.00) {
                audioManager.playSoundEffect(effect, volume.toFloat())
            }
        }
    }

    private fun performHapticFeedback(factor: Double) {
        if (vibrator == null) return
        if (!prefs.inputFeedback.hapticEnabled.get()) return
        val systemPrefs = systemPrefsState.takeIf {
            prefs.inputFeedback.hapticActivationMode.get() == InputFeedbackActivationMode.RESPECT_SYSTEM_SETTINGS
        }

        scope.launch(Dispatchers.Default) {
            if (systemPrefs?.await()?.hapticEnabled == false) return@launch
            if (prefs.inputFeedback.hapticVibrationMode.get() == HapticVibrationMode.USE_HAPTIC_FEEDBACK_INTERFACE) {
                val view = ims.window?.window?.decorView ?: return@launch
                val hfc = if (factor < 1.0 && AndroidVersion.ATLEAST_API27_O_MR1) {
                    HapticFeedbackConstants.TEXT_HANDLE_MOVE
                } else {
                    HapticFeedbackConstants.KEYBOARD_TAP
                }
                val didPerform = view.performHapticFeedback(
                    hfc,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                )
                if (didPerform) return@launch
                // If not performed fall back to using the vibrator directly
            }

            vibrator.vibrate(
                duration = prefs.inputFeedback.hapticVibrationDuration.get(),
                strength = prefs.inputFeedback.hapticVibrationStrength.get(),
                factor = factor,
            )
        }
    }
}

private const val LONG_PRESS_AUDIO_FACTOR = 0.7
private const val SOFT_FEEDBACK_FACTOR = 0.4
private const val SUBTLE_HAPTIC_FACTOR = 0.05
private const val MIN_SOUND_VOLUME = 0.01
