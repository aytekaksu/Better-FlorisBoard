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

import org.florisboard.autocorrect.host.core.AutocorrectHostReducer
import org.florisboard.autocorrect.host.core.CircuitPolicy
import org.florisboard.autocorrect.host.core.EditorGeneration
import org.florisboard.autocorrect.host.core.FallbackReason
import org.florisboard.autocorrect.host.core.HostEffect
import org.florisboard.autocorrect.host.core.HostEvent
import org.florisboard.autocorrect.host.core.HostState
import org.florisboard.autocorrect.host.core.HostTransition
import org.florisboard.autocorrect.host.core.MonotonicMillis
import org.florisboard.autocorrect.host.core.ReplyRejectionReason
import org.florisboard.autocorrect.host.core.RequestCancellationReason
import org.florisboard.autocorrect.host.core.RequestId
import org.florisboard.autocorrect.host.core.RequestLease
import org.florisboard.autocorrect.host.core.RequestOutcome

internal sealed interface SuggestionRequestAdmission {
    data class Admitted(val lease: RequestLease, val cancelledLeases: List<RequestLease>) : SuggestionRequestAdmission

    data class Fallback(val reason: FallbackReason) : SuggestionRequestAdmission
}

internal sealed interface SuggestionReplyDecision {
    data class Accept(val lease: RequestLease) : SuggestionReplyDecision

    data class Reject(val reason: ReplyRejectionReason) : SuggestionReplyDecision

    data object Unknown : SuggestionReplyDecision
}

/**
 * Serializes host lifecycle and request admission through one reducer state.
 * Android keeps only Binder handles, session wire payloads, and provider UI state.
 */
internal class AutocorrectSuggestionRequestCoordinator(circuitPolicy: CircuitPolicy = CircuitPolicy()) {
    private val reducer = AutocorrectHostReducer(circuitPolicy)
    private var state = HostState()
    private val knownLeases = linkedMapOf<RequestId, RequestLease>()

    @Synchronized
    fun snapshot(): HostState = state

    @Synchronized
    fun dispatchLifecycle(event: HostEvent): HostTransition = reduce(event)

    @Synchronized
    fun issueRequest(editorGeneration: Long, at: MonotonicMillis): SuggestionRequestAdmission {
        val transition = reduce(
            HostEvent.IssueRequest(
                editorGeneration = EditorGeneration(editorGeneration),
                at = at,
            ),
        )
        val cancelled = transition.effects
            .filterIsInstance<HostEffect.CancelSuggestions>()
            .map { it.lease }
        val request = transition.effects
            .filterIsInstance<HostEffect.RequestSuggestions>()
            .singleOrNull()
        if (request != null) {
            remember(request.lease)
            return SuggestionRequestAdmission.Admitted(request.lease, cancelled)
        }
        val fallback = transition.effects
            .filterIsInstance<HostEffect.FallbackRequired>()
            .lastOrNull()
            ?.reason
            ?: FallbackReason.SESSION_NOT_READY
        return SuggestionRequestAdmission.Fallback(fallback)
    }

    @Synchronized
    fun acceptReply(requestId: Long, at: MonotonicMillis): SuggestionReplyDecision {
        if (requestId <= 0L) return SuggestionReplyDecision.Unknown
        val lease = knownLeases[RequestId(requestId)] ?: return SuggestionReplyDecision.Unknown
        val transition = reduce(
            HostEvent.RequestReply(
                lease = lease,
                outcome = RequestOutcome.Success,
                at = at,
            ),
        )
        if (transition.effects.any { it is HostEffect.AcceptReply && it.lease == lease }) {
            return SuggestionReplyDecision.Accept(lease)
        }
        val rejection = transition.effects
            .filterIsInstance<HostEffect.RejectReply>()
            .lastOrNull()
            ?.reason
            ?: ReplyRejectionReason.UNKNOWN_REQUEST
        return SuggestionReplyDecision.Reject(rejection)
    }

    /** Only a current request may be failed by a malformed, authenticated reply. */
    @Synchronized
    fun currentLeaseForMalformedReply(requestId: Long?, bindingEpoch: Long): RequestLease? {
        val pending = state.pendingRequest?.lease?.takeIf { it.epoch.value == bindingEpoch } ?: return null
        return pending.takeIf { requestId == null || it.requestId.value == requestId }
    }

    @Synchronized
    fun cancelRequest(
        requestId: Long,
        reason: RequestCancellationReason = RequestCancellationReason.CALLER_CANCELLED,
    ): List<RequestLease> {
        if (requestId <= 0L) return emptyList()
        val transition = reduce(HostEvent.CancelRequest(RequestId(requestId), reason))
        return transition.effects
            .filterIsInstance<HostEffect.CancelSuggestions>()
            .map { it.lease }
    }

    private fun reduce(event: HostEvent) = reducer.reduce(state, event).also {
        state = it.state
    }

    private fun remember(lease: RequestLease) {
        knownLeases[lease.requestId] = lease
        while (knownLeases.size > AutocorrectHostReducer.RETIRED_REQUEST_LIMIT + 1) {
            knownLeases.keys.firstOrNull()?.let(knownLeases::remove)
        }
    }
}
