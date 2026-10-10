/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType

/**
 * Pure touch and gesture transition rules shared by the Compose controller and JVM tests.
 *
 * Keeping these decisions outside [TextKeyboardLayout] prevents rendering and Android pointer
 * plumbing from becoming the only place where input ownership semantics can be understood.
 */
internal fun <T> finishGlideDrawingState(
    showTrail: Boolean,
    activePoints: MutableList<T>,
    fadingPoints: MutableList<T>,
): Boolean {
    fadingPoints.clear()
    if (showTrail) {
        fadingPoints.addAll(activePoints)
    }
    activePoints.clear()
    return fadingPoints.isNotEmpty()
}

internal enum class KeyMoveAction {
    KEEP,
    CANCEL,
    TRANSFER,
}

internal fun KeyData.shouldCommitBeforeAdditionalPointer(): Boolean =
    (type == KeyType.CHARACTER || type == KeyType.NUMERIC) &&
        code != KeyCode.SPACE &&
        code != KeyCode.CJK_SPACE

internal fun shouldCommitDeleteSwipeSelection(action: SwipeAction): Boolean =
    action != SwipeAction.SELECT_CHARACTERS_PRECISELY &&
        action != SwipeAction.SELECT_WORDS_PRECISELY

internal fun resolveKeyMoveAction(
    activeKey: TextKey,
    candidateKey: TextKey?,
    pointerX: Float,
    pointerY: Float,
    hysteresisDistance: Float,
): KeyMoveAction {
    if (candidateKey === activeKey) return KeyMoveAction.KEEP
    if (activeKey.containsWithHysteresis(pointerX, pointerY, hysteresisDistance)) {
        return KeyMoveAction.KEEP
    }
    return if (candidateKey != null) {
        KeyMoveAction.TRANSFER
    } else {
        KeyMoveAction.CANCEL
    }
}
