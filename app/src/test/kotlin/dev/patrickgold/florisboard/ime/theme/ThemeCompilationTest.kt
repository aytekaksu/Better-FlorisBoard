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

package dev.patrickgold.florisboard.ime.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class ThemeCompilationTest :
    FunSpec({
        test("old theme stays published until the new compilation finishes") {
            runTest {
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                var published = "old"
                val compilation = async(start = CoroutineStart.UNDISPATCHED) {
                    compileCurrentTheme(
                        materialization = null,
                        fallbackDir = null,
                        isCurrent = { true },
                        compile = {
                            started.complete(Unit)
                            finish.await()
                            "new"
                        },
                        publish = {
                            published = it
                            true
                        },
                    )
                }

                started.await()
                published shouldBe "old"
                finish.complete(Unit)
                compilation.await() shouldBe true
                published shouldBe "new"
            }
        }

        test("an obsolete preview cannot replace the newer result") {
            runTest {
                var generation = 1
                var published = "old"
                val started = CompletableDeferred<Unit>()
                val finishOld = CompletableDeferred<Unit>()
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    compileCurrentTheme(
                        materialization = null,
                        fallbackDir = null,
                        isCurrent = { generation == 1 },
                        compile = {
                            started.complete(Unit)
                            finishOld.await()
                            "stale"
                        },
                        publish = {
                            published = it
                            true
                        },
                    )
                }

                started.await()
                generation = 2
                compileCurrentTheme(
                    materialization = null,
                    fallbackDir = null,
                    isCurrent = { generation == 2 },
                    compile = { "current" },
                    publish = {
                        published = it
                        true
                    },
                ) shouldBe true
                finishOld.complete(Unit)
                old.await() shouldBe false
                published shouldBe "current"
            }
        }

        test("a compile lease protects assets until the published theme takes its own lease") {
            runTest {
                val directory = Files.createTempDirectory("theme-compile-lease").toFile()
                val materialization = ThemeMaterialization(directory) { it.deleteRecursively() }
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                var publishedLease: ThemeMaterialization.Lease? = null
                val compilation = async(start = CoroutineStart.UNDISPATCHED) {
                    compileCurrentTheme(
                        materialization = materialization,
                        fallbackDir = null,
                        isCurrent = { true },
                        compile = { leasedDir ->
                            leasedDir shouldBe directory
                            started.complete(Unit)
                            finish.await()
                            "ready"
                        },
                        publish = {
                            directory.exists() shouldBe true
                            publishedLease = materialization.tryAcquire()
                            publishedLease != null
                        },
                    )
                }

                started.await()
                finish.complete(Unit)
                compilation.await() shouldBe true
                materialization.retire()
                directory.exists() shouldBe true
                publishedLease?.close()
                directory.exists() shouldBe false
            }
        }

        test("retirement during compilation keeps assets until the worker stops") {
            runTest {
                val directory = Files.createTempDirectory("theme-compile-retired").toFile()
                val materialization = ThemeMaterialization(directory) { it.deleteRecursively() }
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val compilation = async(start = CoroutineStart.UNDISPATCHED) {
                    compileCurrentTheme(
                        materialization = materialization,
                        fallbackDir = null,
                        isCurrent = { true },
                        compile = {
                            started.complete(Unit)
                            finish.await()
                        },
                        publish = {
                            materialization.tryAcquire()?.let { lease ->
                                lease.close()
                                true
                            } ?: false
                        },
                    )
                }

                started.await()
                materialization.retire()
                directory.exists() shouldBe true
                finish.complete(Unit)
                compilation.await() shouldBe false
                directory.exists() shouldBe false
            }
        }

        test("retired assets do not start compilation or publish") {
            runTest {
                val directory = Files.createTempDirectory("theme-compile-unavailable").toFile()
                val materialization = ThemeMaterialization(directory) { it.deleteRecursively() }
                materialization.retire()
                var compiled = false
                var published = false

                compileCurrentTheme(
                    materialization = materialization,
                    fallbackDir = directory,
                    isCurrent = { true },
                    compile = {
                        compiled = true
                        "unavailable"
                    },
                    publish = {
                        published = true
                        true
                    },
                ) shouldBe false
                compiled shouldBe false
                published shouldBe false
                directory.exists() shouldBe false
            }
        }

        test("cancellation releases the compile lease without publishing") {
            runTest {
                val directory = Files.createTempDirectory("theme-compile-cancel").toFile()
                val materialization = ThemeMaterialization(directory) { it.deleteRecursively() }
                val started = CompletableDeferred<Unit>()
                val neverFinish = CompletableDeferred<Unit>()
                var published = false
                val compilation = async(start = CoroutineStart.UNDISPATCHED) {
                    compileCurrentTheme(
                        materialization = materialization,
                        fallbackDir = null,
                        isCurrent = { true },
                        compile = {
                            started.complete(Unit)
                            neverFinish.await()
                        },
                        publish = {
                            published = true
                            true
                        },
                    )
                }

                started.await()
                materialization.retire()
                compilation.cancelAndJoin()
                published shouldBe false
                directory.exists() shouldBe false
            }
        }

        test("a failed compilation releases its lease without publishing") {
            runTest {
                val directory = Files.createTempDirectory("theme-compile-failure").toFile()
                val materialization = ThemeMaterialization(directory) { it.deleteRecursively() }
                var published = false

                var failed = false
                try {
                    compileCurrentTheme<String>(
                        materialization = materialization,
                        fallbackDir = null,
                        isCurrent = { true },
                        compile = { throw IllegalStateException("invalid theme") },
                        publish = {
                            published = true
                            true
                        },
                    )
                } catch (_: IllegalStateException) {
                    failed = true
                }
                materialization.retire()

                failed shouldBe true
                published shouldBe false
                directory.exists() shouldBe false
            }
        }

        test("a failed publication releases the compile lease") {
            runTest {
                val directory = Files.createTempDirectory("theme-compile-publish-failure").toFile()
                val materialization = ThemeMaterialization(directory) { it.deleteRecursively() }
                var failed = false
                try {
                    compileCurrentTheme(
                        materialization = materialization,
                        fallbackDir = null,
                        isCurrent = { true },
                        compile = { "compiled" },
                        publish = { throw IllegalStateException("publish failed") },
                    )
                } catch (_: IllegalStateException) {
                    failed = true
                }
                materialization.retire()

                failed shouldBe true
                directory.exists() shouldBe false
            }
        }
    })
