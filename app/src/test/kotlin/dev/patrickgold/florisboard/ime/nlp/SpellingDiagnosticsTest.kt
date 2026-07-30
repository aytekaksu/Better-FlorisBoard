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

package dev.patrickgold.florisboard.ime.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier

class SpellingDiagnosticsTest :
    FunSpec({
        test("history is bounded ordered and detached") {
            val diagnostics = SpellingDiagnostics(capacity = 2)
            diagnostics.record(SpellingDiagnosticState.VALID_WORD, suggestionCount = 0)
            diagnostics.record(SpellingDiagnosticState.TYPO, suggestionCount = 2)

            val snapshot = diagnostics.snapshot()
            diagnostics.record(SpellingDiagnosticState.GRAMMAR_ERROR, suggestionCount = 1)

            snapshot.records.map { it.sequence } shouldContainExactly listOf(1L, 2L)
            diagnostics.snapshot().records.map { it.sequence } shouldContainExactly listOf(2L, 3L)
            diagnostics.snapshot().droppedRecordCount shouldBe 1L
        }

        test("clear keeps sequence monotonic without retaining history") {
            val diagnostics = SpellingDiagnostics()
            diagnostics.record(SpellingDiagnosticState.UNSPECIFIED, suggestionCount = -1)
            diagnostics.clear()
            diagnostics.record(SpellingDiagnosticState.TYPO, suggestionCount = 3)

            val snapshot = diagnostics.snapshot()
            snapshot.records shouldContainExactly listOf(
                SpellingDiagnosticRecord(
                    sequence = 2L,
                    state = SpellingDiagnosticState.TYPO,
                    suggestionCount = 3,
                ),
            )
            snapshot.droppedRecordCount shouldBe 0L
        }

        test("record schema cannot retain text or Android spelling results") {
            val permittedFieldTypes = setOf(
                java.lang.Long.TYPE,
                java.lang.Integer.TYPE,
                SpellingDiagnosticState::class.java,
            )
            val unexpectedFields = SpellingDiagnosticRecord::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .filterNot { it.type in permittedFieldTypes }
                .map { "${it.name}: ${it.type.name}" }

            unexpectedFields shouldBe emptyList()
        }
    })
