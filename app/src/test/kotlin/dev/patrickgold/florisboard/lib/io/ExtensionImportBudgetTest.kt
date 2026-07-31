/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.lib.io

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.concurrent.CancellationException

class ExtensionImportBudgetTest :
    FunSpec({
        test("the default workspace accepts up to 64 selected inputs") {
            val budget = ExtensionImportBudget()

            repeat(64) {
                budget.beginInput().use { input ->
                    input.reserveArchive(expandedBytes = 0, entries = 0)
                    input.commit()
                }
            }

            budget.usage().inputs shouldBe 64
            shouldThrow<ExtensionImportLimitException> {
                budget.beginInput()
            }
        }

        test("committed inputs share one cumulative workspace quota") {
            val budget = testBudget(maxInputs = 2, maxSourceBytes = 10, maxExpandedBytes = 20, maxEntries = 3)

            budget.beginInput().use { input ->
                input.addSourceBytes(4)
                input.addSourceBytes(3)
                input.reserveArchive(expandedBytes = 10, entries = 2)
                input.commit()
            }
            budget.beginInput().use { input ->
                input.remainingSourceBytes() shouldBe 3
                input.addSourceBytes(3)
                input.remainingSourceBytes() shouldBe 0
                input.reserveArchive(expandedBytes = 10, entries = 1)
                input.commit()
            }

            budget.usage().assertUsage(inputs = 2, sourceBytes = 10, expandedBytes = 20, entries = 3)
            shouldThrow<ExtensionImportLimitException> {
                budget.beginInput()
            }
        }

        test("closing an uncommitted input rolls every reservation back") {
            val budget = testBudget(maxInputs = 1, maxSourceBytes = 5, maxExpandedBytes = 7, maxEntries = 2)

            budget.beginInput().use { input ->
                input.addSourceBytes(5)
                input.reserveArchive(expandedBytes = 7, entries = 2)
            }
            budget.usage().assertUsage(inputs = 0, sourceBytes = 0, expandedBytes = 0, entries = 0)

            budget.beginInput().use { input ->
                input.addSourceBytes(5)
                input.reserveArchive(expandedBytes = 7, entries = 2)
                input.commit()
            }
            budget.usage().assertUsage(inputs = 1, sourceBytes = 5, expandedBytes = 7, entries = 2)
        }

        test("a rejected archive attempt still consumes its completed provider-copy work") {
            val budget = testBudget(maxInputs = 3, maxSourceBytes = 5, maxExpandedBytes = 7, maxEntries = 2)

            budget.beginInput().use { input ->
                shouldThrow<ExtensionImportLimitException> {
                    input.commitAttempt()
                }
                input.addSourceBytes(0)
                input.commitAttempt()
            }
            budget.beginInput().use { input ->
                input.addSourceBytes(5)
                input.reserveArchive(expandedBytes = 7, entries = 2)
                input.commitAttempt()
            }

            budget.usage().assertUsage(inputs = 2, sourceBytes = 5, expandedBytes = 7, entries = 2)
            budget.beginInput().use { input ->
                input.remainingSourceBytes() shouldBe 0
            }
        }

        test("failed charges do not mutate totals and expose no caller data") {
            val budget = testBudget(maxInputs = 1, maxSourceBytes = 2, maxExpandedBytes = 3, maxEntries = 1)
            val privateMarker = "private-provider-marker"

            budget.beginInput().use { input ->
                val sourceFailure = shouldThrow<ExtensionImportLimitException> {
                    input.addSourceBytes(3)
                }
                sourceFailure.message shouldBe "Extension selection exceeds workspace limits."
                sourceFailure.toString() shouldNotContain privateMarker
                budget.usage().assertUsage(inputs = 1, sourceBytes = 0, expandedBytes = 0, entries = 0)

                input.addSourceBytes(2)
                shouldThrow<ExtensionImportLimitException> {
                    input.reserveArchive(expandedBytes = 4, entries = 1)
                }
                budget.usage().assertUsage(inputs = 1, sourceBytes = 2, expandedBytes = 0, entries = 0)
            }
            budget.usage().assertUsage(inputs = 0, sourceBytes = 0, expandedBytes = 0, entries = 0)
        }

        test("cancellation escapes unchanged while use rolls reservations back") {
            val budget = testBudget(maxInputs = 1, maxSourceBytes = 5, maxExpandedBytes = 5, maxEntries = 1)
            val cancellation = CancellationException("caller-owned cancellation")

            val thrown = shouldThrow<CancellationException> {
                budget.beginInput().use { input ->
                    input.addSourceBytes(4)
                    throw cancellation
                }
            }

            (thrown === cancellation) shouldBe true
            budget.usage().assertUsage(inputs = 0, sourceBytes = 0, expandedBytes = 0, entries = 0)
        }

        test("an archive reservation is single-use and required before commit") {
            val budget = testBudget(maxInputs = 1, maxSourceBytes = 5, maxExpandedBytes = 5, maxEntries = 1)

            budget.beginInput().use { input ->
                shouldThrow<ExtensionImportLimitException> {
                    input.commit()
                }
                input.reserveArchive(expandedBytes = 1, entries = 1)
                shouldThrow<ExtensionImportLimitException> {
                    input.reserveArchive(expandedBytes = 0, entries = 0)
                }
                input.commit()
                shouldThrow<ExtensionImportLimitException> {
                    input.addSourceBytes(1)
                }
            }
            budget.usage().assertUsage(inputs = 1, sourceBytes = 0, expandedBytes = 1, entries = 1)
        }
    })

private fun testBudget(
    maxInputs: Int,
    maxSourceBytes: Long,
    maxExpandedBytes: Long,
    maxEntries: Int,
): ExtensionImportBudget = ExtensionImportBudget(
    ExtensionImportBudget.Limits(
        maxInputs = maxInputs,
        maxSourceBytes = maxSourceBytes,
        maxExpandedBytes = maxExpandedBytes,
        maxEntries = maxEntries,
    ),
)

private fun ExtensionImportBudget.Usage.assertUsage(
    inputs: Int,
    sourceBytes: Long,
    expandedBytes: Long,
    entries: Int,
) {
    this.inputs shouldBe inputs
    this.sourceBytes shouldBe sourceBytes
    this.expandedBytes shouldBe expandedBytes
    this.entries shouldBe entries
}
