/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import android.text.InputType
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.nlp.CandidateRevision
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidateKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.florisboard.autocorrect.api.AutocorrectCandidateKind

@OptIn(ExperimentalCoroutinesApi::class)
class AutocorrectCandidateLifecycleTest : FunSpec({
    test("stale editor request cannot create a session or send") {
        editorRequestEffects(requestGeneration = 3, activeGeneration = 4) shouldBe emptyList()
    }

    test("current editor request creates a session and sends") {
        editorRequestEffects(requestGeneration = 4, activeGeneration = 4) shouldBe
            listOf("session", "bind", "send")
    }

    test("editor no-suggestions hint still allows provider suggestions") {
        InputAttributes.wrap(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        ).allowsAutocorrectPluginSession(
            isPrivateSession = false,
            isRawInputEditor = false,
        ) shouldBe true
    }

    test("private, raw, and password editors still block provider sessions") {
        val text = InputAttributes.wrap(InputType.TYPE_CLASS_TEXT)
        val password = InputAttributes.wrap(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )

        text.allowsAutocorrectPluginSession(true, false) shouldBe false
        text.allowsAutocorrectPluginSession(false, true) shouldBe false
        password.allowsAutocorrectPluginSession(false, false) shouldBe false
    }

    test("candidate is current only for its admitted session and editor generation") {
        isCurrentAutocorrectCandidate(
            candidateSessionId = 7,
            candidateRequestId = 11,
            candidateEditorGeneration = 3,
            activeSessionId = 7,
            admittedSessionId = 7,
            latestRequestId = 11,
            activeEditorGeneration = 3,
            providerMatches = true,
        ) shouldBe true
    }

    test("a superseded request cannot commit its otherwise current candidate") {
        isCurrentAutocorrectCandidate(
            candidateSessionId = 7,
            candidateRequestId = 10,
            candidateEditorGeneration = 3,
            activeSessionId = 7,
            admittedSessionId = 7,
            latestRequestId = 11,
            activeEditorGeneration = 3,
            providerMatches = true,
        ) shouldBe false
    }

    test("editor transition invalidates a visible candidate before it can be committed") {
        isCurrentAutocorrectCandidate(
            candidateSessionId = 7,
            candidateRequestId = 11,
            candidateEditorGeneration = 3,
            activeSessionId = 7,
            admittedSessionId = 7,
            latestRequestId = 11,
            activeEditorGeneration = 4,
            providerMatches = true,
        ) shouldBe false
    }

    test("stale session, unadmitted, or disconnected candidates cannot be committed") {
        isCurrentAutocorrectCandidate(7, 11, 3, 8, 8, 11, 3, true) shouldBe false
        isCurrentAutocorrectCandidate(7, 11, 3, 7, -1, 11, 3, true) shouldBe false
        isCurrentAutocorrectCandidate(7, 11, 3, 7, 7, 11, 3, false) shouldBe false
    }

    test("provider candidate kinds retain their presentation semantics") {
        AutocorrectCandidateKind.entries.map { it.toSuggestionCandidateKind() } shouldBe
            listOf(
                SuggestionCandidateKind.TYPED,
                SuggestionCandidateKind.CORRECTION,
                SuggestionCandidateKind.COMPLETION,
                SuggestionCandidateKind.NEXT_WORD,
                SuggestionCandidateKind.EMOJI,
            )
    }

    test("explicit replacement ranges must fit the editor snapshot") {
        isAutocorrectReplacementInContent(-1, -1, 4) shouldBe true
        isAutocorrectReplacementInContent(0, 4, 4) shouldBe true
        isAutocorrectReplacementInContent(100, 104, 4) shouldBe false
        isAutocorrectReplacementInContent(3, 1, 4) shouldBe false
    }

    test("latest candidate request is the only one allowed to publish") {
        val revisions = CandidateRevision()
        val stale = revisions.next()
        val latest = revisions.next()
        var published = ""

        revisions.publishIfCurrent(stale) { published = "stale" } shouldBe false
        revisions.publishIfCurrent(latest) { published = "latest" } shouldBe true
        published shouldBe "latest"
    }

    test("provider results remain pending until the active lifecycle answers") {
        runTest {
            val providerResult = CompletableDeferred<String>()
            val waiting = async { awaitProviderResult(providerResult) }

            testScheduler.advanceTimeBy(2_000)
            waiting.isCompleted shouldBe false

            providerResult.complete("candidates")
            waiting.await() shouldBe "candidates"
        }
    }

    test("provider wait ends when its request lifecycle is cancelled") {
        runTest {
            val providerResult = CompletableDeferred<String>()
            val waiting = async { awaitProviderResult(providerResult) }

            providerResult.cancel()

            waiting.await() shouldBe null
        }
    }

    test("dictionary mutation access follows request identity and visible UI lifecycle") {
        hasDictionaryMutationAccess(
            uiClientCount = 1,
            selectedProviderId = "provider",
            providerId = "provider",
            grantProviderId = "provider",
        ) shouldBe true
        hasDictionaryMutationAccess(0, "provider", "provider", "provider") shouldBe false
        hasDictionaryMutationAccess(1, "other", "provider", "provider") shouldBe false
        hasDictionaryMutationAccess(1, "provider", "provider", null) shouldBe false
    }
})

private fun editorRequestEffects(
    requestGeneration: Long,
    activeGeneration: Long,
) = buildList {
    if (isCurrentEditorRequest(requestGeneration, activeGeneration)) {
        addAll(listOf("session", "bind", "send"))
    }
}
