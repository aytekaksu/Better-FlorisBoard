/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
