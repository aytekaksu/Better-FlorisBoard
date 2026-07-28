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

enum class HostLifecycle {
    RUNNING,
    DESTROYED,
}

sealed interface DiscoveryState {
    data object Idle : DiscoveryState

    data class Loading(val revision: DiscoveryRevision) : DiscoveryState

    data class Ready(val revision: DiscoveryRevision, val providers: Set<ProviderId>) : DiscoveryState

    data class Failed(val revision: DiscoveryRevision) : DiscoveryState
}

sealed interface BindingState {
    data object Unbound : BindingState

    data class Connecting(val lease: BindingLease) : BindingState

    data class Connected(val lease: BindingLease) : BindingState
}

enum class SessionPhase {
    AWAITING_BINDING,
    STARTING,
    ACTIVE,
}

data class HostSession(
    val providerId: ProviderId,
    val sessionId: SessionId,
    val editorGeneration: EditorGeneration,
    val configuration: SessionConfiguration,
    val phase: SessionPhase,
)

data class PendingRequest(val lease: RequestLease)

data class PendingFinish(val lease: SessionFinishLease)

data class RetiredRequest(val lease: RequestLease, val reason: RetiredRequestReason)

sealed interface CircuitState {
    data object Closed : CircuitState

    data class Open(val retryAt: MonotonicMillis) : CircuitState

    data class HalfOpen(val probeInFlight: Boolean) : CircuitState
}

data class ProviderHealth(val consecutiveFailures: Int = 0, val circuit: CircuitState = CircuitState.Closed)

/**
 * Complete immutable state owned by the Android host adapter.
 *
 * The adapter must serialize calls to [AutocorrectHostReducer.reduce], replace its state with the
 * returned state before executing effects, and feed asynchronous results back as token-bearing
 * [HostEvent] instances. The reducer itself owns no threads, timers, services, coroutines, or
 * mutable state.
 */
data class HostState(
    val lifecycle: HostLifecycle = HostLifecycle.RUNNING,
    val discovery: DiscoveryState = DiscoveryState.Idle,
    val selectedProvider: ProviderId? = null,
    val binding: BindingState = BindingState.Unbound,
    val session: HostSession? = null,
    val pendingRequest: PendingRequest? = null,
    val pendingFinishes: Map<SessionId, PendingFinish> = emptyMap(),
    val queuedProvider: ProviderId? = null,
    val health: Map<ProviderId, ProviderHealth> = emptyMap(),
    val editorGeneration: EditorGeneration = EditorGeneration.Initial,
    val retiredRequests: List<RetiredRequest> = emptyList(),
    val nextId: Long = 1L,
    val nextBindingEpoch: Long = 1L,
    val nextDiscoveryRevision: Long = 1L,
) {
    fun healthOf(providerId: ProviderId) = health[providerId] ?: ProviderHealth()

    /**
     * Throws when state did not come from a valid reducer transition. Useful after every event in
     * debug builds and tests.
     */
    fun requireValid(): HostState {
        require(nextId > 0L)
        require(nextBindingEpoch > 0L)
        require(nextDiscoveryRevision > 0L)
        require(retiredRequests.size <= AutocorrectHostReducer.RETIRED_REQUEST_LIMIT)

        if (lifecycle == HostLifecycle.DESTROYED) {
            require(binding == BindingState.Unbound)
            require(session == null)
            require(pendingRequest == null)
            require(pendingFinishes.isEmpty())
            require(queuedProvider == null)
        }

        session?.let { active ->
            require(active.providerId == selectedProvider)
            if (active.phase != SessionPhase.AWAITING_BINDING) {
                val connected = binding as? BindingState.Connected
                require(connected?.lease?.providerId == active.providerId)
            }
        }

        pendingRequest?.lease?.let { request ->
            val active = requireNotNull(session)
            val connected = binding as? BindingState.Connected
            require(active.phase == SessionPhase.ACTIVE)
            require(request.providerId == active.providerId)
            require(request.sessionId == active.sessionId)
            require(request.editorGeneration == active.editorGeneration)
            require(request.editorGeneration == editorGeneration)
            require(request.epoch == connected?.lease?.epoch)
            require(request.providerId == connected.lease.providerId)
        }

        queuedProvider?.let { queued ->
            require(session?.providerId == queued)
            val boundProvider = when (val current = binding) {
                BindingState.Unbound -> null
                is BindingState.Connecting -> current.lease.providerId
                is BindingState.Connected -> current.lease.providerId
            }
            require(boundProvider != null && boundProvider != queued)
        }
        return this
    }
}

data class HostTransition(val state: HostState, val effects: List<HostEffect>)
