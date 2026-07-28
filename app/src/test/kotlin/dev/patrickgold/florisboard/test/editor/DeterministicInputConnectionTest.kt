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

package dev.patrickgold.florisboard.test.editor

import dev.patrickgold.florisboard.test.editor.DeterministicInputConnection.Operation
import dev.patrickgold.florisboard.test.editor.DeterministicInputConnection.SelectionUpdateMode
import dev.patrickgold.florisboard.test.editor.DeterministicInputConnection.TextRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class DeterministicInputConnectionTest :
    FunSpec({
        test("commit replaces the selected UTF-16 range and follows Android cursor rules") {
            val editor = DeterministicInputConnection(
                initialText = "say world",
                initialSelection = TextRange(4, 9),
            )

            editor.commitText("hello", 1) shouldBe true
            editor.state.text shouldBe "say hello"
            editor.state.selection shouldBe TextRange.cursor(9)

            editor.commitText("!", 0) shouldBe true
            editor.state.text shouldBe "say hello!"
            editor.state.selection shouldBe TextRange.cursor(9)
        }

        test("composing text is replaced without committing unrelated text") {
            val editor = DeterministicInputConnection("I teh", TextRange.cursor(5))

            editor.setComposingRegion(2, 5) shouldBe true
            editor.setComposingText("the", 1) shouldBe true
            editor.state.text shouldBe "I the"
            editor.state.composing shouldBe TextRange(2, 5)
            editor.finishComposingText() shouldBe true
            editor.state.composing shouldBe null
        }

        test("code point deletion never leaves half of a surrogate pair") {
            val editor = DeterministicInputConnection(
                initialText = "A😀B🧑‍💻C",
                initialSelection = TextRange.cursor(3),
            )

            editor.deleteSurroundingTextInCodePoints(1, 1) shouldBe true

            editor.state.text shouldBe "A🧑‍💻C"
            editor.state.selection shouldBe TextRange.cursor(1)
        }

        test("nested batch edits produce one final selection update") {
            val updates = mutableListOf<DeterministicInputConnection.SelectionUpdate>()
            val editor = DeterministicInputConnection(
                initialText = "abc",
                onSelectionUpdate = updates::add,
            )

            editor.beginBatchEdit()
            editor.beginBatchEdit()
            editor.setSelection(0, 3)
            editor.commitText("x", 1)
            editor.endBatchEdit()
            updates shouldBe emptyList()
            editor.endBatchEdit()

            updates.map { it.newSelection } shouldContainExactly listOf(TextRange.cursor(1))
            editor.state.batchDepth shouldBe 0
        }

        test("delayed selection updates are released only when the test advances them") {
            val updates = mutableListOf<DeterministicInputConnection.SelectionUpdate>()
            val editor = DeterministicInputConnection(
                initialText = "abc",
                selectionUpdateMode = SelectionUpdateMode.DELAYED,
                onSelectionUpdate = updates::add,
            )

            editor.setSelection(1, 1)
            editor.setSelection(2, 2)

            editor.pendingSelectionUpdateCount shouldBe 2
            editor.flushSelectionUpdates(limit = 1) shouldBe 1
            updates.map { it.newSelection } shouldContainExactly listOf(TextRange.cursor(1))
            editor.flushSelectionUpdates() shouldBe 1
            updates.map { it.newSelection } shouldContainExactly listOf(
                TextRange.cursor(1),
                TextRange.cursor(2),
            )
        }

        test("configured failures are deterministic and do not mutate editor state") {
            val editor = DeterministicInputConnection("abc")
            editor.failNext(Operation.COMMIT_TEXT)

            editor.commitText("x", 1) shouldBe false
            editor.state.text shouldBe "abc"
            editor.commitText("x", 1) shouldBe true
            editor.state.text shouldBe "abcx"

            editor.failAlways(Operation.SET_SELECTION)
            editor.setSelection(0, 0) shouldBe false
            editor.state.selection shouldBe TextRange.cursor(4)
            editor.allow(Operation.SET_SELECTION)
            editor.setSelection(0, 0) shouldBe true
        }

        test("queries respect reversed selections and cursor boundaries") {
            val editor = DeterministicInputConnection(
                initialText = "before-MIDDLE-after",
                initialSelection = TextRange(13, 7),
            )

            editor.getTextBeforeCursor(20, 0) shouldBe "before-"
            editor.getSelectedText(0) shouldBe "MIDDLE"
            editor.getTextAfterCursor(20, 0) shouldBe "-after"
        }
    })
