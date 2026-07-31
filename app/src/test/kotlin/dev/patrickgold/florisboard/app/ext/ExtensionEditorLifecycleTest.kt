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

package dev.patrickgold.florisboard.app.ext

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.IOException

class ExtensionEditorLifecycleTest :
    FunSpec({
        test("successful initialization keeps ownership and skips cleanup") {
            runTest {
                var cleanupCalls = 0

                val result = initializeOwnedEditorResource(
                    resource = "workspace",
                    initialize = { "$it-ready" },
                    cleanup = { cleanupCalls++ },
                )

                result shouldBe "workspace-ready"
                cleanupCalls shouldBe 0
            }
        }

        test("failed initialization cleans the resource and preserves the failure") {
            runTest {
                val failure = IOException("synthetic initialization failure")
                var cleanedResource: String? = null

                val thrown = shouldThrow<IOException> {
                    initializeOwnedEditorResource(
                        resource = "workspace",
                        initialize = { throw failure },
                        cleanup = { cleanedResource = it },
                    )
                }

                (thrown === failure) shouldBe true
                cleanedResource shouldBe "workspace"
            }
        }

        test("caller cancellation completes suspending cleanup and remains cancellation") {
            runTest {
                val initializationStarted = CompletableDeferred<Unit>()
                var cleanupCalls = 0
                val operation = async<Unit> {
                    initializeOwnedEditorResource(
                        resource = "workspace",
                        initialize = {
                            initializationStarted.complete(Unit)
                            awaitCancellation()
                        },
                        cleanup = {
                            yield()
                            cleanupCalls++
                        },
                    )
                }

                initializationStarted.await()
                operation.cancel(CancellationException("caller-owned cancellation"))

                shouldThrow<CancellationException> {
                    operation.await()
                }
                operation.isCancelled shouldBe true
                cleanupCalls shouldBe 1
            }
        }
    })
