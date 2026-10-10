/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.autocorrect.host.core

internal class HostReduction(initialState: HostState, val circuitPolicy: CircuitPolicy) {
    var state = initialState
    val effects = mutableListOf<HostEffect>()

    fun result() = HostTransition(
        state = state.requireValid(),
        effects = effects.toList(),
    )

    fun ignore(event: HostEvent, reason: IgnoredReason) {
        effects += HostEffect.EventIgnored(event::class.simpleName ?: "HostEvent", reason)
    }

    fun bindingLease(): BindingLease? = when (val binding = state.binding) {
        BindingState.Unbound -> null
        is BindingState.Connecting -> binding.lease
        is BindingState.Connected -> binding.lease
    }

    fun hasPendingFinish(providerId: ProviderId) =
        state.pendingFinishes.values.any { it.lease.providerId == providerId }

    fun allocateId(): Long {
        val id = state.nextId
        state = state.copy(nextId = Math.addExact(id, 1L))
        return id
    }

    fun retire(lease: RequestLease, reason: RetiredRequestReason): List<RetiredRequest> =
        (state.retiredRequests + RetiredRequest(lease, reason))
            .takeLast(AutocorrectHostReducer.RETIRED_REQUEST_LIMIT)

    fun updateHealth(providerId: ProviderId, value: ProviderHealth) {
        state = state.copy(health = state.health + (providerId to value))
    }

    fun cancelPendingRequest(reason: RequestCancellationReason, retiredReason: RetiredRequestReason) {
        val pending = state.pendingRequest ?: return
        state = state.copy(
            pendingRequest = null,
            retiredRequests = retire(pending.lease, retiredReason),
        )
        effects += HostEffect.CancelSuggestions(pending.lease, reason)
    }

    fun forceUnbind() {
        bindingLease()?.let { effects += HostEffect.Unbind(it) }
        state = state.copy(
            binding = BindingState.Unbound,
            pendingFinishes = emptyMap(),
        )
    }
}

internal fun RequestCancellationReason.toRetiredReason() = when (this) {
    RequestCancellationReason.SUPERSEDED -> RetiredRequestReason.SUPERSEDED
    RequestCancellationReason.CALLER_CANCELLED -> RetiredRequestReason.CANCELLED
    RequestCancellationReason.SESSION_FINISHED -> RetiredRequestReason.SESSION_FINISHED
    RequestCancellationReason.EDITOR_INVALIDATED -> RetiredRequestReason.EDITOR_INVALIDATED
    RequestCancellationReason.PROVIDER_CHANGED -> RetiredRequestReason.PROVIDER_CHANGED
    RequestCancellationReason.PROVIDER_LOST -> RetiredRequestReason.PROVIDER_LOST
    RequestCancellationReason.HOST_DESTROYED -> RetiredRequestReason.HOST_DESTROYED
}
