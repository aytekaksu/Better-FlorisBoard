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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

class AutocorrectHostReducerPropertyTest :
    FunSpec({
        test("all generated transition histories remain valid and deterministic") {
            checkAll(
                iterations = 300,
                Arb.list(Arb.int(0..15), 1..120),
            ) { actions ->
                val reducer = AutocorrectHostReducer(
                    CircuitPolicy(failureThreshold = 2, recoveryDelayMillis = 20L),
                )
                var state = HostState()
                actions.forEachIndexed { index, action ->
                    val at = MonotonicMillis(1_000L + index)
                    val event = eventFor(action, state, at)
                    val first = reducer.reduce(state, event)
                    val second = reducer.reduce(state, event)

                    first shouldBe second
                    state = first.state.requireValid()
                }
            }
        }

        test("typed identifiers reject invalid sentinel values at the boundary") {
            shouldThrow<IllegalArgumentException> { ProviderId(" ") }
            shouldThrow<IllegalArgumentException> { SessionId(0L) }
            shouldThrow<IllegalArgumentException> { RequestId(-1L) }
            shouldThrow<IllegalArgumentException> { BindingEpoch(0L) }
            shouldThrow<IllegalArgumentException> { EditorGeneration(-1L) }
            shouldThrow<IllegalArgumentException> { MonotonicMillis(-1L) }
        }
    })

private fun eventFor(action: Int, state: HostState, at: MonotonicMillis): HostEvent = when (action) {
    0 -> HostEvent.RefreshProviders
    1 -> discoveryResultFor(state)
    2 -> HostEvent.SelectProvider(ProviderA)
    3 -> HostEvent.SelectProvider(ProviderB)
    4 -> HostEvent.SelectProvider(null)
    5 -> HostEvent.OpenSession(DefaultSessionConfiguration, state.editorGeneration, at)
    6 -> bindingConnectedFor(state)
    7 -> sessionStartedFor(state, at)
    8 -> HostEvent.IssueRequest(state.editorGeneration, at)
    9 -> requestReplyFor(state, at)
    10 -> HostEvent.InvalidateEditor
    11 -> connectionLostFor(state, at)
    12 -> finishAcknowledgedFor(state, at)
    13 -> circuitRecoveryFor(state, at)
    14 -> HostEvent.CancelRequest()
    else -> discoveryFailureFor(state)
}

private fun discoveryResultFor(state: HostState): HostEvent {
    val revision = (state.discovery as? DiscoveryState.Loading)?.revision
        ?: return HostEvent.RefreshProviders
    return HostEvent.ProvidersDiscovered(revision, setOf(ProviderA, ProviderB))
}

private fun discoveryFailureFor(state: HostState): HostEvent {
    val revision = (state.discovery as? DiscoveryState.Loading)?.revision
        ?: return HostEvent.RefreshProviders
    return HostEvent.ProviderDiscoveryFailed(revision)
}

private fun bindingConnectedFor(state: HostState): HostEvent {
    val lease = (state.binding as? BindingState.Connecting)?.lease
        ?: return HostEvent.RefreshProviders
    return HostEvent.BindingConnected(lease)
}

private fun sessionStartedFor(state: HostState, at: MonotonicMillis): HostEvent {
    val session = state.session?.takeIf { it.phase == SessionPhase.STARTING }
        ?: return HostEvent.IssueRequest(state.editorGeneration, at)
    val binding = (state.binding as? BindingState.Connected)?.lease
        ?: return HostEvent.RefreshProviders
    return HostEvent.SessionStartResult(
        SessionLease(
            providerId = session.providerId,
            epoch = binding.epoch,
            sessionId = session.sessionId,
            editorGeneration = session.editorGeneration,
        ),
        successful = true,
        at = at,
    )
}

private fun requestReplyFor(state: HostState, at: MonotonicMillis): HostEvent {
    val request = state.pendingRequest?.lease
        ?: state.retiredRequests.lastOrNull()?.lease
        ?: return HostEvent.IssueRequest(state.editorGeneration, at)
    val outcome = if (at.value % 3L == 0L) {
        RequestOutcome.Failure(ProviderFailureKind.TIMEOUT)
    } else {
        RequestOutcome.Success
    }
    return HostEvent.RequestReply(request, outcome, at)
}

private fun connectionLostFor(state: HostState, at: MonotonicMillis): HostEvent {
    val lease = when (val binding = state.binding) {
        BindingState.Unbound -> return HostEvent.RefreshProviders
        is BindingState.Connecting -> binding.lease
        is BindingState.Connected -> binding.lease
    }
    return HostEvent.ConnectionLost(lease, ConnectionLossKind.SERVICE_DISCONNECTED, at)
}

private fun finishAcknowledgedFor(state: HostState, at: MonotonicMillis): HostEvent {
    val finish = state.pendingFinishes.values.firstOrNull()?.lease
        ?: return HostEvent.InvalidateEditor
    return HostEvent.FinishAcknowledged(
        providerId = finish.providerId,
        epoch = finish.epoch,
        sessionId = finish.sessionId,
        at = at,
    )
}

private fun circuitRecoveryFor(state: HostState, at: MonotonicMillis): HostEvent {
    val open = state.health.entries.firstOrNull {
        it.value.circuit is CircuitState.Open
    } ?: return HostEvent.RefreshProviders
    val retryAt = (open.value.circuit as CircuitState.Open).retryAt
    return HostEvent.CircuitCooldownElapsed(
        providerId = open.key,
        at = if (at.value >= retryAt.value) at else retryAt,
    )
}
