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

package dev.patrickgold.florisboard.ime.nlp.plugin

import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.autocorrect.api.AutocorrectPluginContract

class AutocorrectWireRequestTest : FunSpec({
    test("provider requests use a bounded editor window") {
        val content = editorContent(
            text = "a".repeat(700),
            offset = 0,
            cursor = 700,
        )

        val wire = content.wireRequest()

        wire.content.text.length shouldBe AutocorrectPluginContract.MAX_CONTEXT_CHARS
        wire.content.offset shouldBe 188
        wire.request.text shouldBe wire.content.text
        wire.request.selectionStart shouldBe 512
        wire.request.selectionEnd shouldBe 512
    }

    test("ranges and explicit replacements retain their full editor coordinates") {
        val content = editorContent(
            text = "x".repeat(600) + "word" + "y".repeat(200),
            offset = 1_000,
            cursor = 604,
            composing = EditorRange(600, 604),
            currentWord = EditorRange(600, 604),
        )

        val wire = content.wireRequest()
        val replacement = autocorrectReplacementForWireContent(
            replacementStart = 508,
            replacementEnd = 512,
            wireContent = wire.content,
            originContent = content,
        )

        wire.content.offset shouldBe 1_092
        wire.content.localSelection shouldBe EditorRange.cursor(512)
        wire.content.localComposing shouldBe EditorRange(508, 512)
        wire.content.localCurrentWord shouldBe EditorRange(508, 512)
        wire.request.currentWordStart shouldBe 508
        wire.request.currentWordEnd shouldBe 512
        replacement?.range shouldBe EditorRange(1_600, 1_604)
        replacement?.originalText shouldBe "word"
        replacement?.expectedSelection shouldBe EditorRange.cursor(1_604)
    }

    test("editor windows never split surrogate pairs at either boundary") {
        val startBoundary = editorContent(
            text = "a\uD83D\uDE00" + "b".repeat(600),
            offset = 0,
            cursor = 514,
        ).wireRequest().content
        val endBoundary = editorContent(
            text = "a".repeat(511) + "\uD83D\uDE00" + "b".repeat(10),
            offset = 0,
            cursor = 100,
        ).wireRequest().content

        startBoundary.offset shouldBe 3
        Character.isLowSurrogate(startBoundary.text.first()) shouldBe false
        endBoundary.text.length shouldBe 511
        Character.isHighSurrogate(endBoundary.text.last()) shouldBe false
    }

    test("non-cursor editor snapshots do not produce provider requests") {
        editorContent(
            text = "selected",
            offset = 0,
            cursor = 0,
        ).copy(
            localSelection = EditorRange(0, 4),
        ).buildAutocorrectWireRequest(
            sessionId = 1,
            requestId = 2,
            maxCandidateCount = 3,
            allowPossiblyOffensive = false,
        ) shouldBe null
    }
})

private fun editorContent(
    text: String,
    offset: Int,
    cursor: Int,
    composing: EditorRange = EditorRange.Unspecified,
    currentWord: EditorRange = EditorRange.Unspecified,
) = EditorContent(
    text = text,
    offset = offset,
    localSelection = EditorRange.cursor(cursor),
    localComposing = composing,
    localCurrentWord = currentWord,
)

private fun EditorContent.wireRequest() = requireNotNull(
    buildAutocorrectWireRequest(
        sessionId = 1,
        requestId = 2,
        maxCandidateCount = 3,
        allowPossiblyOffensive = false,
    ),
)
