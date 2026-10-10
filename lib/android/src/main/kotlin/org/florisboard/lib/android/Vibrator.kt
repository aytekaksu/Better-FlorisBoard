/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.android

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

fun Context.systemVibratorOrNull(): Vibrator? {
    return if (AndroidVersion.ATLEAST_API31_S) {
        this.systemServiceOrNull(VibratorManager::class)?.defaultVibrator
    } else {
        this.systemServiceOrNull(Vibrator::class)
    }?.takeIf { it.hasVibrator() }
}

fun Vibrator.vibrate(duration: Int, strength: Int, factor: Double = 1.0) {
    if (duration == 0 || strength == 0) return
    val effectiveDuration = (duration * factor).toLong().coerceAtLeast(1L)
    val effectiveStrength = when {
        this.hasAmplitudeControl() -> (255.0 * ((strength * factor) / 100.0)).toInt().coerceIn(1, 255)
        else -> VibrationEffect.DEFAULT_AMPLITUDE
    }
    val effect = VibrationEffect.createOneShot(effectiveDuration, effectiveStrength)
    this.vibrate(effect)
}
