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

package dev.patrickgold.florisboard.ime.nlp.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier

class AutocorrectPluginDiagnosticsTest :
    FunSpec({
        test("history is bounded ordered and returns detached snapshots") {
            val diagnostics = AutocorrectPluginDiagnostics(capacity = 3)

            repeat(5) { epoch ->
                diagnostics.record(
                    AutocorrectPluginDiagnosticEvent.Binding(
                        bindingEpoch = epoch.toLong(),
                        state = AutocorrectPluginDiagnosticState.CONNECTED,
                        error = AutocorrectPluginDiagnosticError.NONE,
                    ),
                )
            }

            val snapshot = diagnostics.snapshot()
            snapshot.records.map { it.sequence } shouldContainExactly listOf(3L, 4L, 5L)
            snapshot.droppedRecordCount shouldBe 2L

            diagnostics.record(
                AutocorrectPluginDiagnosticEvent.Binding(
                    bindingEpoch = 5L,
                    state = AutocorrectPluginDiagnosticState.DISCONNECTED,
                    error = AutocorrectPluginDiagnosticError.NONE,
                ),
            )
            snapshot.records.map { it.sequence } shouldContainExactly listOf(3L, 4L, 5L)
        }

        test("operation duration is coarse and IDs stay opaque") {
            var now = 1_000_000L
            val diagnostics = AutocorrectPluginDiagnostics(
                monotonicNanos = { now },
            )
            diagnostics.operationStarted(
                operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                bindingEpoch = 7L,
                sessionId = 11L,
                requestId = 19L,
            )
            now += 73_000_000L
            diagnostics.operationFinished(
                operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                bindingEpoch = 7L,
                requestId = 19L,
                state = AutocorrectPluginDiagnosticState.SUCCEEDED,
                itemCount = Int.MAX_VALUE,
            )

            diagnostics.snapshot().records.map { it.event } shouldContainExactly listOf(
                AutocorrectPluginDiagnosticEvent.Operation(
                    bindingEpoch = 7L,
                    sessionId = AutocorrectPluginDiagnosticId.fromHostId(11L),
                    requestId = AutocorrectPluginDiagnosticId.fromHostId(19L),
                    operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                    state = AutocorrectPluginDiagnosticState.STARTED,
                    duration = AutocorrectPluginDiagnosticDuration.UNKNOWN,
                    itemCount = 0,
                    error = AutocorrectPluginDiagnosticError.NONE,
                ),
                AutocorrectPluginDiagnosticEvent.Operation(
                    bindingEpoch = 7L,
                    sessionId = AutocorrectPluginDiagnosticId.fromHostId(11L),
                    requestId = AutocorrectPluginDiagnosticId.fromHostId(19L),
                    operation = AutocorrectPluginDiagnosticOperation.SUGGESTION,
                    state = AutocorrectPluginDiagnosticState.SUCCEEDED,
                    duration = AutocorrectPluginDiagnosticDuration.FROM_50_TO_149_MS,
                    itemCount = 10_000,
                    error = AutocorrectPluginDiagnosticError.NONE,
                ),
            )
        }

        test("duration boundaries do not expose exact timings") {
            listOf(
                0L to AutocorrectPluginDiagnosticDuration.UNDER_1_MS,
                1L to AutocorrectPluginDiagnosticDuration.FROM_1_TO_4_MS,
                5L to AutocorrectPluginDiagnosticDuration.FROM_5_TO_15_MS,
                16L to AutocorrectPluginDiagnosticDuration.FROM_16_TO_49_MS,
                50L to AutocorrectPluginDiagnosticDuration.FROM_50_TO_149_MS,
                150L to AutocorrectPluginDiagnosticDuration.FROM_150_TO_499_MS,
                500L to AutocorrectPluginDiagnosticDuration.FROM_500_TO_1_999_MS,
                2_000L to AutocorrectPluginDiagnosticDuration.AT_LEAST_2_SECONDS,
            ).forEach { (millis, expected) ->
                diagnosticDurationForNanos(millis * 1_000_000L) shouldBe expected
            }
        }

        test("event schema only permits closed enums IDs epochs and counts") {
            val eventClasses = listOf(
                AutocorrectPluginDiagnosticEvent.Discovery::class.java,
                AutocorrectPluginDiagnosticEvent.Binding::class.java,
                AutocorrectPluginDiagnosticEvent.Session::class.java,
                AutocorrectPluginDiagnosticEvent.Operation::class.java,
                AutocorrectPluginDiagnosticEvent.ReplyRejected::class.java,
            )
            val permittedFieldTypes = setOf(
                java.lang.Integer.TYPE,
                java.lang.Long.TYPE,
                AutocorrectPluginDiagnosticOperation::class.java,
                AutocorrectPluginDiagnosticState::class.java,
                AutocorrectPluginDiagnosticError::class.java,
                AutocorrectPluginDiagnosticDuration::class.java,
            )

            val unexpectedFields = eventClasses.flatMap { eventClass ->
                eventClass.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .filterNot { it.type in permittedFieldTypes }
                    .map { "${eventClass.simpleName}.${it.name}: ${it.type.name}" }
            }

            unexpectedFields shouldBe emptyList()
        }

        test("concurrent writers preserve a valid bounded sequence") {
            val capacity = 64
            val writerCount = 8
            val recordsPerWriter = 200
            val diagnostics = AutocorrectPluginDiagnostics(capacity = capacity)
            val writers = List(writerCount) { writer ->
                Thread {
                    repeat(recordsPerWriter) { index ->
                        diagnostics.record(
                            AutocorrectPluginDiagnosticEvent.Binding(
                                bindingEpoch = (writer * recordsPerWriter + index).toLong(),
                                state = AutocorrectPluginDiagnosticState.CONNECTED,
                                error = AutocorrectPluginDiagnosticError.NONE,
                            ),
                        )
                    }
                }
            }

            writers.forEach(Thread::start)
            writers.forEach(Thread::join)

            val snapshot = diagnostics.snapshot()
            snapshot.records.size shouldBe capacity
            snapshot.records.map { it.sequence } shouldBe
                snapshot.records.map { it.sequence }.sorted()
            snapshot.records.map { it.sequence }.distinct().size shouldBe capacity
            snapshot.droppedRecordCount shouldBe
                (writerCount * recordsPerWriter - capacity).toLong()
        }
    })
