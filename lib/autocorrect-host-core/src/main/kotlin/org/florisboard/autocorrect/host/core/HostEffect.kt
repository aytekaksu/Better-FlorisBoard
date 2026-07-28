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

sealed interface HostEffect {
    data class DiscoverProviders(val revision: DiscoveryRevision) : HostEffect

    data class Bind(val lease: BindingLease) : HostEffect

    data class Unbind(val lease: BindingLease) : HostEffect

    data class StartSession(val lease: SessionLease, val configuration: SessionConfiguration) : HostEffect

    data class FinishSession(val lease: SessionFinishLease) : HostEffect

    data class RequestSuggestions(val lease: RequestLease) : HostEffect

    data class CancelSuggestions(val lease: RequestLease, val reason: RequestCancellationReason) : HostEffect

    /**
     * The adapter may publish the payload carried by the matching [HostEvent.RequestReply].
     * Payloads intentionally stay outside this state machine.
     */
    data class AcceptReply(val lease: RequestLease) : HostEffect

    data class RejectReply(val lease: RequestLease, val reason: ReplyRejectionReason) : HostEffect

    data class FallbackRequired(val providerId: ProviderId?, val reason: FallbackReason) : HostEffect

    data class ProviderDegraded(
        val providerId: ProviderId,
        val health: ProviderHealth,
        val cause: ProviderFailureKind,
    ) : HostEffect

    data class ProviderRecovered(val providerId: ProviderId) : HostEffect

    data class ScheduleCircuitRecovery(val providerId: ProviderId, val retryAt: MonotonicMillis) : HostEffect

    data class CircuitHalfOpened(val providerId: ProviderId) : HostEffect

    data class EventIgnored(val event: String, val reason: IgnoredReason) : HostEffect

    /**
     * Final effect on destruction. The adapter owns and must cancel its coroutine scope, timers,
     * package receiver, and transport after processing earlier best-effort cleanup effects.
     */
    data object ReleaseOwnedResources : HostEffect
}

enum class ReplyRejectionReason {
    DUPLICATE,
    SUPERSEDED,
    CANCELLED,
    UNKNOWN_REQUEST,
    STALE_PROVIDER,
    STALE_BINDING,
    STALE_SESSION,
    STALE_GENERATION,
}

enum class FallbackReason {
    NO_PROVIDER_SELECTED,
    PROVIDER_UNAVAILABLE,
    DISCOVERY_FAILED,
    SESSION_NOT_READY,
    GENERATION_INVALIDATED,
    CIRCUIT_OPEN,
    CIRCUIT_PROBE_IN_FLIGHT,
    REQUEST_FAILED,
    PROVIDER_LOST,
}

enum class IgnoredReason {
    HOST_DESTROYED,
    STALE_DISCOVERY,
    NO_CHANGE,
    STALE_BINDING,
    STALE_SESSION,
    UNKNOWN_FINISH,
    STALE_CIRCUIT_TIMER,
    UNKNOWN_REQUEST,
}
