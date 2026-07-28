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

/**
 * Pure state machine for external-autocorrect host coordination.
 *
 * Effects are commands, not callbacks. The owner must install [HostTransition.state] before
 * executing [HostTransition.effects], execute effects in order, and serialize any resulting events
 * through the same owner. This guarantees that a synchronous transport callback is still checked
 * against the state which authorized it.
 */
class AutocorrectHostReducer(private val circuitPolicy: CircuitPolicy = CircuitPolicy()) {
    fun reduce(initialState: HostState, event: HostEvent): HostTransition {
        initialState.requireValid()
        val reduction = HostReduction(initialState, circuitPolicy)
        if (initialState.lifecycle == HostLifecycle.DESTROYED) {
            reduction.ignore(event, IgnoredReason.HOST_DESTROYED)
            return reduction.result()
        }
        reduction.handle(event)
        return reduction.result()
    }

    companion object {
        const val RETIRED_REQUEST_LIMIT = 32
    }
}

private fun HostReduction.handle(event: HostEvent) {
    when (event) {
        HostEvent.RefreshProviders -> refreshProviders()
        is HostEvent.ProvidersDiscovered -> providersDiscovered(event)
        is HostEvent.ProviderDiscoveryFailed -> providerDiscoveryFailed(event)
        is HostEvent.SelectProvider -> selectProvider(event)
        is HostEvent.OpenSession -> openSession(event)
        HostEvent.InvalidateEditor -> invalidateEditor()
        is HostEvent.BindingConnected -> bindingConnected(event)
        is HostEvent.BindingFailed -> bindingFailed(event)
        is HostEvent.ConnectionLost -> connectionLost(event)
        is HostEvent.SessionStartResult -> sessionStartResult(event)
        is HostEvent.IssueRequest -> issueRequest(event)
        is HostEvent.RequestReply -> requestReply(event)
        is HostEvent.RequestSendFailed -> requestSendFailed(event)
        is HostEvent.CancelRequest -> cancelRequest(event)
        is HostEvent.FinishAcknowledged -> finishAcknowledged(event)
        is HostEvent.CircuitCooldownElapsed -> circuitCooldownElapsed(event)
        HostEvent.Destroy -> destroy()
    }
}
