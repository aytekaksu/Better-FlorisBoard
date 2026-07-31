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
            host.startActiveSession()
            val oldSessionId = host.state.session!!.sessionId

            val effects = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration.copy(capsMode = 1),
                    host.state.editorGeneration,
                    T0,
                ),
            )

            assertSoftly {
                effects.filterIsInstance<HostEffect.FinishSession>().single()
                    .lease.sessionId shouldBe oldSessionId
                effects.filterIsInstance<HostEffect.StartSession>().single()
                    .configuration.capsMode shouldBe 1
                host.state.pendingFinishes.keys shouldContainExactly listOf(oldSessionId)
                host.state.session?.phase shouldBe SessionPhase.STARTING
            }
        }

        test("provider switch waits for the old finish acknowledgement before rebinding") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            val oldBinding = (host.state.binding as BindingState.Connected).lease
            val oldSession = host.state.session!!.sessionId

            val switchEffects = host.dispatch(HostEvent.SelectProvider(ProviderB))
            switchEffects.singleEffect<HostEffect.FinishSession>().lease.sessionId shouldBe oldSession
            switchEffects.filterIsInstance<HostEffect.Unbind>() shouldBe emptyList()

            val openEffects = host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            )
            openEffects.filterIsInstance<HostEffect.Bind>() shouldBe emptyList()
            host.state.queuedProvider shouldBe ProviderB

            val wrongAck = host.dispatch(
                HostEvent.FinishAcknowledged(
                    ProviderA,
                    BindingEpoch(oldBinding.epoch.value + 1),
                    oldSession,
                    T0,
                ),
            )
            wrongAck.singleEffect<HostEffect.EventIgnored>().reason shouldBe
                IgnoredReason.STALE_BINDING

            val ackEffects = host.dispatch(
                HostEvent.FinishAcknowledged(
                    ProviderA,
                    oldBinding.epoch,
                    oldSession,
                    T0,
                ),
            )
            assertSoftly {
                ackEffects.filterIsInstance<HostEffect.Unbind>().single().lease shouldBe oldBinding
                val newBinding = ackEffects.filterIsInstance<HostEffect.Bind>().single().lease
                newBinding.providerId shouldBe ProviderB
                newBinding.epoch.value shouldBe oldBinding.epoch.value + 1
                host.state.queuedProvider shouldBe null
            }
        }

        test("editor invalidation drops a queued provider while the old finish is pending") {
            val host = HostTestHarness()
            host.startActiveSession(ProviderA)
            val oldBinding = (host.state.binding as BindingState.Connected).lease
            val oldSession = host.state.session!!.sessionId

            host.dispatch(HostEvent.SelectProvider(ProviderB))
            host.dispatch(
                HostEvent.OpenSession(
                    DefaultSessionConfiguration,
                    host.state.editorGeneration,
                    T0,
                ),
            )
            host.state.queuedProvider shouldBe ProviderB

            val invalidateEffects = host.dispatch(HostEvent.InvalidateEditor)
            assertSoftly {
                invalidateEffects shouldBe emptyList()
                host.state.session shouldBe null
                host.state.queuedProvider shouldBe null
                host.state.binding shouldBe BindingState.Connected(oldBinding)
                host.state.pendingFinishes.keys shouldContainExactly listOf(oldSession)
            }

            val ackEffects = host.dispatch(
                HostEvent.FinishAcknowledged(ProviderA, oldBinding.epoch, oldSession, T0),
            )
            ackEffects.singleEffect<HostEffect.Unbind>().lease shouldBe oldBinding
            host.state.binding shouldBe BindingState.Unbound
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
