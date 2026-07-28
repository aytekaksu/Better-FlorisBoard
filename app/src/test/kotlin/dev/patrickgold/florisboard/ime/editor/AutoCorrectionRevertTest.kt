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

import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class AutoCorrectionRevertTest : FunSpec({
    test("space autocorrection restores its original UTF-16 range") {
        val replacement = replacement(originalText = "😀x")
        val content = content(text = "😀y ", selection = EditorRange.cursor(4))

        autoCorrectionRevertPlan(replacement, "😀y", content) shouldBe AutoCorrectionRevertPlan(
            range = EditorRange(0, 4),
            expectedText = "😀y ",
            replacementText = "😀x",
        )
    }

    test("replacement and correction may have different UTF-16 lengths") {
        val replacement = replacement(originalText = "te")
        val content = content(text = "the ", selection = EditorRange.cursor(4))

        autoCorrectionRevertPlan(replacement, "the", content) shouldBe AutoCorrectionRevertPlan(
            range = EditorRange(0, 4),
            expectedText = "the ",
            replacementText = "te",
        )
    }

    test("no-space autocorrection remains revertible") {
        val replacement = replacement(originalText = "teh")
        val content = content(text = "the", selection = EditorRange.cursor(3))

        autoCorrectionRevertPlan(replacement, "the", content) shouldBe AutoCorrectionRevertPlan(
            range = EditorRange(0, 3),
            expectedText = "the",
            replacementText = "teh",
        )
    }

    test("absolute replacement ranges map into a partial editor snapshot") {
        val replacement = SuggestionReplacement(
            range = EditorRange(100, 103),
            originalText = "teh",
            expectedSelection = EditorRange.cursor(103),
        )
        val content = EditorContent(
            text = "the ",
            offset = 100,
            localSelection = EditorRange.cursor(4),
            localComposing = EditorRange.Unspecified,
            localCurrentWord = EditorRange.Unspecified,
        )

        autoCorrectionRevertPlan(replacement, "the", content) shouldBe AutoCorrectionRevertPlan(
            range = EditorRange(100, 104),
            expectedText = "the ",
            replacementText = "teh",
        )
    }

    test("stale text punctuation and selections are not revertible") {
        val replacement = replacement(originalText = "teh")

        autoCorrectionRevertPlan(
            replacement,
            "the",
            content(text = "thy ", selection = EditorRange.cursor(4)),
        ).shouldBeNull()
        autoCorrectionRevertPlan(
            replacement,
            "the",
            content(text = "the.", selection = EditorRange.cursor(4)),
        ).shouldBeNull()
        autoCorrectionRevertPlan(
            replacement,
            "the",
            content(text = "the ", selection = EditorRange(0, 4)),
        ).shouldBeNull()
        autoCorrectionRevertPlan(
            replacement.copy(range = EditorRange(3, 0)),
            "the",
            content(text = "the ", selection = EditorRange.cursor(4)),
        ).shouldBeNull()
    }

    test("any exact queued update preserves a newer revert candidate") {
        val candidate = TestCandidate(
            text = "the",
            replacement = replacement(originalText = "teh"),
        )
        val state = EditorInstance.PhantomSpaceState()
        val queue = AbstractEditorInstance.ExpectedContentQueue()
        val older = content(
            text = "t",
            selection = EditorRange.cursor(1),
        )
        val intermediate = content(
            text = "teh",
            selection = EditorRange(0, 3),
        )
        val final = content(
            text = "the ",
            selection = EditorRange.cursor(4),
        )

        state.setActive(
            showComposingRegion = false,
            candidate = candidate,
        )
        runBlocking {
            queue.push(older)
            queue.push(intermediate)
            queue.push(final)

            state.setInactiveFromUpdate(
                queue.popUntilOrNull(EditorRange.cursor(1), EditorRange.Unspecified) != null,
            )
            (state.candidateForRevert === candidate) shouldBe true
            state.setInactiveFromUpdate(
                queue.popUntilOrNull(EditorRange(0, 3), EditorRange.Unspecified) != null,
            )
            (state.candidateForRevert === candidate) shouldBe true
            state.setInactiveFromUpdate(
                queue.popUntilOrNull(EditorRange.cursor(4), EditorRange.Unspecified) != null,
            )
            (state.candidateForRevert === candidate) shouldBe true
            state.setInactiveFromUpdate(
                queue.popUntilOrNull(EditorRange.cursor(3), EditorRange.Unspecified) != null,
            )
            state.candidateForRevert.shouldBeNull()
        }
    }

    test("replacement failures are dirty only when cursor restoration fails") {
        classifyEditorEditResult(
            selectionAccepted = false,
            commitAccepted = false,
            cursorRestored = false,
        ) shouldBe EditorEditResult.NOT_APPLICABLE
        classifyEditorEditResult(true, false, true) shouldBe EditorEditResult.NOT_APPLICABLE
        classifyEditorEditResult(true, false, false) shouldBe EditorEditResult.DIRTY_FAILURE
        classifyEditorEditResult(true, true, false) shouldBe EditorEditResult.SUCCESS
    }

    test("extracted selections must be valid absolute collapsed cursors") {
        absoluteCollapsedSelection(100, 5, 4, 4) shouldBe 104
        absoluteCollapsedSelection(-1, 5, 4, 4).shouldBeNull()
        absoluteCollapsedSelection(100, -1, 0, 0).shouldBeNull()
        absoluteCollapsedSelection(100, 5, -1, -1).shouldBeNull()
        absoluteCollapsedSelection(100, 5, 6, 6).shouldBeNull()
        absoluteCollapsedSelection(100, 5, 3, 4).shouldBeNull()
        absoluteCollapsedSelection(Int.MAX_VALUE, 1, 1, 1).shouldBeNull()
    }
})

private fun replacement(originalText: String): SuggestionReplacement {
    return SuggestionReplacement(
        range = EditorRange(0, originalText.length),
        originalText = originalText,
        expectedSelection = EditorRange.cursor(originalText.length),
    )
}

private fun content(text: String, selection: EditorRange): EditorContent {
    return EditorContent(
        text = text,
        offset = 0,
        localSelection = selection,
        localComposing = EditorRange.Unspecified,
        localCurrentWord = EditorRange.Unspecified,
    )
}

private class TestCandidate(
    override val text: CharSequence,
    override val replacement: SuggestionReplacement,
) : SuggestionCandidate by WordSuggestionCandidate(text = text)
