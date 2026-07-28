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

import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.test.editor.DeterministicInputConnection
import dev.patrickgold.florisboard.test.editor.DeterministicInputConnection.TextRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AutoCorrectionEditorFixtureTest : FunSpec({
    test("production revert plan restores the original word through InputConnection") {
        val editor = DeterministicInputConnection(
            initialText = "the ",
            initialSelection = TextRange.cursor(4),
        )
        val content = editor.asEditorContent()
        val replacement = SuggestionReplacement(
            range = EditorRange(0, 3),
            originalText = "teh",
            expectedSelection = EditorRange.cursor(3),
        )

        val plan = requireNotNull(autoCorrectionRevertPlan(replacement, "the", content))
        editor.beginBatchEdit() shouldBe true
        editor.setSelection(plan.range.start, plan.range.end) shouldBe true
        editor.commitText(plan.replacementText, 1) shouldBe true
        editor.endBatchEdit() shouldBe true

        editor.state.text shouldBe "teh"
        editor.state.selection shouldBe TextRange.cursor(3)
    }
})

private fun DeterministicInputConnection.asEditorContent(): EditorContent {
    val state = state
    return EditorContent(
        text = state.text,
        offset = 0,
        localSelection = EditorRange(state.selection.start, state.selection.end),
        localComposing = state.composing?.let { EditorRange(it.start, it.end) } ?: EditorRange.Unspecified,
        localCurrentWord = EditorRange.Unspecified,
    )
}
