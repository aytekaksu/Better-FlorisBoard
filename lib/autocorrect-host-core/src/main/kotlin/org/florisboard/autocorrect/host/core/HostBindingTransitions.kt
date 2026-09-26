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

internal fun HostReduction.bindingConnected(event: HostEvent.BindingConnected) {
    val connecting = state.binding as? BindingState.Connecting
    if (connecting?.lease != event.lease) {
        ignore(event, IgnoredReason.STALE_BINDING)
        return
    }
    state = state.copy(binding = BindingState.Connected(event.lease))
    val active = state.session
    if (
        active != null &&
        active.providerId == event.lease.providerId &&
        active.phase == SessionPhase.AWAITING_BINDING
    ) {
        startSession(active, event.lease)
    } else {
        settleBinding(at = null)
    }
}

internal fun HostReduction.bindingFailed(event: HostEvent.BindingFailed) {
    if (bindingLease() != event.lease) {
        ignore(event, IgnoredReason.STALE_BINDING)
        return
    }
    discardProviderConnection(event.lease, preserveSession = false)
    recordFailure(event.lease.providerId, event.kind, event.at)
    effects += HostEffect.FallbackRequired(
        providerId = event.lease.providerId,
        reason = FallbackReason.PROVIDER_LOST,
    )
}

internal fun HostReduction.connectionLost(event: HostEvent.ConnectionLost) {
    if (bindingLease() != event.lease) {
        ignore(event, IgnoredReason.STALE_BINDING)
        return
    }
    discardProviderConnection(event.lease, event.kind.preservesSession)
    val failure = event.kind.toFailureKind()
    recordFailure(event.lease.providerId, failure, event.at)
    effects += HostEffect.FallbackRequired(
        providerId = event.lease.providerId,
        reason = FallbackReason.PROVIDER_LOST,
    )
    desiredBindingProvider()?.let { ensureBinding(it, event.at, allowUiCircuitBypass = false) }
}

private fun HostReduction.discardProviderConnection(lease: BindingLease, preserveSession: Boolean) {
    cancelPendingRequest(
        RequestCancellationReason.PROVIDER_LOST,
        RetiredRequestReason.PROVIDER_LOST,
    )
    val active = state.session
    val retained = active
        ?.takeIf { preserveSession && it.providerId == lease.providerId }
        ?.copy(phase = SessionPhase.AWAITING_BINDING)
        ?: active?.takeUnless { it.providerId == lease.providerId }
    state = state.copy(
        binding = BindingState.Unbound,
        session = retained,
        pendingFinishes = state.pendingFinishes.filterValues {
            it.lease.providerId != lease.providerId
        },
    )
    effects += HostEffect.Unbind(lease)
}

private fun ConnectionLossKind.toFailureKind() = when (this) {
    ConnectionLossKind.SERVICE_DISCONNECTED -> ProviderFailureKind.SERVICE_DISCONNECTED
    ConnectionLossKind.DEAD_REMOTE -> ProviderFailureKind.DEAD_REMOTE
    ConnectionLossKind.BINDING_DIED -> ProviderFailureKind.BINDING_DIED
    ConnectionLossKind.NULL_BINDING -> ProviderFailureKind.NULL_BINDING
}

internal fun HostReduction.sessionStartSending(event: HostEvent.SessionStartSending) {
    if (!isCurrentSessionStart(event.lease, SessionPhase.STARTING)) {
        ignore(event, if (!isCurrentBinding(event.lease)) IgnoredReason.STALE_BINDING else IgnoredReason.STALE_SESSION)
        return
    }
    state = state.copy(session = requireNotNull(state.session).copy(phase = SessionPhase.SENDING_START))
}

internal fun HostReduction.sessionStartResult(event: HostEvent.SessionStartResult) {
    if (!isCurrentSessionStart(event.lease, SessionPhase.SENDING_START) &&
        !(!event.successful && isCurrentSessionStart(event.lease, SessionPhase.STARTING))
    ) {
        val reason = if (!isCurrentBinding(event.lease)) {
            IgnoredReason.STALE_BINDING
        } else {
            IgnoredReason.STALE_SESSION
        }
        ignore(event, reason)
        return
    }
    val active = requireNotNull(state.session)
    if (event.successful) {
        state = state.copy(session = active.copy(phase = SessionPhase.ACTIVE))
    } else {
        state = state.copy(session = null)
        recordFailure(event.lease.providerId, ProviderFailureKind.SEND_FAILED, event.at)
        effects += HostEffect.FallbackRequired(
            providerId = event.lease.providerId,
            reason = FallbackReason.PROVIDER_LOST,
        )
        settleBinding(event.at)
    }
}

private fun HostReduction.isCurrentSessionStart(lease: SessionLease, phase: SessionPhase): Boolean {
    val active = state.session
    return isCurrentBinding(lease) &&
        active?.sessionId == lease.sessionId &&
        active.providerId == lease.providerId &&
        active.editorGeneration == lease.editorGeneration &&
        active.phase == phase
}

private fun HostReduction.isCurrentBinding(lease: SessionLease): Boolean {
    val connected = state.binding as? BindingState.Connected
    return connected?.lease?.providerId == lease.providerId &&
        connected.lease.epoch == lease.epoch
}

internal fun HostReduction.finishAcknowledged(event: HostEvent.FinishAcknowledged) {
    val pending = state.pendingFinishes[event.sessionId]
    if (pending == null) {
        ignore(event, IgnoredReason.UNKNOWN_FINISH)
        return
    }
    if (
        pending.lease.providerId != event.providerId ||
        pending.lease.epoch != event.epoch
    ) {
        ignore(event, IgnoredReason.STALE_BINDING)
        return
    }
    state = state.copy(pendingFinishes = state.pendingFinishes - event.sessionId)
    settleBinding(event.at)
}

internal fun HostReduction.finishSendFailed(event: HostEvent.FinishSendFailed) {
    val pending = state.pendingFinishes[event.lease.sessionId]
    if (pending?.lease != event.lease) {
        ignore(event, if (pending == null) IgnoredReason.UNKNOWN_FINISH else IgnoredReason.STALE_BINDING)
        return
    }
    state = state.copy(pendingFinishes = state.pendingFinishes - event.lease.sessionId)
    recordFailure(event.lease.providerId, ProviderFailureKind.SEND_FAILED, event.at)
    settleBinding(event.at)
}

internal fun HostReduction.destroy() {
    cancelPendingRequest(
        RequestCancellationReason.HOST_DESTROYED,
        RetiredRequestReason.HOST_DESTROYED,
    )
    endActiveSession(
        cancellationReason = RequestCancellationReason.HOST_DESTROYED,
        retainFinish = false,
    )
    forceUnbind()
    state = state.copy(
        lifecycle = HostLifecycle.DESTROYED,
        binding = BindingState.Unbound,
        session = null,
        pendingRequest = null,
        pendingFinishes = emptyMap(),
        uiBindingDemand = false,
    )
    effects += HostEffect.ReleaseOwnedResources
}

internal fun HostReduction.ensureBinding(
    providerId: ProviderId,
    at: MonotonicMillis?,
    allowUiCircuitBypass: Boolean = true,
) {
    if (desiredBindingProvider() != providerId) return
    if (at != null) advanceCircuitIfReady(providerId, at)
    if (
        state.healthOf(providerId).circuit is CircuitState.Open &&
        !(allowUiCircuitBypass && state.uiBindingDemand)
    ) {
        effects += HostEffect.FallbackRequired(providerId, FallbackReason.CIRCUIT_OPEN)
        return
    }
    when (providerAvailability(providerId)) {
        ProviderAvailability.DISCOVER -> refreshProviders()

        ProviderAvailability.WAIT -> Unit

        ProviderAvailability.UNAVAILABLE -> effects += HostEffect.FallbackRequired(
            providerId,
            FallbackReason.PROVIDER_UNAVAILABLE,
        )

        ProviderAvailability.AVAILABLE -> alignBinding(providerId)
    }
}

internal fun HostReduction.desiredBindingProvider(): ProviderId? =
    state.session?.providerId ?: state.selectedProvider.takeIf { state.uiBindingDemand }

private enum class ProviderAvailability {
    DISCOVER,
    WAIT,
    UNAVAILABLE,
    AVAILABLE,
}

private fun HostReduction.providerAvailability(providerId: ProviderId) = when (val discovery = state.discovery) {
    DiscoveryState.Idle, is DiscoveryState.Failed -> ProviderAvailability.DISCOVER

    is DiscoveryState.Loading -> ProviderAvailability.WAIT

    is DiscoveryState.Ready -> if (providerId in discovery.providers) {
        ProviderAvailability.AVAILABLE
    } else {
        ProviderAvailability.UNAVAILABLE
    }
}

private fun HostReduction.alignBinding(providerId: ProviderId) {
    when (val current = state.binding) {
        BindingState.Unbound -> beginBinding(providerId)
        is BindingState.Connecting -> alignConnecting(current.lease, providerId)
        is BindingState.Connected -> alignConnected(current.lease, providerId)
    }
}

private fun HostReduction.alignConnecting(current: BindingLease, providerId: ProviderId) {
    if (current.providerId == providerId) return
    effects += HostEffect.Unbind(current)
    state = state.copy(binding = BindingState.Unbound)
    beginBinding(providerId)
}

private fun HostReduction.alignConnected(current: BindingLease, providerId: ProviderId) {
    if (current.providerId == providerId) {
        state.session
            ?.takeIf { it.phase == SessionPhase.AWAITING_BINDING }
            ?.let { startSession(it, current) }
    } else {
        effects += HostEffect.Unbind(current)
        state = state.copy(binding = BindingState.Unbound)
        beginBinding(providerId)
    }
}

private fun HostReduction.beginBinding(providerId: ProviderId) {
    val lease = BindingLease(
        providerId = providerId,
        epoch = BindingEpoch(state.nextBindingEpoch),
    )
    state = state.copy(
        binding = BindingState.Connecting(lease),
        nextBindingEpoch = Math.addExact(state.nextBindingEpoch, 1L),
    )
    effects += HostEffect.Bind(lease)
}

private fun HostReduction.startSession(session: HostSession, binding: BindingLease) {
    // Settings may keep a provider bound while its typing circuit is open.
    if (state.healthOf(session.providerId).circuit is CircuitState.Open) return
    val lease = SessionLease(
        providerId = session.providerId,
        epoch = binding.epoch,
        sessionId = session.sessionId,
        editorGeneration = session.editorGeneration,
    )
    state = state.copy(session = session.copy(phase = SessionPhase.STARTING))
    effects += HostEffect.StartSession(lease, session.configuration)
}

internal fun HostReduction.endActiveSession(cancellationReason: RequestCancellationReason, retainFinish: Boolean) {
    cancelPendingRequest(cancellationReason, cancellationReason.toRetiredReason())
    val active = state.session ?: return
    val connected = state.binding as? BindingState.Connected
    if (
        (active.phase == SessionPhase.SENDING_START || active.phase == SessionPhase.ACTIVE) &&
        connected?.lease?.providerId == active.providerId
    ) {
        emitFinish(active, connected.lease, retainFinish)
    }
    state = state.copy(session = null)
}

private fun HostReduction.emitFinish(session: HostSession, binding: BindingLease, retainFinish: Boolean) {
    val lease = SessionFinishLease(
        providerId = session.providerId,
        epoch = binding.epoch,
        sessionId = session.sessionId,
        finalRequestId = RequestId(allocateId()),
    )
    effects += HostEffect.FinishSession(lease, session.configuration)
    if (retainFinish) {
        state = state.copy(
            pendingFinishes = state.pendingFinishes +
                (session.sessionId to PendingFinish(lease)),
        )
    }
}

internal fun HostReduction.settleBinding(at: MonotonicMillis?) {
    val desired = desiredBindingProvider()
    val current = bindingLease()
    if (desired != null) {
        settleBindingForDemand(desired, current, at)
    } else {
        if (current != null && !hasPendingFinish(current.providerId)) {
            effects += HostEffect.Unbind(current)
            state = state.copy(binding = BindingState.Unbound)
        }
    }
}

private fun HostReduction.settleBindingForDemand(providerId: ProviderId, current: BindingLease?, at: MonotonicMillis?) {
    when {
        current == null -> ensureBinding(providerId, at)

        current.providerId == providerId -> {
            state.session
                ?.takeIf { state.binding is BindingState.Connected && it.phase == SessionPhase.AWAITING_BINDING }
                ?.let { startSession(it, current) }
        }

        else -> {
            effects += HostEffect.Unbind(current)
            state = state.copy(binding = BindingState.Unbound)
            ensureBinding(providerId, at)
        }
    }
}
