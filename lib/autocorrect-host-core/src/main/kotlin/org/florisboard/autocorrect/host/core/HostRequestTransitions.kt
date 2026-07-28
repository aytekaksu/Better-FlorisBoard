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

internal fun HostReduction.issueRequest(event: HostEvent.IssueRequest) {
    val fallback = requestIssueFallback(event)
    if (fallback != null) {
        effects += HostEffect.FallbackRequired(
            providerId = state.session?.providerId ?: state.selectedProvider,
            reason = fallback,
        )
        return
    }
    val active = requireNotNull(state.session)
    val connected = state.binding as BindingState.Connected
    advanceCircuitIfReady(active.providerId, event.at)
    val circuitFallback = state.healthOf(active.providerId).circuit.requestFallback()
    if (circuitFallback != null) {
        effects += HostEffect.FallbackRequired(active.providerId, circuitFallback)
        return
    }

    cancelPendingRequest(
        RequestCancellationReason.SUPERSEDED,
        RetiredRequestReason.SUPERSEDED,
    )
    val lease = RequestLease(
        providerId = active.providerId,
        epoch = connected.lease.epoch,
        sessionId = active.sessionId,
        requestId = RequestId(allocateId()),
        editorGeneration = active.editorGeneration,
    )
    state = state.copy(pendingRequest = PendingRequest(lease))
    markCircuitProbeInFlight(active.providerId)
    effects += HostEffect.RequestSuggestions(lease)
}

private fun HostReduction.requestIssueFallback(event: HostEvent.IssueRequest): FallbackReason? {
    if (event.editorGeneration != state.editorGeneration) {
        return FallbackReason.GENERATION_INVALIDATED
    }
    val active = state.session ?: return FallbackReason.SESSION_NOT_READY
    val connected = state.binding as? BindingState.Connected
    return if (
        active.phase != SessionPhase.ACTIVE ||
        active.editorGeneration != event.editorGeneration ||
        connected?.lease?.providerId != active.providerId
    ) {
        FallbackReason.SESSION_NOT_READY
    } else {
        null
    }
}

private fun CircuitState.requestFallback() = when (this) {
    CircuitState.Closed -> null

    is CircuitState.Open -> FallbackReason.CIRCUIT_OPEN

    is CircuitState.HalfOpen -> if (probeInFlight) {
        FallbackReason.CIRCUIT_PROBE_IN_FLIGHT
    } else {
        null
    }
}

private fun HostReduction.markCircuitProbeInFlight(providerId: ProviderId) {
    val health = state.healthOf(providerId)
    if (health.circuit is CircuitState.HalfOpen) {
        updateHealth(
            providerId,
            health.copy(circuit = CircuitState.HalfOpen(probeInFlight = true)),
        )
    }
}

internal fun HostReduction.requestReply(event: HostEvent.RequestReply) {
    if (state.pendingRequest?.lease != event.lease) {
        effects += HostEffect.RejectReply(event.lease, replyRejectionReason(event.lease))
        return
    }
    val retiredReason = when (event.outcome) {
        RequestOutcome.Success -> RetiredRequestReason.COMPLETED
        is RequestOutcome.Failure -> RetiredRequestReason.FAILED
    }
    state = state.copy(
        pendingRequest = null,
        retiredRequests = retire(event.lease, retiredReason),
    )
    when (val outcome = event.outcome) {
        RequestOutcome.Success -> {
            effects += HostEffect.AcceptReply(event.lease)
            recordSuccess(event.lease.providerId)
        }

        is RequestOutcome.Failure -> {
            recordFailure(event.lease.providerId, outcome.kind, event.at)
            effects += HostEffect.FallbackRequired(
                providerId = event.lease.providerId,
                reason = FallbackReason.REQUEST_FAILED,
            )
        }
    }
}

internal fun HostReduction.requestSendFailed(event: HostEvent.RequestSendFailed) {
    if (state.pendingRequest?.lease != event.lease) {
        ignore(event, IgnoredReason.UNKNOWN_REQUEST)
        return
    }
    state = state.copy(
        pendingRequest = null,
        retiredRequests = retire(event.lease, RetiredRequestReason.FAILED),
    )
    recordFailure(event.lease.providerId, event.kind, event.at)
    effects += HostEffect.FallbackRequired(
        providerId = event.lease.providerId,
        reason = FallbackReason.REQUEST_FAILED,
    )
}

internal fun HostReduction.cancelRequest(event: HostEvent.CancelRequest) {
    val pending = state.pendingRequest
    if (pending == null || event.requestId?.let { it != pending.lease.requestId } == true) {
        ignore(event, IgnoredReason.UNKNOWN_REQUEST)
        return
    }
    cancelPendingRequest(event.reason, RetiredRequestReason.CANCELLED)
}

internal fun HostReduction.circuitCooldownElapsed(event: HostEvent.CircuitCooldownElapsed) {
    val health = state.healthOf(event.providerId)
    val open = health.circuit as? CircuitState.Open
    if (open == null || event.at.value < open.retryAt.value) {
        ignore(event, IgnoredReason.STALE_CIRCUIT_TIMER)
        return
    }
    updateHealth(
        event.providerId,
        health.copy(circuit = CircuitState.HalfOpen(probeInFlight = false)),
    )
    effects += HostEffect.CircuitHalfOpened(event.providerId)
    state.session
        ?.takeIf {
            it.providerId == event.providerId &&
                it.phase == SessionPhase.AWAITING_BINDING
        }
        ?.let { ensureBinding(event.providerId, event.at) }
}

internal fun HostReduction.recordFailure(providerId: ProviderId, kind: ProviderFailureKind, at: MonotonicMillis) {
    val previous = state.healthOf(providerId)
    val failures = Math.addExact(previous.consecutiveFailures, 1)
    val shouldOpen = previous.circuit !is CircuitState.Closed ||
        failures >= circuitPolicy.failureThreshold
    val circuit = if (shouldOpen) {
        CircuitState.Open(at.plus(circuitPolicy.recoveryDelayMillis))
    } else {
        CircuitState.Closed
    }
    val next = ProviderHealth(
        consecutiveFailures = failures,
        circuit = circuit,
    )
    updateHealth(providerId, next)
    effects += HostEffect.ProviderDegraded(providerId, next, kind)
    if (circuit is CircuitState.Open) {
        effects += HostEffect.ScheduleCircuitRecovery(providerId, circuit.retryAt)
    }
}

private fun HostReduction.recordSuccess(providerId: ProviderId) {
    if (state.healthOf(providerId) == ProviderHealth()) return
    updateHealth(providerId, ProviderHealth())
    effects += HostEffect.ProviderRecovered(providerId)
}

internal fun HostReduction.advanceCircuitIfReady(providerId: ProviderId, at: MonotonicMillis) {
    val health = state.healthOf(providerId)
    val open = health.circuit as? CircuitState.Open ?: return
    if (at.value < open.retryAt.value) return
    updateHealth(
        providerId,
        health.copy(circuit = CircuitState.HalfOpen(probeInFlight = false)),
    )
    effects += HostEffect.CircuitHalfOpened(providerId)
}

private fun HostReduction.replyRejectionReason(lease: RequestLease): ReplyRejectionReason {
    val connected = state.binding as? BindingState.Connected
    val active = state.session
    return retiredReplyReason(lease) ?: when {
        lease.providerId != state.selectedProvider -> ReplyRejectionReason.STALE_PROVIDER

        connected?.lease?.providerId != lease.providerId ||
            connected.lease.epoch != lease.epoch -> ReplyRejectionReason.STALE_BINDING

        active?.providerId != lease.providerId ||
            active.sessionId != lease.sessionId -> ReplyRejectionReason.STALE_SESSION

        lease.editorGeneration != state.editorGeneration ||
            lease.editorGeneration != active.editorGeneration -> ReplyRejectionReason.STALE_GENERATION

        else -> ReplyRejectionReason.UNKNOWN_REQUEST
    }
}

private fun HostReduction.retiredReplyReason(lease: RequestLease): ReplyRejectionReason? {
    val retired = state.retiredRequests.lastOrNull {
        it.lease.requestId == lease.requestId
    } ?: return null
    return when (retired.reason) {
        RetiredRequestReason.COMPLETED -> ReplyRejectionReason.DUPLICATE

        RetiredRequestReason.SUPERSEDED -> ReplyRejectionReason.SUPERSEDED

        RetiredRequestReason.CANCELLED,
        RetiredRequestReason.SESSION_FINISHED,
        RetiredRequestReason.EDITOR_INVALIDATED,
        RetiredRequestReason.PROVIDER_CHANGED,
        RetiredRequestReason.PROVIDER_LOST,
        RetiredRequestReason.HOST_DESTROYED,
        RetiredRequestReason.FAILED,
        -> ReplyRejectionReason.CANCELLED
    }
}
