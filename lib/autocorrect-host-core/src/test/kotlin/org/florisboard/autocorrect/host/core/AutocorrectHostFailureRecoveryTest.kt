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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class AutocorrectHostFailureRecoveryTest :
    FunSpec({
        test("bind rejection ends the waiting session and degrades only the bound provider") {
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

            val effects = host.dispatch(HostEvent.BindingFailed(binding, at = T0))

            assertSoftly {
                host.state.session shouldBe null
                host.state.binding shouldBe BindingState.Unbound
                host.state.healthOf(ProviderA).consecutiveFailures shouldBe 1
                host.state.healthOf(ProviderB) shouldBe ProviderHealth()
                effects.singleEffect<HostEffect.Unbind>().lease shouldBe binding
                effects.singleEffect<HostEffect.ProviderDegraded>().cause shouldBe
                    ProviderFailureKind.BIND_REJECTED
            }
        }

        test("failed session-start send cannot leave an admitted-looking session") {
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
            val start = host.dispatch(HostEvent.BindingConnected(binding))
                .singleEffect<HostEffect.StartSession>()

            val effects = host.dispatch(
                HostEvent.SessionStartResult(start.lease, successful = false, T0),
            )

            assertSoftly {
                host.state.session shouldBe null
                host.state.binding shouldBe BindingState.Unbound
                effects.singleEffect<HostEffect.ProviderDegraded>().cause shouldBe
                    ProviderFailureKind.SEND_FAILED
                effects.singleEffect<HostEffect.Unbind>().lease shouldBe binding
            }
        }

        test("service disconnection preserves the session but invalidates every transport lease") {
            val host = HostTestHarness()
            val session = host.startActiveSession()
            val request = host.issue()
            val binding = (host.state.binding as BindingState.Connected).lease

            val effects = host.dispatch(
                HostEvent.ConnectionLost(
                    binding,
                    ConnectionLossKind.SERVICE_DISCONNECTED,
                    T0,
                ),
            )

            assertSoftly {
                effects.filterIsInstance<HostEffect.CancelSuggestions>().single().lease shouldBe request
                effects.filterIsInstance<HostEffect.Unbind>().single().lease shouldBe binding
                effects.filterIsInstance<HostEffect.ProviderDegraded>().single().cause shouldBe
                    ProviderFailureKind.SERVICE_DISCONNECTED
                effects.filterIsInstance<HostEffect.FallbackRequired>().single().reason shouldBe
                    FallbackReason.PROVIDER_LOST
                val rebound = effects.filterIsInstance<HostEffect.Bind>().single().lease
                rebound.epoch.value shouldBe binding.epoch.value + 1
                host.state.session?.sessionId shouldBe session.sessionId
                host.state.session?.phase shouldBe SessionPhase.AWAITING_BINDING
            }
        }

        test("binding death and null binding end a session instead of reviving stale identity") {
            for (kind in listOf(ConnectionLossKind.BINDING_DIED, ConnectionLossKind.NULL_BINDING)) {
                val host = HostTestHarness()
                host.startActiveSession()
                val binding = (host.state.binding as BindingState.Connected).lease

                val effects = host.dispatch(HostEvent.ConnectionLost(binding, kind, T0))

                assertSoftly {
                    host.state.session shouldBe null
                    host.state.binding shouldBe BindingState.Unbound
                    effects.filterIsInstance<HostEffect.Bind>() shouldBe emptyList()
                    effects.filterIsInstance<HostEffect.ProviderDegraded>().single().cause shouldBe
                        when (kind) {
                            ConnectionLossKind.BINDING_DIED ->
                                ProviderFailureKind.BINDING_DIED

                            ConnectionLossKind.NULL_BINDING -> ProviderFailureKind.NULL_BINDING

                            else -> error("Test only covers terminal binding loss")
                        }
                }
            }
        }

        test("repeated request failures open the circuit and schedule recovery") {
            val policy = CircuitPolicy(failureThreshold = 3, recoveryDelayMillis = 500L)
            val host = HostTestHarness(policy)
            host.startActiveSession()

            repeat(2) { index ->
                val request = host.issue(MonotonicMillis(T0.value + index))
                val effects = host.dispatch(
                    HostEvent.RequestReply(
                        request,
                        RequestOutcome.Failure(ProviderFailureKind.TIMEOUT),
                        MonotonicMillis(T0.value + index),
                    ),
                )
                effects.filterIsInstance<HostEffect.ScheduleCircuitRecovery>() shouldBe emptyList()
                host.state.healthOf(ProviderA).circuit shouldBe CircuitState.Closed
            }

            val third = host.issue(MonotonicMillis(T0.value + 2))
            val effects = host.dispatch(
                HostEvent.RequestReply(
                    third,
                    RequestOutcome.Failure(ProviderFailureKind.TIMEOUT),
                    MonotonicMillis(T0.value + 2),
                ),
            )
            val open = host.state.healthOf(ProviderA).circuit.shouldBeInstanceOf<CircuitState.Open>()

            assertSoftly {
                open.retryAt shouldBe MonotonicMillis(T0.value + 502)
                effects.singleEffect<HostEffect.ScheduleCircuitRecovery>().retryAt shouldBe open.retryAt
                effects.singleEffect<HostEffect.ProviderDegraded>().health.consecutiveFailures shouldBe 3
            }
        }

        test("open circuit rejects work, allows one half-open probe, and closes on success") {
            val host = HostTestHarness(
                CircuitPolicy(failureThreshold = 1, recoveryDelayMillis = 100L),
            )
            host.startActiveSession()
            val first = host.issue(T0)
            host.dispatch(
                HostEvent.RequestReply(
                    first,
                    RequestOutcome.Failure(ProviderFailureKind.TIMEOUT),
                    T0,
                ),
            )

            host.dispatch(
                HostEvent.IssueRequest(host.state.editorGeneration, MonotonicMillis(1_099)),
            ).singleEffect<HostEffect.FallbackRequired>().reason shouldBe
                FallbackReason.CIRCUIT_OPEN
            host.dispatch(
                HostEvent.CircuitCooldownElapsed(ProviderA, MonotonicMillis(1_099)),
            ).singleEffect<HostEffect.EventIgnored>().reason shouldBe
                IgnoredReason.STALE_CIRCUIT_TIMER

            val halfOpenEffects = host.dispatch(
                HostEvent.CircuitCooldownElapsed(ProviderA, MonotonicMillis(1_100)),
            )
            halfOpenEffects.singleEffect<HostEffect.CircuitHalfOpened>().providerId shouldBe ProviderA
            host.state.healthOf(ProviderA).circuit shouldBe CircuitState.HalfOpen(false)

            val probe = host.issue(MonotonicMillis(1_100))
            host.state.healthOf(ProviderA).circuit shouldBe CircuitState.HalfOpen(true)
            host.dispatch(
                HostEvent.IssueRequest(host.state.editorGeneration, MonotonicMillis(1_100)),
            ).singleEffect<HostEffect.FallbackRequired>().reason shouldBe
                FallbackReason.CIRCUIT_PROBE_IN_FLIGHT

            val recovered = host.dispatch(
                HostEvent.RequestReply(probe, RequestOutcome.Success, MonotonicMillis(1_101)),
            )
            assertSoftly {
                recovered.singleEffect<HostEffect.AcceptReply>().lease shouldBe probe
                recovered.singleEffect<HostEffect.ProviderRecovered>().providerId shouldBe ProviderA
                host.state.healthOf(ProviderA) shouldBe ProviderHealth()
            }
        }

        test("a request arriving after cooldown advances the circuit without a timer callback") {
            val host = HostTestHarness(
                CircuitPolicy(failureThreshold = 1, recoveryDelayMillis = 100L),
            )
            host.startActiveSession()
            val failed = host.issue(T0)
            host.dispatch(
                HostEvent.RequestReply(
                    failed,
                    RequestOutcome.Failure(ProviderFailureKind.TIMEOUT),
                    T0,
                ),
            )

            val effects = host.dispatch(
                HostEvent.IssueRequest(host.state.editorGeneration, MonotonicMillis(1_100)),
            )

            assertSoftly {
                effects.singleEffect<HostEffect.CircuitHalfOpened>().providerId shouldBe ProviderA
                effects.singleEffect<HostEffect.RequestSuggestions>()
                host.state.healthOf(ProviderA).circuit shouldBe CircuitState.HalfOpen(true)
            }
        }

        test("failed half-open probe reopens the circuit from the new failure time") {
            val host = HostTestHarness(
                CircuitPolicy(failureThreshold = 1, recoveryDelayMillis = 100L),
            )
            host.startActiveSession()
            val first = host.issue(T0)
            host.dispatch(
                HostEvent.RequestReply(
                    first,
                    RequestOutcome.Failure(ProviderFailureKind.TIMEOUT),
                    T0,
                ),
            )
            host.dispatch(
                HostEvent.CircuitCooldownElapsed(ProviderA, MonotonicMillis(1_100)),
            )
            val probe = host.issue(MonotonicMillis(1_100))

            host.dispatch(
                HostEvent.RequestReply(
                    probe,
                    RequestOutcome.Failure(ProviderFailureKind.PROVIDER_ERROR),
                    MonotonicMillis(1_120),
                ),
            )
            val reopened = host.state.healthOf(ProviderA).circuit
                .shouldBeInstanceOf<CircuitState.Open>()
            reopened.retryAt shouldBe MonotonicMillis(1_220)
        }

        test("circuit cooldown can resume a session preserved after provider loss") {
            val host = HostTestHarness(
                CircuitPolicy(failureThreshold = 1, recoveryDelayMillis = 100L),
            )
            host.startActiveSession()
            val binding = (host.state.binding as BindingState.Connected).lease
            val loss = host.dispatch(
                HostEvent.ConnectionLost(binding, ConnectionLossKind.DEAD_REMOTE, T0),
            )
            loss.filterIsInstance<HostEffect.Bind>() shouldBe emptyList()
            host.state.session?.phase shouldBe SessionPhase.AWAITING_BINDING

            val recovery = host.dispatch(
                HostEvent.CircuitCooldownElapsed(ProviderA, MonotonicMillis(1_100)),
            )
            assertSoftly {
                recovery.singleEffect<HostEffect.CircuitHalfOpened>().providerId shouldBe ProviderA
                recovery.singleEffect<HostEffect.Bind>().lease.providerId shouldBe ProviderA
                host.state.binding.shouldBeInstanceOf<BindingState.Connecting>()
            }
        }

        test("send failure counts against provider health and never accepts a later reply") {
            val host = HostTestHarness()
            host.startActiveSession()
            val request = host.issue()

            val effects = host.dispatch(
                HostEvent.RequestSendFailed(request, ProviderFailureKind.SEND_FAILED, T0),
            )
            effects.singleEffect<HostEffect.ProviderDegraded>().cause shouldBe
                ProviderFailureKind.SEND_FAILED
            host.dispatch(
                HostEvent.RequestReply(request, RequestOutcome.Success, T0),
            ).singleEffect<HostEffect.RejectReply>().reason shouldBe
                ReplyRejectionReason.CANCELLED
        }

        test("late loss signal for an old binding cannot damage the replacement") {
            val host = HostTestHarness()
            host.startActiveSession()
            val old = (host.state.binding as BindingState.Connected).lease
            val replacement = host.dispatch(
                HostEvent.ConnectionLost(old, ConnectionLossKind.SERVICE_DISCONNECTED, T0),
            ).singleEffect<HostEffect.Bind>().lease

            host.dispatch(
                HostEvent.ConnectionLost(old, ConnectionLossKind.BINDING_DIED, T0),
            ).singleEffect<HostEffect.EventIgnored>().reason shouldBe
                IgnoredReason.STALE_BINDING
            host.state.binding shouldBe BindingState.Connecting(replacement)
        }
    })
