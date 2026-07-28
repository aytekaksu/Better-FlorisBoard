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

internal val ProviderA = ProviderId("example.provider/.Alpha")
internal val ProviderB = ProviderId("example.provider/.Beta")
internal val T0 = MonotonicMillis(1_000L)

internal val DefaultSessionConfiguration = SessionConfiguration(
    primaryLanguageTag = "en-US",
    secondaryLanguageTags = listOf("de-DE"),
    inputType = 1,
    capsMode = 0,
    allowPersonalizedLearning = true,
    editorFlags = 0,
    preferredEmojiSkinToneModifier = 0,
)

internal class HostTestHarness(policy: CircuitPolicy = CircuitPolicy()) {
    private val reducer = AutocorrectHostReducer(policy)

    var state = HostState()
        private set

    fun dispatch(event: HostEvent): List<HostEffect> {
        val transition = reducer.reduce(state, event)
        state = transition.state
        return transition.effects
    }

    fun discover(providers: Set<ProviderId>): List<HostEffect> {
        val revision = dispatch(HostEvent.RefreshProviders)
            .singleEffect<HostEffect.DiscoverProviders>()
            .revision
        return dispatch(HostEvent.ProvidersDiscovered(revision, providers))
    }

    fun startActiveSession(
        providerId: ProviderId = ProviderA,
        configuration: SessionConfiguration = DefaultSessionConfiguration,
        at: MonotonicMillis = T0,
    ): SessionLease {
        discover(setOf(ProviderA, ProviderB))
        dispatch(HostEvent.SelectProvider(providerId))
        val binding = dispatch(
            HostEvent.OpenSession(configuration, state.editorGeneration, at),
        ).singleEffect<HostEffect.Bind>().lease
        val session = dispatch(HostEvent.BindingConnected(binding))
            .singleEffect<HostEffect.StartSession>()
            .lease
        dispatch(HostEvent.SessionStartResult(session, successful = true, at))
        return session
    }

    fun issue(at: MonotonicMillis = T0): RequestLease = dispatch(HostEvent.IssueRequest(state.editorGeneration, at))
        .singleEffect<HostEffect.RequestSuggestions>()
        .lease
}

internal inline fun <reified T : HostEffect> List<HostEffect>.singleEffect(): T = filterIsInstance<T>().single()
