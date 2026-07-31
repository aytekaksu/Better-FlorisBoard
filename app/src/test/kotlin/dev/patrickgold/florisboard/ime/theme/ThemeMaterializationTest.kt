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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class ThemeMaterializationTest :
    FunSpec({
        test("retired assets live until every lease closes") {
            val directory = Files.createTempDirectory("theme-materialization-test").toFile()
            var disposalCount = 0
            val materialization = ThemeMaterialization(directory) {
                disposalCount++
                it.deleteRecursively()
            }
            val first = materialization.acquire()
            val second = materialization.acquire()

            materialization.retire()
            disposalCount shouldBe 0
            directory.exists() shouldBe true

            first.close()
            first.close()
            disposalCount shouldBe 0

            second.close()
            disposalCount shouldBe 1
            directory.exists() shouldBe false

            materialization.retire()
            disposalCount shouldBe 1
            shouldThrow<IllegalStateException> {
                materialization.acquire()
            }
        }

        test("awaitable retirement completes only after the last lease closes") {
            runTest {
                val directory = Files.createTempDirectory("theme-materialization-await-test").toFile()
                var disposalCount = 0
                val materialization = ThemeMaterialization(directory) {
                    disposalCount++
                }
                val lease = materialization.acquire()

                val retirement = async(start = CoroutineStart.UNDISPATCHED) {
                    materialization.retireAndAwaitRelease()
                }
                retirement.isCompleted shouldBe false
                disposalCount shouldBe 0

                lease.close()
                retirement.await()

                retirement.isCompleted shouldBe true
                disposalCount shouldBe 1
                directory.deleteRecursively()
            }
        }

        test("UI acquisition fails safely once retirement starts") {
            val directory = Files.createTempDirectory("theme-materialization-retired-test").toFile()
            val materialization = ThemeMaterialization(directory) { }
            val managerLease = materialization.acquire()

            materialization.retire()

            materialization.tryAcquire() shouldBe null
            managerLease.close()
            materialization.tryAcquire() shouldBe null
            directory.deleteRecursively()
        }

        test("abandoned UI holder releases its materialization lease") {
            runTest {
                val directory = Files.createTempDirectory("theme-materialization-abandoned-test").toFile()
                val materialization = ThemeMaterialization(directory) { }
                val holder = ThemeMaterializationLeaseHolder(materialization)
                val retirement = async(start = CoroutineStart.UNDISPATCHED) {
                    materialization.retireAndAwaitRelease()
                }

                retirement.isCompleted shouldBe false
                holder.onAbandoned()
                retirement.await()

                retirement.isCompleted shouldBe true
                directory.deleteRecursively()
            }
        }

        test("forgotten UI holder releases its materialization lease once") {
            runTest {
                val directory = Files.createTempDirectory("theme-materialization-forgotten-test").toFile()
                var disposalCount = 0
                val materialization = ThemeMaterialization(directory) {
                    disposalCount++
                }
                val holder = ThemeMaterializationLeaseHolder(materialization)
                val retirement = async(start = CoroutineStart.UNDISPATCHED) {
                    materialization.retireAndAwaitRelease()
                }

                holder.onForgotten()
                holder.onForgotten()
                retirement.await()

                disposalCount shouldBe 1
                directory.deleteRecursively()
            }
        }

        test("load failure summary excludes exception content") {
            val failure = ThemeManager.LoadFailure(IllegalStateException("private theme path"))

            failure.toString().contains("private theme path") shouldBe false
            failure.toString() shouldBe "LoadFailure(type=IllegalStateException)"
        }
    })
