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

package dev.patrickgold.florisboard.ime.editor

import dev.patrickgold.florisboard.ime.keyboard.KeyboardState
import dev.patrickgold.florisboard.ime.keyboard.manualSelectionEndpointIsStart
import dev.patrickgold.florisboard.ime.keyboard.setManualSelectionEndpoint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SelectionDragStateTest : FunSpec({
    test("left drag can reverse across its fixed anchor") {
        var state = SelectionDragState.create(EditorRange.cursor(4), initialSteps = -1)!!

        state = state.movedBy(steps = -1, codeUnitDistance = 2)
        state.selection shouldBe EditorRange(2, 4)
        state.isMovingSelectionStart shouldBe true
        state = state.movedBy(steps = 1, codeUnitDistance = 2)
        state.selection shouldBe EditorRange.cursor(4)
        state.isMovingSelectionStart shouldBe true
        state = state.movedBy(steps = 1, codeUnitDistance = 1)
        state.selection shouldBe EditorRange(4, 5)
        state.isMovingSelectionStart shouldBe false
        manualSelectionEndpoint(state) shouldBe state.endpoint
        state.anchor shouldBe 4
    }

    test("right drag can reverse across its fixed anchor") {
        var state = SelectionDragState.create(EditorRange.cursor(3), initialSteps = 1)!!

        state = state.movedBy(steps = 1, codeUnitDistance = 3)
        state.selection shouldBe EditorRange(3, 6)
        state.isMovingSelectionStart shouldBe false
        state = state.movedBy(steps = -1, codeUnitDistance = 3)
        state.selection shouldBe EditorRange.cursor(3)
        state.isMovingSelectionStart shouldBe true
        state = state.movedBy(steps = -1, codeUnitDistance = 1)
        state.selection shouldBe EditorRange(2, 3)
        state.isMovingSelectionStart shouldBe true
        manualSelectionEndpoint(state) shouldBe state.endpoint
        state.anchor shouldBe 3
    }

    test("existing selections choose the opposite boundary as their anchor") {
        SelectionDragState.create(EditorRange(2, 6), initialSteps = -1) shouldBe
            SelectionDragState(anchor = 6, endpoint = 2)
        SelectionDragState.create(EditorRange(2, 6), initialSteps = 1) shouldBe
            SelectionDragState(anchor = 2, endpoint = 6)
    }

    test("established start endpoint overrides the first space swipe direction") {
        val keyboardState = KeyboardState.new().apply {
            setManualSelectionEndpoint(isStart = true)
        }
        val state = SelectionDragState.create(
            selection = EditorRange(2, 4),
            initialSteps = 1,
            establishedMovingStart = keyboardState.manualSelectionEndpointIsStart,
        )!!.movedBy(steps = 1, codeUnitDistance = 1)

        state shouldBe SelectionDragState(anchor = 4, endpoint = 3)
        state.selection shouldBe EditorRange(3, 4)
        manualSelectionEndpoint(state) shouldBe 3
    }

    test("established end endpoint overrides the first space swipe direction") {
        val keyboardState = KeyboardState.new().apply {
            setManualSelectionEndpoint(isStart = false)
        }
        val state = SelectionDragState.create(
            selection = EditorRange(2, 4),
            initialSteps = -1,
            establishedMovingStart = keyboardState.manualSelectionEndpointIsStart,
        )!!.movedBy(steps = -1, codeUnitDistance = 1)

        state shouldBe SelectionDragState(anchor = 2, endpoint = 3)
        state.selection shouldBe EditorRange(2, 3)
        manualSelectionEndpoint(state) shouldBe 3
    }

    test("zero movement keeps boundaries stable and UTF-16 distances remain exact") {
        val boundary = SelectionDragState(anchor = 2, endpoint = 0)
        boundary.movedBy(steps = -1, codeUnitDistance = 0) shouldBe boundary

        val emoji = SelectionDragState(anchor = 0, endpoint = 0)
            .movedBy(steps = 1, codeUnitDistance = "😀".length)
        emoji.selection shouldBe EditorRange(0, 2)
        emoji.movedBy(steps = 1, codeUnitDistance = 0) shouldBe emoji
    }
})

private fun manualSelectionEndpoint(state: SelectionDragState): Int {
    val keyboardState = KeyboardState.new().apply {
        setManualSelectionEndpoint(state.isMovingSelectionStart)
    }
    (keyboardState.isManualSelectionModeStart xor keyboardState.isManualSelectionModeEnd) shouldBe true
    return if (keyboardState.isManualSelectionModeStart) state.selection.start else state.selection.end
}
