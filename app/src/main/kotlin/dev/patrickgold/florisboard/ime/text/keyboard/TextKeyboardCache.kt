/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode

/**
 * Reuses computed keyboards. [dev.patrickgold.florisboard.ime.keyboard.KeyboardManager] serializes
 * all access, including suspended cache misses.
 */
internal class TextKeyboardCache {
    private val cache = KeyboardMode.entries.associateWith {
        mutableMapOf<Subtype, TextKeyboard>()
    }

    fun clear() = cache.values.forEach { it.clear() }

    fun clear(mode: KeyboardMode) = cache.getValue(mode).clear()

    suspend fun getOrPut(mode: KeyboardMode, subtype: Subtype, compute: suspend () -> TextKeyboard): TextKeyboard {
        val modeCache = cache.getValue(mode)
        return modeCache[subtype] ?: compute().also { modeCache[subtype] = it }
    }
}
