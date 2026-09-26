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
import org.florisboard.autocorrect.api.AutocorrectCapsMode
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.host.core.BindingEpoch
import org.florisboard.autocorrect.host.core.HostEvent
import org.florisboard.autocorrect.host.core.ProviderId
import org.florisboard.autocorrect.host.core.RequestId
import org.florisboard.autocorrect.host.core.SessionConfiguration
import org.florisboard.autocorrect.host.core.SessionFinishLease
import org.florisboard.autocorrect.host.core.SessionId

class AutocorrectWireRequestTest : FunSpec({
    test("reducer session configuration becomes the provider wire session") {
        val configuration = testConfiguration().copy(
            secondaryLanguageTags = listOf("de-DE", "fr-FR"),
            capsMode = 2,
            allowPersonalizedLearning = false,
            editorFlags = 1,
            preferredEmojiSkinToneModifier = 0x1F3FD,
        )

        configuration.toAutocorrectSession(SessionId(7)) shouldBe AutocorrectSession(
            sessionId = 7,
            primaryLanguageTag = "en",
            secondaryLanguageTags = listOf("de-DE", "fr-FR"),
            inputType = 1,
            capsMode = 2,
            allowPersonalizedLearning = false,
            editorFlags = 1,
            preferredEmojiSkinToneModifier = 0x1F3FD,
        )
    }

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

    test("wire requests retain the caps mode sampled for that request") {
        val content = editorContent(text = "word", offset = 0, cursor = 4)
        val wire = requireNotNull(
            content.buildAutocorrectWireRequest(
                sessionId = 1,
                requestId = 2,
                maxCandidateCount = 3,
                allowPossiblyOffensive = false,
                capsMode = AutocorrectCapsMode.CAPS_LOCK,
            ),
        )

        wire.request.capsMode shouldBe AutocorrectCapsMode.CAPS_LOCK
    }

    test("normal finish keeps eligible content from the same editor") {
        val content = editorContent(text = "abc", offset = 0, cursor = 3)
        selectFinalRequestContent(content, true, true, true) shouldBe content
    }

    test("finish sends no content after a configuration or privacy change") {
        val content = editorContent(text = "abc", offset = 0, cursor = 3)
        selectFinalRequestContent(content, false, true, true).text shouldBe ""
        selectFinalRequestContent(content, true, true, false).text shouldBe ""
    }

    test("a later editor cannot contribute content to an earlier session finish") {
        val laterEditor = editorContent(text = "later editor", offset = 0, cursor = 12)
        selectFinalRequestContent(laterEditor, true, false, true).text shouldBe ""
    }

    test("a queued finish keeps the closure snapshot when the same editor changes") {
        val atClose = editorContent(text = "abc", offset = 0, cursor = 3)
        val finalRequest = selectFinalRequestContent(atClose, true, true, true)
            .wireRequest().request
        val afterClose = editorContent(text = "abcd", offset = 0, cursor = 4)

        finalRequest.text shouldBe "abc"
        afterClose.text shouldBe "abcd"
    }

    test("a second editor invalidation preserves the queued closure snapshot") {
        val lease = SessionFinishLease(ProviderId("provider"), BindingEpoch(1), SessionId(1), RequestId(2))
        val snapshot = editorContent(text = "abc", offset = 0, cursor = 3).wireRequest().request
        val snapshots = FinalRequestSnapshots()
        snapshots.put(lease, testConfiguration(), snapshot)

        snapshots.onHostEvent(HostEvent.InvalidateEditor, isPrivateSession = false)
        snapshots.onHostEvent(HostEvent.InvalidateEditor, isPrivateSession = false)

        snapshots.take(lease)?.text shouldBe "abc"
        snapshots.take(lease) shouldBe null
    }

    test("private mode clears a queued final snapshot before send") {
        val lease = SessionFinishLease(ProviderId("provider"), BindingEpoch(1), SessionId(1), RequestId(2))
        val snapshots = FinalRequestSnapshots()
        snapshots.put(
            lease,
            testConfiguration(),
            editorContent(text = "abc", offset = 0, cursor = 3).wireRequest().request,
        )

        snapshots.onHostEvent(HostEvent.InvalidateEditor, isPrivateSession = true)

        snapshots.take(lease) shouldBe null
    }
})

private fun testConfiguration() = SessionConfiguration(
    primaryLanguageTag = "en",
    inputType = 1,
    capsMode = 0,
    allowPersonalizedLearning = true,
    editorFlags = 0,
    preferredEmojiSkinToneModifier = 0,
)

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
