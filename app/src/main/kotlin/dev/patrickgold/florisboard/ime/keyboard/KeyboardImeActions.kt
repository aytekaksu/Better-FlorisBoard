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

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.window.ImeWindowMode

/** Actions that need the currently active IME service, without retaining that service in the manager. */
interface KeyboardImeActions {
    val windowMode: ImeWindowMode

    fun disableWindowEditorIfIdle()
    fun keyRepeatedAction(data: KeyData)
    fun toggleFloatingWindow()
    fun toggleCompactLayout()
    fun compactLayoutToLeft()
    fun compactLayoutToRight()
    fun toggleResizeMode()
    fun showUi()
    fun hideUi()
    fun switchToVoiceInputMethod(): Boolean
    fun switchToPrevInputMethod(): Boolean
    fun switchToNextInputMethod(): Boolean
    fun launchSettings()
}
