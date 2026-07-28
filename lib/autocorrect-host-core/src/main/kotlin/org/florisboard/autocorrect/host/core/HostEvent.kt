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

sealed interface HostEvent {
    data object RefreshProviders : HostEvent

    data class ProvidersDiscovered(val revision: DiscoveryRevision, val providers: Set<ProviderId>) : HostEvent

    data class ProviderDiscoveryFailed(val revision: DiscoveryRevision) : HostEvent

    data class SelectProvider(val providerId: ProviderId?) : HostEvent

    data class OpenSession(
        val configuration: SessionConfiguration,
        val editorGeneration: EditorGeneration,
        val at: MonotonicMillis,
    ) : HostEvent

    data object InvalidateEditor : HostEvent

    data class BindingConnected(val lease: BindingLease) : HostEvent

    data class BindingFailed(
        val lease: BindingLease,
        val kind: ProviderFailureKind = ProviderFailureKind.BIND_REJECTED,
        val at: MonotonicMillis,
    ) : HostEvent

    data class ConnectionLost(val lease: BindingLease, val kind: ConnectionLossKind, val at: MonotonicMillis) :
        HostEvent

    data class SessionStartResult(val lease: SessionLease, val successful: Boolean, val at: MonotonicMillis) :
        HostEvent

    data class IssueRequest(val editorGeneration: EditorGeneration, val at: MonotonicMillis) : HostEvent

    data class RequestReply(val lease: RequestLease, val outcome: RequestOutcome, val at: MonotonicMillis) : HostEvent

    data class RequestSendFailed(
        val lease: RequestLease,
        val kind: ProviderFailureKind = ProviderFailureKind.SEND_FAILED,
        val at: MonotonicMillis,
    ) : HostEvent

    data class CancelRequest(
        val requestId: RequestId? = null,
        val reason: RequestCancellationReason = RequestCancellationReason.CALLER_CANCELLED,
    ) : HostEvent

    data class FinishAcknowledged(
        val providerId: ProviderId,
        val epoch: BindingEpoch,
        val sessionId: SessionId,
        val at: MonotonicMillis,
    ) : HostEvent

    data class CircuitCooldownElapsed(val providerId: ProviderId, val at: MonotonicMillis) : HostEvent

    data object Destroy : HostEvent
}
