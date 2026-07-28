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

internal fun HostReduction.refreshProviders() {
    val revision = DiscoveryRevision(state.nextDiscoveryRevision)
    state = state.copy(
        discovery = DiscoveryState.Loading(revision),
        nextDiscoveryRevision = Math.addExact(state.nextDiscoveryRevision, 1L),
    )
    effects += HostEffect.DiscoverProviders(revision)
}

internal fun HostReduction.providersDiscovered(event: HostEvent.ProvidersDiscovered) {
    val loading = state.discovery as? DiscoveryState.Loading
    if (loading?.revision != event.revision) {
        ignore(event, IgnoredReason.STALE_DISCOVERY)
        return
    }
    val providers = event.providers.toSet()
    state = state.copy(discovery = DiscoveryState.Ready(event.revision, providers))
    val selected = state.selectedProvider ?: return
    if (selected !in providers) {
        val affected = state.session?.providerId == selected ||
            bindingLease()?.providerId == selected
        if (affected) {
            endActiveSession(
                cancellationReason = RequestCancellationReason.PROVIDER_CHANGED,
                retainFinish = false,
            )
            forceUnbind()
        }
        effects += HostEffect.FallbackRequired(
            providerId = selected,
            reason = FallbackReason.PROVIDER_UNAVAILABLE,
        )
    } else if (state.session?.providerId == selected) {
        ensureBinding(selected, at = null)
    }
}

internal fun HostReduction.providerDiscoveryFailed(event: HostEvent.ProviderDiscoveryFailed) {
    val loading = state.discovery as? DiscoveryState.Loading
    if (loading?.revision != event.revision) {
        ignore(event, IgnoredReason.STALE_DISCOVERY)
        return
    }
    state = state.copy(discovery = DiscoveryState.Failed(event.revision))
    if (state.binding == BindingState.Unbound) {
        endActiveSession(
            cancellationReason = RequestCancellationReason.SESSION_FINISHED,
            retainFinish = false,
        )
        effects += HostEffect.FallbackRequired(
            providerId = state.selectedProvider,
            reason = FallbackReason.DISCOVERY_FAILED,
        )
    }
}

internal fun HostReduction.selectProvider(event: HostEvent.SelectProvider) {
    if (event.providerId == state.selectedProvider) {
        ignore(event, IgnoredReason.NO_CHANGE)
        return
    }
    state = state.copy(editorGeneration = state.editorGeneration.next())
    endActiveSession(
        cancellationReason = RequestCancellationReason.PROVIDER_CHANGED,
        retainFinish = true,
    )
    state = state.copy(
        selectedProvider = event.providerId,
        queuedProvider = null,
    )
    settleBinding(at = null)
}

internal fun HostReduction.openSession(event: HostEvent.OpenSession) {
    val fallback = sessionOpenFallback(event)
    if (fallback != null) {
        effects += HostEffect.FallbackRequired(state.selectedProvider, fallback)
        return
    }
    val providerId = requireNotNull(state.selectedProvider)
    val reusable = state.session?.takeIf {
        it.providerId == providerId &&
            it.editorGeneration == event.editorGeneration &&
            it.configuration == event.configuration
    }
    if (reusable != null) {
        ensureBinding(providerId, event.at)
        return
    }

    endActiveSession(
        cancellationReason = RequestCancellationReason.SESSION_FINISHED,
        retainFinish = true,
    )
    val sessionId = SessionId(allocateId())
    state = state.copy(
        session = HostSession(
            providerId = providerId,
            sessionId = sessionId,
            editorGeneration = event.editorGeneration,
            configuration = event.configuration.copy(
                secondaryLanguageTags = event.configuration.secondaryLanguageTags.toList(),
            ),
            phase = SessionPhase.AWAITING_BINDING,
        ),
    )
    ensureBinding(providerId, event.at)
}

private fun HostReduction.sessionOpenFallback(event: HostEvent.OpenSession): FallbackReason? {
    if (event.editorGeneration != state.editorGeneration) {
        return FallbackReason.GENERATION_INVALIDATED
    }
    val selected = state.selectedProvider ?: return FallbackReason.NO_PROVIDER_SELECTED
    val ready = state.discovery as? DiscoveryState.Ready
    return if (ready != null && selected !in ready.providers) {
        FallbackReason.PROVIDER_UNAVAILABLE
    } else {
        null
    }
}

internal fun HostReduction.invalidateEditor() {
    state = state.copy(editorGeneration = state.editorGeneration.next())
    endActiveSession(
        cancellationReason = RequestCancellationReason.EDITOR_INVALIDATED,
        retainFinish = true,
    )
    settleBinding(at = null)
}
