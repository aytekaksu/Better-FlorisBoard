/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.autocorrect.host.core

sealed interface HostEvent {
    data object RefreshProviders : HostEvent

    data class ProvidersDiscovered(val revision: DiscoveryRevision, val providers: Set<ProviderId>) : HostEvent

    data class ProviderDiscoveryFailed(val revision: DiscoveryRevision) : HostEvent

    /** A selection change abandons the old binding after sending a best-effort finish. */
    data class SelectProvider(val providerId: ProviderId?, val forceRebind: Boolean = false) : HostEvent

    /** Whether provider UI, a document picker, or an in-flight UI operation still needs a binding. */
    data class SetUiBindingDemand(val required: Boolean) : HostEvent

    data class OpenSession(
        val configuration: SessionConfiguration,
        val editorGeneration: EditorGeneration,
        val at: MonotonicMillis,
    ) : HostEvent

    data object InvalidateEditor : HostEvent

    /** End typing without claiming that the editor itself changed. */
    data object CloseSession : HostEvent

    data class BindingConnected(val lease: BindingLease) : HostEvent

    data class BindingFailed(
        val lease: BindingLease,
        val kind: ProviderFailureKind = ProviderFailureKind.BIND_REJECTED,
        val at: MonotonicMillis,
    ) : HostEvent

    data class ConnectionLost(val lease: BindingLease, val kind: ConnectionLossKind, val at: MonotonicMillis) :
        HostEvent

    /** The adapter is about to send START after checking the current lease. */
    data class SessionStartSending(val lease: SessionLease) : HostEvent

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

    data class FinishSendFailed(val lease: SessionFinishLease, val at: MonotonicMillis) : HostEvent

    data class CircuitCooldownElapsed(val providerId: ProviderId, val at: MonotonicMillis) : HostEvent

    data object Destroy : HostEvent
}
