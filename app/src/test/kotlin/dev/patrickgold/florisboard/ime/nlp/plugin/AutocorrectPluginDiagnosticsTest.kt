/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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

        test("event schema only permits closed enums IDs and epochs") {
            val eventClasses = AutocorrectPluginDiagnosticEvent::class.sealedSubclasses.map { it.java }
            eventClasses.isNotEmpty() shouldBe true
            val permittedFieldTypes = setOf(
                java.lang.Long.TYPE,
                AutocorrectPluginDiagnosticOperation::class.java,
                AutocorrectPluginDiagnosticState::class.java,
                AutocorrectPluginDiagnosticError::class.java,
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
