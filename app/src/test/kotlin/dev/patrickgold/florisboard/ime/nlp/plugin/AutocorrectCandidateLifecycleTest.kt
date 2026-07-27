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

import android.text.InputType
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.nlp.AutomaticSmartbarMutations
import dev.patrickgold.florisboard.ime.nlp.CandidateAssemblyRevision
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidateKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.florisboard.autocorrect.api.AutocorrectCandidateKind

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
            candidateEditorGeneration = 3,
            activeSessionId = 7,
            admittedSessionId = 7,
            activeEditorGeneration = 3,
            providerMatches = true,
        ) shouldBe true
    }

    test("editor transition invalidates a visible candidate before it can be committed") {
        isCurrentAutocorrectCandidate(
            candidateSessionId = 7,
            candidateEditorGeneration = 3,
            activeSessionId = 7,
            admittedSessionId = 7,
            activeEditorGeneration = 4,
            providerMatches = true,
        ) shouldBe false
    }

    test("stale session, unadmitted, or disconnected candidates cannot be committed") {
        isCurrentAutocorrectCandidate(7, 3, 8, 8, 3, true) shouldBe false
        isCurrentAutocorrectCandidate(7, 3, 7, -1, 3, true) shouldBe false
        isCurrentAutocorrectCandidate(7, 3, 7, 7, 3, false) shouldBe false
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

    test("clear prevents an already-running assembly from republishing candidates") {
        val revisions = CandidateAssemblyRevision()
        var candidates = listOf("old")
        var smartbarExpanded = false
        val runningAssembly = revisions.next()

        revisions.next {
            candidates = emptyList()
            smartbarExpanded = true
        }

        revisions.publishIfCurrent(runningAssembly) {
            candidates = listOf("stale")
            smartbarExpanded = false
        } shouldBe false
        candidates shouldBe emptyList()
        smartbarExpanded shouldBe true
    }

    test("clear wins when an older Smartbar write is suspended") {
        runTest {
            val mutations = AutomaticSmartbarMutations()
            val oldWriteSuspended = CompletableDeferred<Unit>()
            val resumeOldWrite = CompletableDeferred<Unit>()
            var animate = true
            var expanded = false

            val oldRevision = mutations.next()
            val oldWrite = launch {
                mutations.runIfCurrent(oldRevision) {
                    animate = false
                    oldWriteSuspended.complete(Unit)
                    resumeOldWrite.await()
                    expanded = false
                }
            }
            oldWriteSuspended.await()

            val clearRevision = mutations.next()
            val clearWrite = launch {
                mutations.runIfCurrent(clearRevision) {
                    animate = false
                    expanded = true
                }
            }
            testScheduler.runCurrent()

            resumeOldWrite.complete(Unit)
            oldWrite.join()
            clearWrite.join()
            animate shouldBe false
            expanded shouldBe true
        }
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
