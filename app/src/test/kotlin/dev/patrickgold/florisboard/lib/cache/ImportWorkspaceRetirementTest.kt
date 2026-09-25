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

package dev.patrickgold.florisboard.lib.cache

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ImportWorkspaceRetirementTest :
    FunSpec({
        test("duplicate retirement waits for the active import") {
            runTest {
                var cleanupCalls = 0
                val retirement = ImportWorkspaceRetirement(
                    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                ) { cleanupCalls++ }
                val lease = retirement.retainForImport()

                retirement.request()
                retirement.request()
                shouldThrow<IllegalStateException> { retirement.retainForImport() }
                runCurrent()
                cleanupCalls shouldBe 0

                lease.close()
                lease.close()
                retirement.retire()
                cleanupCalls shouldBe 1
            }
        }

        test("caller cancellation still awaits retirement") {
            runTest {
                var cleanupCalls = 0
                val cleanupScheduler = TestCoroutineScheduler()
                val retirement = ImportWorkspaceRetirement(
                    CoroutineScope(SupervisorJob() + StandardTestDispatcher(cleanupScheduler)),
                ) { cleanupCalls++ }
                val lease = retirement.retainForImport()
                val caller = async { retirement.retire() }

                runCurrent()
                caller.cancel(CancellationException("synthetic cancellation"))
                lease.close()
                runCurrent()
                caller.isCompleted shouldBe false

                cleanupScheduler.runCurrent()
                shouldThrow<CancellationException> { caller.await() }
                cleanupCalls shouldBe 1
            }
        }

        test("a UI-only request retries a transient deletion failure") {
            runTest {
                var attempts = 0
                var deleted = false
                val terminalFailures = mutableListOf<Throwable>()
                fun requestAndForget() {
                    ImportWorkspaceRetirement(backgroundScope, onTerminalFailure = { terminalFailures += it }) {
                        attempts++
                        if (attempts == 1) throw IOException("synthetic transient failure")
                        deleted = true
                    }.request()
                }

                requestAndForget()
                runCurrent()
                attempts shouldBe 1
                deleted shouldBe false

                advanceTimeBy(10_000)
                runCurrent()
                attempts shouldBe 2
                deleted shouldBe true
                terminalFailures shouldBe emptyList()
            }
        }

        test("a UI-only terminal failure stays owned until janitor recovery") {
            runTest {
                val failure = IOException("synthetic persistent failure")
                var attempts = 0
                var deleted = false
                val reported = mutableListOf<Throwable>()
                val directory = File("synthetic-import-workspace")
                val janitor = ImportWorkspaceJanitor(backgroundScope, intervalMs = 1_000L) {
                    it shouldBe directory
                    attempts++
                    deleted = true
                    true
                }
                fun requestAndForget() {
                    ImportWorkspaceRetirement(backgroundScope, onTerminalFailure = {
                        reported += it
                        janitor.enqueue(directory)
                    }) {
                        attempts++
                        throw failure
                    }.request()
                }

                requestAndForget()
                runCurrent()
                attempts shouldBe 1
                advanceTimeBy(10_000)
                runCurrent()

                attempts shouldBe 3
                deleted shouldBe true
                reported shouldBe listOf(failure)
            }
        }

        test("terminal failure reaches the waiter and permits explicit retry") {
            runTest {
                val failure = IOException("synthetic cleanup failure")
                var attempts = 0
                val reported = mutableListOf<Throwable>()
                val retirement = ImportWorkspaceRetirement(
                    backgroundScope,
                    onTerminalFailure = { reported += it },
                ) {
                    attempts++
                    if (attempts <= 2) throw failure
                }

                val thrown = shouldThrow<IOException> { retirement.retire() }
                thrown.message shouldBe failure.message
                attempts shouldBe 2
                reported shouldBe listOf(failure)
                retirement.retire()
                attempts shouldBe 3
                reported shouldBe listOf(failure)
            }
        }
    })
