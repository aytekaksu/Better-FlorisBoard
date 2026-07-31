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
import org.florisboard.autocorrect.host.core.BindingEpoch
import org.florisboard.autocorrect.host.core.BindingLease
import org.florisboard.autocorrect.host.core.BindingState
import org.florisboard.autocorrect.host.core.CircuitPolicy
import org.florisboard.autocorrect.host.core.ConnectionLossKind
import org.florisboard.autocorrect.host.core.DiscoveryRevision
import org.florisboard.autocorrect.host.core.DiscoveryState
import org.florisboard.autocorrect.host.core.EditorGeneration
import org.florisboard.autocorrect.host.core.FallbackReason
import org.florisboard.autocorrect.host.core.HostEffect
import org.florisboard.autocorrect.host.core.HostEvent
import org.florisboard.autocorrect.host.core.HostSession
import org.florisboard.autocorrect.host.core.HostState
import org.florisboard.autocorrect.host.core.MonotonicMillis
import org.florisboard.autocorrect.host.core.ProviderFailureKind
import org.florisboard.autocorrect.host.core.ProviderId
import org.florisboard.autocorrect.host.core.ReplyRejectionReason
import org.florisboard.autocorrect.host.core.RequestCancellationReason
import org.florisboard.autocorrect.host.core.RequestId
import org.florisboard.autocorrect.host.core.RequestLease
import org.florisboard.autocorrect.host.core.RequestOutcome
import org.florisboard.autocorrect.host.core.SessionConfiguration
import org.florisboard.autocorrect.host.core.SessionId
import org.florisboard.autocorrect.host.core.SessionPhase

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
 * Production bridge for the first Architecture-B migration slice.
 *
 * Android still owns discovery, binding handles, session payloads, and provider UI. This class is
 * already authoritative for suggestion request IDs, supersession, cancellation, stale-reply
 * admission, and the provider circuit breaker. Later slices can move binding/session effects into
 * the same reducer without changing callers.
 */
internal class AutocorrectSuggestionRequestCoordinator(circuitPolicy: CircuitPolicy = CircuitPolicy()) {
    private val reducer = AutocorrectHostReducer(circuitPolicy)
    private var state = HostState()
    private val knownLeases = linkedMapOf<RequestId, RequestLease>()

    @Synchronized
    fun admitSession(
        providerId: String,
        bindingEpoch: Long,
        sessionId: Long,
        editorGeneration: Long,
        configuration: SessionConfiguration,
    ) {
        require(bindingEpoch > 0L)
        require(sessionId > 0L)
        require(editorGeneration >= 0L)
        retirePendingRequest(RequestCancellationReason.SESSION_FINISHED)

        val typedProviderId = ProviderId(providerId)
        val typedBindingEpoch = BindingEpoch(bindingEpoch)
        val typedSessionId = SessionId(sessionId)
        val typedGeneration = EditorGeneration(editorGeneration)
        val discoveryRevision = DiscoveryRevision(state.nextDiscoveryRevision)
        state = state.copy(
            discovery = DiscoveryState.Ready(discoveryRevision, setOf(typedProviderId)),
            selectedProvider = typedProviderId,
            binding = BindingState.Connected(
                BindingLease(typedProviderId, typedBindingEpoch),
            ),
            session = HostSession(
                providerId = typedProviderId,
                sessionId = typedSessionId,
                editorGeneration = typedGeneration,
                configuration = configuration,
                phase = SessionPhase.ACTIVE,
            ),
            pendingRequest = null,
            pendingFinishes = emptyMap(),
            queuedProvider = null,
            editorGeneration = typedGeneration,
            nextId = maxOf(state.nextId, nextValueAfter(sessionId)),
            nextBindingEpoch = maxOf(state.nextBindingEpoch, nextValueAfter(bindingEpoch)),
            nextDiscoveryRevision = nextValueAfter(discoveryRevision.value),
        ).requireValid()
    }

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

    @Synchronized
    fun requestSendFailed(
        lease: RequestLease,
        at: MonotonicMillis,
        kind: ProviderFailureKind = ProviderFailureKind.SEND_FAILED,
    ) {
        reduce(HostEvent.RequestSendFailed(lease, kind, at))
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

    @Synchronized
    fun endSession(editorGeneration: Long) {
        retirePendingRequest(RequestCancellationReason.EDITOR_INVALIDATED)
        discardActiveSession(editorGeneration)
    }

    @Synchronized
    fun connectionLost(kind: ConnectionLossKind, at: MonotonicMillis, editorGeneration: Long) {
        val lease = when (val binding = state.binding) {
            BindingState.Unbound -> null
            is BindingState.Connecting -> binding.lease
            is BindingState.Connected -> binding.lease
        }
        if (lease != null) {
            reduce(HostEvent.ConnectionLost(lease, kind, at))
        }
        discardActiveSession(editorGeneration)
    }

    private fun discardActiveSession(editorGeneration: Long) {
        state = state.copy(
            binding = BindingState.Unbound,
            session = null,
            pendingRequest = null,
            pendingFinishes = emptyMap(),
            queuedProvider = null,
            editorGeneration = EditorGeneration(editorGeneration),
        ).requireValid()
    }

    private fun reduce(event: HostEvent) = reducer.reduce(state, event).also {
        state = it.state
    }

    private fun retirePendingRequest(reason: RequestCancellationReason) {
        val pending = state.pendingRequest ?: return
        reduce(HostEvent.CancelRequest(pending.lease.requestId, reason))
    }

    private fun remember(lease: RequestLease) {
        knownLeases[lease.requestId] = lease
        while (knownLeases.size > AutocorrectHostReducer.RETIRED_REQUEST_LIMIT + 1) {
            knownLeases.keys.firstOrNull()?.let(knownLeases::remove)
        }
    }

    private fun nextValueAfter(value: Long): Long {
        require(value < Long.MAX_VALUE) { "Autocorrect host identifier space exhausted" }
        return value + 1L
    }
}
