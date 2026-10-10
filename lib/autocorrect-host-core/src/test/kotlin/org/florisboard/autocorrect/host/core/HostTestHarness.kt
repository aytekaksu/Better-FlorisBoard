/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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

    fun openSession(
        configuration: SessionConfiguration = DefaultSessionConfiguration,
        editorGeneration: EditorGeneration = state.editorGeneration,
        at: MonotonicMillis = T0,
    ): List<HostEffect> = dispatch(HostEvent.OpenSession(configuration, editorGeneration, at))

    fun awaitSessionBinding(
        providerId: ProviderId = ProviderA,
        configuration: SessionConfiguration = DefaultSessionConfiguration,
        at: MonotonicMillis = T0,
        providers: Set<ProviderId> = setOf(ProviderA),
    ): BindingLease {
        discover(providers)
        dispatch(HostEvent.SelectProvider(providerId))
        return dispatch(
            HostEvent.OpenSession(configuration, state.editorGeneration, at),
        ).singleEffect<HostEffect.Bind>().lease
    }

    fun queueSessionStart(binding: BindingLease): SessionLease = dispatch(HostEvent.BindingConnected(binding))
        .singleEffect<HostEffect.StartSession>()
        .lease

    fun startActiveSession(
        providerId: ProviderId = ProviderA,
        configuration: SessionConfiguration = DefaultSessionConfiguration,
        at: MonotonicMillis = T0,
    ): SessionLease {
        val binding = awaitSessionBinding(providerId, configuration, at, providers = setOf(ProviderA, ProviderB))
        val session = queueSessionStart(binding)
        dispatch(HostEvent.SessionStartSending(session))
        dispatch(HostEvent.SessionStartResult(session, successful = true, at))
        return session
    }

    fun issue(at: MonotonicMillis = T0): RequestLease = dispatch(HostEvent.IssueRequest(state.editorGeneration, at))
        .singleEffect<HostEffect.RequestSuggestions>()
        .lease

    fun replyFailure(lease: RequestLease, kind: ProviderFailureKind, at: MonotonicMillis = T0): List<HostEffect> =
        dispatch(HostEvent.RequestReply(lease, RequestOutcome.Failure(kind), at))
}

internal inline fun <reified T : HostEffect> List<HostEffect>.singleEffect(): T = filterIsInstance<T>().single()
