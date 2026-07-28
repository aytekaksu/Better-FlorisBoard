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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.florisboard.autocorrect.host.core.CircuitPolicy
import org.florisboard.autocorrect.host.core.FallbackReason
import org.florisboard.autocorrect.host.core.MonotonicMillis
import org.florisboard.autocorrect.host.core.ReplyRejectionReason
import org.florisboard.autocorrect.host.core.SessionConfiguration

class AutocorrectSuggestionRequestCoordinatorTest :
    FunSpec({
        test("a superseded request can never be accepted") {
            val coordinator = coordinator()
            val first = coordinator.issue(0L).shouldBeInstanceOf<SuggestionRequestAdmission.Admitted>()
            val second = coordinator.issue(1L).shouldBeInstanceOf<SuggestionRequestAdmission.Admitted>()

            second.cancelledLeases shouldBe listOf(first.lease)
            coordinator.acceptReply(first.lease.requestId.value, time(2L)) shouldBe
                SuggestionReplyDecision.Reject(ReplyRejectionReason.SUPERSEDED)
            coordinator.acceptReply(second.lease.requestId.value, time(3L)) shouldBe
                SuggestionReplyDecision.Accept(second.lease)
        }

        test("a stale editor generation falls back without allocating a request") {
            val coordinator = coordinator()

            coordinator.issueRequest(editorGeneration = 9L, at = time(1L)) shouldBe
                SuggestionRequestAdmission.Fallback(FallbackReason.GENERATION_INVALIDATED)
        }

        test("missing and hostile request IDs are rejected without constructing typed IDs") {
            val coordinator = coordinator()

            coordinator.acceptReply(0L, time(1L)) shouldBe SuggestionReplyDecision.Unknown
            coordinator.acceptReply(-1L, time(1L)) shouldBe SuggestionReplyDecision.Unknown
            coordinator.cancelRequest(0L) shouldBe emptyList()
        }

        test("three send failures open the circuit until its measured cooldown") {
            val coordinator = coordinator(
                circuitPolicy = CircuitPolicy(
                    failureThreshold = 3,
                    recoveryDelayMillis = 100L,
                ),
            )
            repeat(3) { index ->
                val admission = coordinator.issue(index.toLong())
                    .shouldBeInstanceOf<SuggestionRequestAdmission.Admitted>()
                coordinator.requestSendFailed(admission.lease, time(index.toLong()))
            }

            coordinator.issueRequest(0L, time(99L)) shouldBe
                SuggestionRequestAdmission.Fallback(FallbackReason.CIRCUIT_OPEN)

            coordinator.issueRequest(0L, time(102L))
                .shouldBeInstanceOf<SuggestionRequestAdmission.Admitted>()
        }

        test("ending a session rejects the old lease after a new session is admitted") {
            val coordinator = coordinator()
            val old = coordinator.issue(1L).shouldBeInstanceOf<SuggestionRequestAdmission.Admitted>()
            coordinator.endSession(editorGeneration = 1L)
            coordinator.admitSession(
                providerId = "provider.two",
                bindingEpoch = 2L,
                sessionId = 20L,
                editorGeneration = 1L,
                configuration = configuration,
            )

            coordinator.acceptReply(old.lease.requestId.value, time(2L)) shouldBe
                SuggestionReplyDecision.Reject(ReplyRejectionReason.CANCELLED)
        }
    })

private val configuration = SessionConfiguration(
    primaryLanguageTag = "en-US",
    inputType = 1,
    capsMode = 0,
    allowPersonalizedLearning = true,
    editorFlags = 0,
    preferredEmojiSkinToneModifier = 0,
)

private fun coordinator(circuitPolicy: CircuitPolicy = CircuitPolicy()) =
    AutocorrectSuggestionRequestCoordinator(circuitPolicy).also {
        it.admitSession(
            providerId = "provider.one",
            bindingEpoch = 1L,
            sessionId = 10L,
            editorGeneration = 0L,
            configuration = configuration,
        )
    }

private fun AutocorrectSuggestionRequestCoordinator.issue(at: Long) = issueRequest(editorGeneration = 0L, at = time(at))

private fun time(value: Long) = MonotonicMillis(value)
