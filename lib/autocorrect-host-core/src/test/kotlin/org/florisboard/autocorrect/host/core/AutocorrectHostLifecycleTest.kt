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

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AutocorrectHostLifecycleTest :
    FunSpec({
        test("discovery, binding, and session admission form an explicit ordered lifecycle") {
            val host = HostTestHarness()

            val discovery = host.dispatch(HostEvent.RefreshProviders)
                .singleEffect<HostEffect.DiscoverProviders>()
            host.state.discovery shouldBe DiscoveryState.Loading(discovery.revision)

            host.dispatch(
                HostEvent.ProvidersDiscovered(
                    discovery.revision,
                    setOf(ProviderA, ProviderB),
                ),
            ) shouldBe emptyList()
            host.dispatch(HostEvent.SelectProvider(ProviderA))

            val binding = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            ).singleEffect<HostEffect.Bind>().lease
            host.state.session?.phase shouldBe SessionPhase.AWAITING_BINDING
            host.state.binding shouldBe BindingState.Connecting(binding)

            val start = host.dispatch(HostEvent.BindingConnected(binding))
                .singleEffect<HostEffect.StartSession>()
            host.state.session?.phase shouldBe SessionPhase.STARTING
            start.configuration shouldBe DefaultSessionConfiguration

            host.dispatch(HostEvent.SessionStartSending(start.lease))
            host.dispatch(HostEvent.SessionStartResult(start.lease, successful = true, T0))
            host.state.session?.phase shouldBe SessionPhase.ACTIVE
            host.state.requireValid()
        }

        test("stale discovery results cannot replace a newer provider snapshot") {
            val host = HostTestHarness()
            val first = host.dispatch(HostEvent.RefreshProviders)
                .singleEffect<HostEffect.DiscoverProviders>()
            val second = host.dispatch(HostEvent.RefreshProviders)
                .singleEffect<HostEffect.DiscoverProviders>()

            val staleEffects = host.dispatch(
                HostEvent.ProvidersDiscovered(first.revision, setOf(ProviderA)),
            )
            staleEffects.singleEffect<HostEffect.EventIgnored>().reason shouldBe
                IgnoredReason.STALE_DISCOVERY
            host.state.discovery shouldBe DiscoveryState.Loading(second.revision)

            host.dispatch(
                HostEvent.ProvidersDiscovered(second.revision, setOf(ProviderB)),
            )
            host.state.discovery shouldBe DiscoveryState.Ready(second.revision, setOf(ProviderB))
        }

        test("discovery failure closes an unbound waiting session") {
            val host = HostTestHarness()
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val discovery = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            ).singleEffect<HostEffect.DiscoverProviders>()
            host.state.session?.phase shouldBe SessionPhase.AWAITING_BINDING

            val effects = host.dispatch(HostEvent.ProviderDiscoveryFailed(discovery.revision))

            assertSoftly {
                host.state.discovery shouldBe DiscoveryState.Failed(discovery.revision)
                host.state.session shouldBe null
                effects.singleEffect<HostEffect.FallbackRequired>().reason shouldBe
                    FallbackReason.DISCOVERY_FAILED
            }
        }

        test("session opening reports selection and availability failures without side effects") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderB))

            host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            ).singleEffect<HostEffect.FallbackRequired>().reason shouldBe
                FallbackReason.NO_PROVIDER_SELECTED

            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val unavailable = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            )
            unavailable.singleEffect<HostEffect.FallbackRequired>().reason shouldBe
                FallbackReason.PROVIDER_UNAVAILABLE
            host.state.session shouldBe null
            host.state.binding shouldBe BindingState.Unbound
        }

        test("session configuration is snapshotted and an identical session is reused") {
            val host = HostTestHarness()
            val secondaryLanguages = mutableListOf("de-DE")
            val configuration = DefaultSessionConfiguration.copy(
                secondaryLanguageTags = secondaryLanguages,
            )
            host.startActiveSession(configuration = configuration)
            val originalSession = host.state.session

            secondaryLanguages += "fr-FR"
            host.state.session?.configuration?.secondaryLanguageTags shouldContainExactly
                listOf("de-DE")
            host.dispatch(
                HostEvent.OpenSession(
                    configuration.copy(secondaryLanguageTags = listOf("de-DE")),
                    host.state.editorGeneration,
                    T0,
                ),
            ) shouldBe emptyList()
            host.state.session shouldBe originalSession
        }

        test("changed session configuration finishes the admitted session and starts the new one") {
            val host = HostTestHarness()
            val oldConfiguration = DefaultSessionConfiguration.copy(
                secondaryLanguageTags = listOf("de-DE"),
            )
            val newConfiguration = oldConfiguration.copy(
                secondaryLanguageTags = listOf("fr-FR"),
                capsMode = 1,
                allowPersonalizedLearning = false,
            )
            host.startActiveSession(configuration = oldConfiguration)
            val oldSessionId = host.state.session!!.sessionId

            val effects = host.dispatch(
                HostEvent.OpenSession(
                    newConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            )

            assertSoftly {
                val finish = effects.filterIsInstance<HostEffect.FinishSession>().single()
                val start = effects.filterIsInstance<HostEffect.StartSession>().single()
                finish.lease.sessionId shouldBe oldSessionId
                finish.configuration shouldBe oldConfiguration
                start.configuration shouldBe newConfiguration
                (effects.indexOf(finish) < effects.indexOf(start)) shouldBe true
                host.state.pendingFinishes.keys shouldContainExactly listOf(oldSessionId)
                host.state.session?.phase shouldBe SessionPhase.STARTING
            }
        }

        test("changing the learning policy cannot silently reuse an admitted session") {
            val host = HostTestHarness()
            host.startActiveSession()
            val oldSessionId = host.state.session!!.sessionId

            val effects = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration.copy(allowPersonalizedLearning = false),
                    host.state.editorGeneration,
                    T0,
                ),
            )

            effects.singleEffect<HostEffect.FinishSession>().lease.sessionId shouldBe oldSessionId
            effects.singleEffect<HostEffect.StartSession>()
                .configuration.allowPersonalizedLearning shouldBe false
            host.state.session!!.sessionId shouldBe SessionId(oldSessionId.value + 2L)
        }

        test("UI demand binds without typing and releases only after its last lease ends") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))

            val binding = host.dispatch(HostEvent.SetUiBindingDemand(true))
                .singleEffect<HostEffect.Bind>().lease
            host.state.session shouldBe null
            host.dispatch(HostEvent.SetUiBindingDemand(true))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.NO_CHANGE
            host.dispatch(HostEvent.BindingConnected(binding)) shouldBe emptyList()
            host.state.binding shouldBe BindingState.Connected(binding)

            host.dispatch(HostEvent.SetUiBindingDemand(false))
                .singleEffect<HostEffect.Unbind>().lease shouldBe binding
            host.state.binding shouldBe BindingState.Unbound
        }

        test("UI demand withdrawn before connection rejects the late callback") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val binding = host.dispatch(HostEvent.SetUiBindingDemand(true))
                .singleEffect<HostEffect.Bind>().lease

            host.dispatch(HostEvent.SetUiBindingDemand(false))
                .singleEffect<HostEffect.Unbind>().lease shouldBe binding
            host.dispatch(HostEvent.BindingConnected(binding))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_BINDING
            host.state.binding shouldBe BindingState.Unbound
            host.state.session shouldBe null
        }

        test("closing typing leaves a UI-only binding until the finish is acknowledged and UI closes") {
            val host = HostTestHarness()
            host.startActiveSession()
            val generation = host.state.editorGeneration
            val binding = (host.state.binding as BindingState.Connected).lease
            host.dispatch(HostEvent.SetUiBindingDemand(true))

            val finish = host.dispatch(HostEvent.CloseSession)
                .singleEffect<HostEffect.FinishSession>().lease
            host.state.session shouldBe null
            host.state.editorGeneration shouldBe generation
            host.state.binding shouldBe BindingState.Connected(binding)

            host.dispatch(
                HostEvent.FinishAcknowledged(ProviderA, binding.epoch, finish.sessionId, T0),
            ) shouldBe emptyList()
            host.state.binding shouldBe BindingState.Connected(binding)

            host.dispatch(HostEvent.SetUiBindingDemand(false))
                .singleEffect<HostEffect.Unbind>().lease shouldBe binding
        }

        test("closing before START is sent does not queue a phantom finish") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val binding = host.dispatch(
                HostEvent.OpenSession(DefaultSessionConfiguration, host.state.editorGeneration, T0),
            ).singleEffect<HostEffect.Bind>().lease
            val start = host.dispatch(HostEvent.BindingConnected(binding))
                .singleEffect<HostEffect.StartSession>().lease
            host.state.session?.phase shouldBe SessionPhase.STARTING

            host.dispatch(HostEvent.CloseSession)
                .filterIsInstance<HostEffect.FinishSession>() shouldBe emptyList()
            host.state.pendingFinishes shouldBe emptyMap()
            host.dispatch(HostEvent.SessionStartSending(start))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_BINDING
            host.dispatch(HostEvent.SessionStartResult(start, successful = true, T0))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_BINDING
        }

        test("closing during a START send retains the binding until its ordered finish") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val binding = host.dispatch(
                HostEvent.OpenSession(DefaultSessionConfiguration, host.state.editorGeneration, T0),
            ).singleEffect<HostEffect.Bind>().lease
            val start = host.dispatch(HostEvent.BindingConnected(binding))
                .singleEffect<HostEffect.StartSession>().lease
            host.dispatch(HostEvent.SessionStartSending(start))
            host.state.session?.phase shouldBe SessionPhase.SENDING_START

            val finish = host.dispatch(HostEvent.CloseSession)
                .singleEffect<HostEffect.FinishSession>().lease
            finish.sessionId shouldBe start.sessionId
            host.state.pendingFinishes.keys shouldContainExactly listOf(start.sessionId)
            host.dispatch(HostEvent.SessionStartResult(start, successful = true, T0))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_SESSION
        }

        test("transport failure during a closed START drops its unsent finish") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val binding = host.dispatch(
                HostEvent.OpenSession(DefaultSessionConfiguration, host.state.editorGeneration, T0),
            ).singleEffect<HostEffect.Bind>().lease
            val start = host.dispatch(HostEvent.BindingConnected(binding))
                .singleEffect<HostEffect.StartSession>().lease
            host.dispatch(HostEvent.SessionStartSending(start))
            val finish = host.dispatch(HostEvent.CloseSession)
                .singleEffect<HostEffect.FinishSession>().lease

            host.dispatch(HostEvent.ConnectionLost(binding, ConnectionLossKind.DEAD_REMOTE, T0))
                .singleEffect<HostEffect.Unbind>().lease shouldBe binding
            host.state.pendingFinishes shouldBe emptyMap()
            host.dispatch(HostEvent.SessionStartResult(start, successful = false, T0))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_BINDING
            host.dispatch(HostEvent.FinishSendFailed(finish, T0))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.UNKNOWN_FINISH
        }

        test("privacy configuration change skips an old queued START and its phantom finish") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val binding = host.dispatch(
                HostEvent.OpenSession(DefaultSessionConfiguration, host.state.editorGeneration, T0),
            ).singleEffect<HostEffect.Bind>().lease
            val oldStart = host.dispatch(HostEvent.BindingConnected(binding))
                .singleEffect<HostEffect.StartSession>().lease

            val privateConfiguration = DefaultSessionConfiguration.copy(allowPersonalizedLearning = false)
            val effects = host.dispatch(
                HostEvent.OpenSession(privateConfiguration, host.state.editorGeneration, T0),
            )
            effects.filterIsInstance<HostEffect.FinishSession>() shouldBe emptyList()
            val newStart = effects.singleEffect<HostEffect.StartSession>().lease
            newStart.sessionId shouldBe host.state.session?.sessionId
            (oldStart.sessionId != newStart.sessionId) shouldBe true
            host.dispatch(HostEvent.SessionStartSending(oldStart))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_SESSION
            host.dispatch(HostEvent.SessionStartSending(newStart)) shouldBe emptyList()
            host.dispatch(HostEvent.SessionStartResult(newStart, true, T0)) shouldBe emptyList()
            host.state.session?.configuration shouldBe privateConfiguration
            host.state.session?.phase shouldBe SessionPhase.ACTIVE
        }

        test("privacy configuration change during START sends FINISH before the next START") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val binding = host.dispatch(
                HostEvent.OpenSession(DefaultSessionConfiguration, host.state.editorGeneration, T0),
            ).singleEffect<HostEffect.Bind>().lease
            val oldStart = host.dispatch(HostEvent.BindingConnected(binding))
                .singleEffect<HostEffect.StartSession>().lease
            host.dispatch(HostEvent.SessionStartSending(oldStart))

            val effects = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration.copy(allowPersonalizedLearning = false),
                    host.state.editorGeneration,
                    T0,
                ),
            )
            val finish = effects.singleEffect<HostEffect.FinishSession>().lease
            val newStart = effects.singleEffect<HostEffect.StartSession>().lease
            finish.sessionId shouldBe oldStart.sessionId
            (
                effects.indexOfFirst { it is HostEffect.FinishSession } <
                    effects.indexOfFirst { it is HostEffect.StartSession }
                ) shouldBe true
            host.dispatch(HostEvent.SessionStartResult(oldStart, true, T0))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_SESSION
            host.dispatch(HostEvent.SessionStartSending(newStart)) shouldBe emptyList()
            host.dispatch(HostEvent.SessionStartResult(newStart, true, T0)) shouldBe emptyList()
            host.state.pendingFinishes.keys shouldContainExactly listOf(oldStart.sessionId)
        }

        test("a UI-only provider switch releases the old binding and uses a new epoch") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA, ProviderB))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val old = host.dispatch(HostEvent.SetUiBindingDemand(true))
                .singleEffect<HostEffect.Bind>().lease
            host.dispatch(HostEvent.BindingConnected(old))

            val effects = host.dispatch(HostEvent.SelectProvider(ProviderB))
            effects.singleEffect<HostEffect.Unbind>().lease shouldBe old
            val new = effects.singleEffect<HostEffect.Bind>().lease
            new.providerId shouldBe ProviderB
            new.epoch.value shouldBe old.epoch.value + 1L

            host.dispatch(HostEvent.BindingConnected(old))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_BINDING
            host.state.binding shouldBe BindingState.Connecting(new)
        }

        test("provider switch drops an old pending finish and binds UI without its acknowledgement") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            val old = (host.state.binding as BindingState.Connected).lease
            val finish = host.dispatch(HostEvent.CloseSession)
                .singleEffect<HostEffect.FinishSession>().lease
            val switch = host.dispatch(HostEvent.SelectProvider(ProviderB))
            switch.singleEffect<HostEffect.Unbind>().lease shouldBe old
            host.state.pendingFinishes shouldBe emptyMap()
            host.dispatch(
                HostEvent.FinishAcknowledged(ProviderA, old.epoch, finish.sessionId, T0),
            ).singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.UNKNOWN_FINISH

            val new = host.dispatch(HostEvent.SetUiBindingDemand(true))
                .singleEffect<HostEffect.Bind>().lease
            new.providerId shouldBe ProviderB
            new.epoch.value shouldBe old.epoch.value + 1L
        }

        test("same-provider reselection abandons pending finish and rejects old callbacks") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            val old = (host.state.binding as BindingState.Connected).lease
            val finish = host.dispatch(HostEvent.CloseSession)
                .singleEffect<HostEffect.FinishSession>().lease
            host.dispatch(HostEvent.SetUiBindingDemand(true))

            val effects = host.dispatch(HostEvent.SelectProvider(ProviderA, forceRebind = true))
            effects.singleEffect<HostEffect.Unbind>().lease shouldBe old
            val fresh = effects.singleEffect<HostEffect.Bind>().lease
            fresh.providerId shouldBe ProviderA
            fresh.epoch.value shouldBe old.epoch.value + 1L
            host.state.pendingFinishes shouldBe emptyMap()
            host.dispatch(HostEvent.FinishAcknowledged(ProviderA, old.epoch, finish.sessionId, T0))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.UNKNOWN_FINISH
            host.dispatch(HostEvent.BindingConnected(old))
                .singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.STALE_BINDING
            host.dispatch(HostEvent.BindingConnected(fresh)) shouldBe emptyList()
            host.state.binding shouldBe BindingState.Connected(fresh)
        }

        test("same-provider reselection finishes an active session before unbinding") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            host.dispatch(HostEvent.SetUiBindingDemand(true))

            val effects = host.dispatch(HostEvent.SelectProvider(ProviderA, forceRebind = true))

            effects.filterIsInstance<HostEffect.FinishSession>().size shouldBe 1
            effects.filterIsInstance<HostEffect.Unbind>().size shouldBe 1
            (
                effects.indexOfFirst { it is HostEffect.FinishSession } <
                    effects.indexOfFirst { it is HostEffect.Unbind }
                ) shouldBe true
            effects.filterIsInstance<HostEffect.Bind>().size shouldBe 1
            host.state.pendingFinishes shouldBe emptyMap()
        }

        test("switching to no provider releases pending finish work without rebinding") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            val old = (host.state.binding as BindingState.Connected).lease
            val finish = host.dispatch(HostEvent.CloseSession)
                .singleEffect<HostEffect.FinishSession>().lease
            val effects = host.dispatch(HostEvent.SelectProvider(null))
            effects.singleEffect<HostEffect.Unbind>().lease shouldBe old
            effects.filterIsInstance<HostEffect.Bind>() shouldBe emptyList()
            host.state.binding shouldBe BindingState.Unbound
            host.state.pendingFinishes shouldBe emptyMap()
            host.dispatch(
                HostEvent.FinishAcknowledged(ProviderA, old.epoch, finish.sessionId, T0),
            ).singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.UNKNOWN_FINISH
        }

        test("provider switch sends best-effort finish before unbind and starts a fresh binding") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            val oldBinding = (host.state.binding as BindingState.Connected).lease
            val oldSession = host.state.session!!.sessionId

            val switchEffects = host.dispatch(HostEvent.SelectProvider(ProviderB))
            switchEffects.filterIsInstance<HostEffect.FinishSession>().single().lease.sessionId shouldBe oldSession
            switchEffects.filterIsInstance<HostEffect.Unbind>().single().lease shouldBe oldBinding
            (
                switchEffects.indexOfFirst { it is HostEffect.FinishSession } <
                    switchEffects.indexOfFirst { it is HostEffect.Unbind }
                ) shouldBe true
            host.state.pendingFinishes shouldBe emptyMap()

            val openEffects = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            )
            val newBinding = openEffects.singleEffect<HostEffect.Bind>().lease
            newBinding.providerId shouldBe ProviderB
            newBinding.epoch.value shouldBe oldBinding.epoch.value + 1L

            host.dispatch(
                HostEvent.FinishAcknowledged(
                    ProviderA,
                    oldBinding.epoch,
                    oldSession,
                    T0,
                ),
            ).singleEffect<HostEffect.EventIgnored>().reason shouldBe IgnoredReason.UNKNOWN_FINISH
            host.state.binding shouldBe BindingState.Connecting(newBinding)
        }

        test("editor invalidation drops a new waiting session after a forced switch") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            host.dispatch(HostEvent.SelectProvider(ProviderB))
            val newBinding = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            ).singleEffect<HostEffect.Bind>().lease

            val invalidateEffects = host.dispatch(HostEvent.InvalidateEditor)
            assertSoftly {
                invalidateEffects.singleEffect<HostEffect.Unbind>().lease shouldBe newBinding
                host.state.session shouldBe null
                host.state.binding shouldBe BindingState.Unbound
                host.state.pendingFinishes shouldBe emptyMap()
            }
        }

        test("editor invalidation cancels work, finishes the session, and releases after ack") {
            val host = HostTestHarness()
            host.startActiveSession()
            val request = host.issue()
            val oldGeneration = host.state.editorGeneration
            val binding = (host.state.binding as BindingState.Connected).lease
            val session = host.state.session!!.sessionId

            val invalidateEffects = host.dispatch(HostEvent.InvalidateEditor)
            assertSoftly {
                host.state.editorGeneration.value shouldBe oldGeneration.value + 1
                host.state.session shouldBe null
                host.state.pendingRequest shouldBe null
                invalidateEffects.filterIsInstance<HostEffect.CancelSuggestions>().single().apply {
                    lease shouldBe request
                    reason shouldBe RequestCancellationReason.EDITOR_INVALIDATED
                }
                invalidateEffects.filterIsInstance<HostEffect.FinishSession>().single()
                    .lease.sessionId shouldBe session
                invalidateEffects.filterIsInstance<HostEffect.Unbind>() shouldBe emptyList()
            }

            val ackEffects = host.dispatch(
                HostEvent.FinishAcknowledged(ProviderA, binding.epoch, session, T0),
            )
            ackEffects.singleEffect<HostEffect.Unbind>().lease shouldBe binding
            host.state.binding shouldBe BindingState.Unbound

            host.dispatch(
                HostEvent.FinishAcknowledged(ProviderA, binding.epoch, session, T0),
            ).singleEffect<HostEffect.EventIgnored>().reason shouldBe
                IgnoredReason.UNKNOWN_FINISH
        }

        test("destroy emits best-effort cleanup then permanently closes lifecycle ownership") {
            val host = HostTestHarness()
            host.startActiveSession()
            host.issue()

            val effects = host.dispatch(HostEvent.Destroy)
            assertSoftly {
                effects.filterIsInstance<HostEffect.CancelSuggestions>().size shouldBe 1
                effects.filterIsInstance<HostEffect.FinishSession>().size shouldBe 1
                effects.filterIsInstance<HostEffect.Unbind>().size shouldBe 1
                effects.last() shouldBe HostEffect.ReleaseOwnedResources
                host.state.lifecycle shouldBe HostLifecycle.DESTROYED
                host.state.requireValid()
            }

            val ignored = host.dispatch(HostEvent.RefreshProviders)
                .singleEffect<HostEffect.EventIgnored>()
            ignored.reason shouldBe IgnoredReason.HOST_DESTROYED
        }

        test("a provider removed by discovery cannot retain an active session or binding") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            val refresh = host.dispatch(HostEvent.RefreshProviders)
                .singleEffect<HostEffect.DiscoverProviders>()

            val effects = host.dispatch(
                HostEvent.ProvidersDiscovered(refresh.revision, setOf(ProviderB)),
            )

            assertSoftly {
                host.state.session shouldBe null
                host.state.binding shouldBe BindingState.Unbound
                host.state.pendingFinishes shouldBe emptyMap()
                effects.filterIsInstance<HostEffect.FinishSession>().size shouldBe 1
                effects.filterIsInstance<HostEffect.Unbind>().size shouldBe 1
                effects.filterIsInstance<HostEffect.FallbackRequired>().single().reason shouldBe
                    FallbackReason.PROVIDER_UNAVAILABLE
            }
        }

        test("provider, session, request, and binding identifiers share a monotonic allocation order") {
            val host = HostTestHarness()
            val session = host.startActiveSession()
            val request = host.issue()
            host.dispatch(HostEvent.InvalidateEditor)
            val finish = host.state.pendingFinishes.values.single().lease

            assertSoftly {
                session.sessionId.value shouldBe 1L
                request.requestId.value shouldBe 2L
                finish.finalRequestId.value shouldBe 3L
            }
        }

        test("state exposes concrete lifecycle types instead of nullable transport flags") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val binding = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            ).singleEffect<HostEffect.Bind>().lease

            host.state.binding.shouldBeInstanceOf<BindingState.Connecting>()
            host.dispatch(HostEvent.BindingConnected(binding))
            host.state.binding.shouldBeInstanceOf<BindingState.Connected>()
        }

        test("a connection callback for an old epoch cannot attach") {
            val host = HostTestHarness()
            host.discover(setOf(ProviderA))
            host.dispatch(HostEvent.SelectProvider(ProviderA))
            val current = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            ).singleEffect<HostEffect.Bind>().lease
            val stale = current.copy(epoch = BindingEpoch(current.epoch.value + 10))

            host.dispatch(
                HostEvent.BindingConnected(stale),
            ).singleEffect<HostEffect.EventIgnored>().reason shouldBe
                IgnoredReason.STALE_BINDING
            host.state.binding shouldBe BindingState.Connecting(current)
        }
    })
