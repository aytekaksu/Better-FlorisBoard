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

package org.florisboard.autocorrect.host.core

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AutocorrectHostRequestOrderingTest :
    FunSpec({
        test("a newer request cancels and retires its predecessor before being issued") {
            val host = HostTestHarness()
            host.startActiveSession()
            val first = host.issue()

            val effects = host.dispatch(
                HostEvent.IssueRequest(host.state.editorGeneration, T0),
            )
            val cancel = effects.filterIsInstance<HostEffect.CancelSuggestions>().single()
            val second = effects.filterIsInstance<HostEffect.RequestSuggestions>().single().lease

            assertSoftly {
                cancel.lease shouldBe first
                cancel.reason shouldBe RequestCancellationReason.SUPERSEDED
                second.requestId.value shouldBe first.requestId.value + 1
                host.state.pendingRequest?.lease shouldBe second
                host.state.retiredRequests.last().reason shouldBe RetiredRequestReason.SUPERSEDED
            }
        }

        test("late and out-of-order replies cannot replace the newest request") {
            val host = HostTestHarness()
            host.startActiveSession()
            val first = host.issue()
            val second = host.dispatch(
                HostEvent.IssueRequest(host.state.editorGeneration, T0),
            ).singleEffect<HostEffect.RequestSuggestions>().lease

            val late = host.dispatch(
                HostEvent.RequestReply(first, RequestOutcome.Success, T0),
            ).singleEffect<HostEffect.RejectReply>()
            late.reason shouldBe ReplyRejectionReason.SUPERSEDED
            host.state.pendingRequest?.lease shouldBe second

            host.dispatch(
                HostEvent.RequestReply(second, RequestOutcome.Success, T0),
            ).singleEffect<HostEffect.AcceptReply>().lease shouldBe second
            host.state.pendingRequest shouldBe null
        }

        test("a duplicate accepted reply is explicitly classified and rejected") {
            val host = HostTestHarness()
            host.startActiveSession()
            val request = host.issue()

            host.dispatch(
                HostEvent.RequestReply(request, RequestOutcome.Success, T0),
            ).singleEffect<HostEffect.AcceptReply>()
            val duplicate = host.dispatch(
                HostEvent.RequestReply(request, RequestOutcome.Success, T0),
            ).singleEffect<HostEffect.RejectReply>()

            duplicate.reason shouldBe ReplyRejectionReason.DUPLICATE
        }

        test("wrong provider, binding, session, and generation tokens are all rejected") {
            val host = HostTestHarness()
            val session = host.startActiveSession()
            val request = host.issue()

            val cases = listOf(
                request.copy(providerId = ProviderB) to ReplyRejectionReason.STALE_PROVIDER,
                request.copy(epoch = BindingEpoch(request.epoch.value + 1)) to
                    ReplyRejectionReason.STALE_BINDING,
                request.copy(sessionId = SessionId(session.sessionId.value + 100)) to
                    ReplyRejectionReason.STALE_SESSION,
                request.copy(
                    editorGeneration = EditorGeneration(request.editorGeneration.value + 1),
                ) to ReplyRejectionReason.STALE_GENERATION,
            )

            for ((reply, reason) in cases) {
                host.dispatch(
                    HostEvent.RequestReply(reply, RequestOutcome.Success, T0),
                ).singleEffect<HostEffect.RejectReply>().reason shouldBe reason
                host.state.pendingRequest?.lease shouldBe request
            }
        }

        test("generation invalidation rejects captured work before it can issue") {
            val host = HostTestHarness()
            host.startActiveSession()
            val capturedGeneration = host.state.editorGeneration
            host.dispatch(HostEvent.InvalidateEditor)

            val staleOpen = host.dispatch(
                HostEvent.OpenSession(DefaultSessionConfiguration, capturedGeneration, T0),
            ).singleEffect<HostEffect.FallbackRequired>()
            staleOpen.reason shouldBe FallbackReason.GENERATION_INVALIDATED

            val staleRequest = host.dispatch(
                HostEvent.IssueRequest(capturedGeneration, T0),
            ).singleEffect<HostEffect.FallbackRequired>()
            staleRequest.reason shouldBe FallbackReason.GENERATION_INVALIDATED
        }

        test("reply from the old binding stays rejected after provider reconnection") {
            val host = HostTestHarness()
            host.startActiveSession()
            val oldRequest = host.issue()
            val oldBinding = (host.state.binding as BindingState.Connected).lease

            val lossEffects = host.dispatch(
                HostEvent.ConnectionLost(
                    oldBinding,
                    ConnectionLossKind.SERVICE_DISCONNECTED,
                    T0,
                ),
            )
            val newBinding = lossEffects.singleEffect<HostEffect.Bind>().lease
            newBinding.epoch.value shouldBe oldBinding.epoch.value + 1

            val rejection = host.dispatch(
                HostEvent.RequestReply(oldRequest, RequestOutcome.Success, T0),
            ).singleEffect<HostEffect.RejectReply>()
            rejection.reason shouldBe ReplyRejectionReason.CANCELLED
        }

        test("retired request history is bounded without weakening current request validation") {
            val host = HostTestHarness()
            host.startActiveSession()

            repeat(AutocorrectHostReducer.RETIRED_REQUEST_LIMIT + 12) {
                val request = host.issue()
                host.dispatch(HostEvent.RequestReply(request, RequestOutcome.Success, T0))
            }

            host.state.retiredRequests.size shouldBe AutocorrectHostReducer.RETIRED_REQUEST_LIMIT
            val current = host.issue()
            host.state.pendingRequest?.lease shouldBe current
            host.state.requireValid()
        }

        test("caller cancellation is idempotent and late completion cannot be accepted") {
            val host = HostTestHarness()
            host.startActiveSession()
            val request = host.issue()

            val cancel = host.dispatch(
                HostEvent.CancelRequest(request.requestId),
            ).singleEffect<HostEffect.CancelSuggestions>()
            cancel.reason shouldBe RequestCancellationReason.CALLER_CANCELLED

            host.dispatch(
                HostEvent.CancelRequest(request.requestId),
            ).singleEffect<HostEffect.EventIgnored>().reason shouldBe
                IgnoredReason.UNKNOWN_REQUEST
            host.dispatch(
                HostEvent.RequestReply(request, RequestOutcome.Success, T0),
            ).singleEffect<HostEffect.RejectReply>().reason shouldBe
                ReplyRejectionReason.CANCELLED
        }
    })
