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
