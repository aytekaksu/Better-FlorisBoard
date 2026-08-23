/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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
