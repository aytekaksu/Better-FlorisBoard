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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private val a = QuickAction.InsertKey(TextKeyData.SETTINGS)
private val b = QuickAction.InsertKey(TextKeyData.UNDO)
private val c = QuickAction.InsertKey(TextKeyData.REDO)
private val noop = QuickAction.InsertKey(TextKeyData(code = KeyCode.NOOP))
private val marker = QuickAction.InsertKey(TextKeyData(code = KeyCode.DRAG_MARKER))

private fun dragState(
    sticky: QuickAction = a,
    dynamic: List<QuickAction> = listOf(b),
    hidden: List<QuickAction> = listOf(c),
) = QuickActionDragState(sticky, dynamic, hidden)

private fun slot(section: QuickActionSection, index: Int = 0) = QuickActionSlot(section, index)

class QuickActionDragStateTest : FunSpec({
    test("grid indices keep headers outside the three action sections") {
        val state = dragState(dynamic = listOf(b, c), hidden = listOf(a, b))
        listOf(
            0 to null,
            1 to slot(QuickActionSection.STICKY),
            2 to null,
            3 to slot(QuickActionSection.DYNAMIC),
            4 to slot(QuickActionSection.DYNAMIC, 1),
            5 to null,
            6 to slot(QuickActionSection.HIDDEN),
            7 to slot(QuickActionSection.HIDDEN, 1),
            8 to null,
        ).forEach { (index, expected) -> state.slotForGridIndex(index) shouldBe expected }
    }

    context("begin drag") {
        withData(
            slot(QuickActionSection.STICKY) to dragState(
                sticky = marker,
            ).copy(activeDragAction = a),
            slot(QuickActionSection.DYNAMIC) to dragState(
                dynamic = listOf(marker),
            ).copy(activeDragAction = b),
            slot(QuickActionSection.HIDDEN) to dragState(
                hidden = listOf(marker),
            ).copy(activeDragAction = c),
        ) { (source, expected) ->
            dragState().beginDrag(source) shouldBe expected
        }

        test("NOOP and invalid slots do not start a drag") {
            val state = dragState(sticky = noop, dynamic = listOf(noop), hidden = listOf(noop))
            listOf(
                slot(QuickActionSection.STICKY),
                slot(QuickActionSection.DYNAMIC),
                slot(QuickActionSection.HIDDEN),
                slot(QuickActionSection.DYNAMIC, 1),
                slot(QuickActionSection.HIDDEN, -1),
            ).forEach { source -> state.beginDrag(source) shouldBe state }
        }

        test("a second drag cannot replace an active action") {
            val started = dragState().beginDrag(slot(QuickActionSection.STICKY))
            started.beginDrag(slot(QuickActionSection.DYNAMIC)) shouldBe started
        }
    }

    context("move and complete") {
        withData(
            Triple(QuickActionSection.STICKY, QuickActionSection.DYNAMIC,
                dragState(sticky = noop, dynamic = listOf(a, b))),
            Triple(QuickActionSection.STICKY, QuickActionSection.HIDDEN,
                dragState(sticky = noop, hidden = listOf(a, c))),
            Triple(QuickActionSection.DYNAMIC, QuickActionSection.STICKY,
                dragState(sticky = b, dynamic = listOf(a))),
            Triple(QuickActionSection.DYNAMIC, QuickActionSection.HIDDEN,
                dragState(dynamic = listOf(noop), hidden = listOf(b, c))),
            Triple(QuickActionSection.HIDDEN, QuickActionSection.STICKY,
                dragState(sticky = c, dynamic = listOf(a, b), hidden = listOf(noop))),
            Triple(QuickActionSection.HIDDEN, QuickActionSection.DYNAMIC,
                dragState(dynamic = listOf(c, b), hidden = listOf(noop))),
        ) { (source, destination, expected) ->
            val moved = dragState().beginDrag(slot(source)).moveDrag(slot(destination))
            moved.completeDrag() shouldBe expected
        }

        test("same-section moves retain captured insertion-index semantics") {
            val state = dragState(dynamic = listOf(a, b, c))
            state.beginDrag(slot(QuickActionSection.DYNAMIC))
                .moveDrag(slot(QuickActionSection.DYNAMIC, 1))
                .completeDrag().dynamicActions shouldBe listOf(b, a, c)
            state.beginDrag(slot(QuickActionSection.DYNAMIC, 2))
                .moveDrag(slot(QuickActionSection.DYNAMIC))
                .completeDrag().dynamicActions shouldBe listOf(c, a, b)
            dragState(hidden = listOf(a, b, c))
                .beginDrag(slot(QuickActionSection.HIDDEN))
                .moveDrag(slot(QuickActionSection.HIDDEN, 1))
                .completeDrag().hiddenActions shouldBe listOf(b, a, c)
        }

        test("empty target replaces NOOP and marker hover makes no change") {
            val started = dragState(dynamic = listOf(noop)).beginDrag(slot(QuickActionSection.HIDDEN))
            started.moveDrag(slot(QuickActionSection.HIDDEN)) shouldBe started
            val moved = started.moveDrag(slot(QuickActionSection.DYNAMIC))
            moved.dynamicActions shouldBe listOf(marker)
            moved.hiddenActions shouldBe listOf(noop)
            moved.completeDrag().dynamicActions shouldBe listOf(c)
        }

        test("saved NOOP after a marker remains targetable when marker removal shifts its index") {
            val state = dragState(dynamic = listOf(b, noop))
                .beginDrag(slot(QuickActionSection.DYNAMIC))
            state.moveDrag(slot(QuickActionSection.DYNAMIC, 1))
                .completeDrag().dynamicActions shouldBe listOf(b)
            dragState(dynamic = listOf(noop, b))
                .beginDrag(slot(QuickActionSection.DYNAMIC, 1))
                .moveDrag(slot(QuickActionSection.DYNAMIC))
                .completeDrag().dynamicActions shouldBe listOf(b)
        }

        test("drag cancel commits the current marker position, not the original slot") {
            val moved = dragState().beginDrag(slot(QuickActionSection.DYNAMIC))
                .moveDrag(slot(QuickActionSection.HIDDEN))
            // Both UI callbacks use completeDragGestureAndCleanUp(), which finalizes this state.
            moved.completeDrag().hiddenActions shouldBe listOf(b, c)
        }

        test("repeated moves and completion never duplicate or lose actions") {
            val started = dragState().beginDrag(slot(QuickActionSection.HIDDEN))
            val moved = started.moveDrag(slot(QuickActionSection.DYNAMIC))
                .moveDrag(slot(QuickActionSection.STICKY))
                .moveDrag(slot(QuickActionSection.DYNAMIC, 1))
            val completed = moved.completeDrag()
            completed.completeDrag() shouldBe completed
            val actions = completed.toArrangement().run {
                listOfNotNull(stickyAction) + dynamicActions + hiddenActions
            }
            actions.size shouldBe 3
            actions.toSet() shouldBe setOf(a, b, c)
        }
    }

    test("dispose completion and persistence omit NOOP and marker sentinels") {
        val unfinished = dragState(dynamic = listOf(noop), hidden = listOf(c, noop))
            .beginDrag(slot(QuickActionSection.HIDDEN))
            .moveDrag(slot(QuickActionSection.DYNAMIC))
        unfinished.completeDrag().toArrangement() shouldBe QuickActionArrangement(
            stickyAction = a,
            dynamicActions = listOf(c),
            hiddenActions = emptyList(),
        )
    }
})
