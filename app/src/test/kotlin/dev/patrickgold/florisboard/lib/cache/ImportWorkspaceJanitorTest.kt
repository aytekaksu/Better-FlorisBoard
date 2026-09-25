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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ImportWorkspaceJanitorTest : FunSpec({
    test("an abandoned failed sweep is retried by the same worker") {
        runTest {
            var attempts = 0
            var removed = false
            val directory = File("synthetic-import-workspace")
            fun startAndForget() {
                ImportWorkspaceJanitor(backgroundScope, intervalMs = 1_000L) {
                    it shouldBe directory
                    attempts++
                    if (attempts == 1) throw IOException("synthetic sweep failure")
                    removed = true
                    true
                }.enqueue(directory)
            }

            startAndForget()
            runCurrent()
            attempts shouldBe 1
            removed shouldBe false

            advanceTimeBy(1_000L)
            runCurrent()
            attempts shouldBe 2
            removed shouldBe true
        }
    }

    test("a permanent failure stays on the bounded scan interval") {
        runTest {
            var attempts = 0
            val janitor = ImportWorkspaceJanitor(backgroundScope, intervalMs = 1_000L) {
                attempts++
                throw IOException("synthetic persistent failure")
            }
            janitor.enqueue(File("synthetic-import-workspace"))

            runCurrent()
            attempts shouldBe 1
            advanceTimeBy(3_000L)
            runCurrent()
            attempts shouldBe 4
        }
    }

    test("a newly failed path wakes the worker before an older retry is due") {
        runTest {
            val first = File("synthetic-import-workspace-first")
            val second = File("synthetic-import-workspace-second")
            val scanned = mutableListOf<File>()
            val janitor = ImportWorkspaceJanitor(backgroundScope, intervalMs = 60_000L) {
                scanned += it
                it == second
            }

            janitor.enqueue(first)
            runCurrent()
            scanned shouldBe listOf(first)

            janitor.enqueue(second)
            runCurrent()
            (second in scanned) shouldBe true
            testScheduler.currentTime shouldBe 0L
        }
    }

    test("a same-path failure queued during cleanup is not lost") {
        runTest {
            val directory = File("synthetic-import-workspace-reused")
            var attempts = 0
            lateinit var janitor: ImportWorkspaceJanitor
            janitor = ImportWorkspaceJanitor(backgroundScope, intervalMs = 60_000L) {
                attempts++
                if (attempts == 1) janitor.enqueue(directory)
                true
            }

            janitor.enqueue(directory)
            runCurrent()
            attempts shouldBe 2
            testScheduler.currentTime shouldBe 0L
        }
    }

    test("a full batch rotates so later failures still get a turn") {
        runTest {
            val scanned = mutableListOf<File>()
            val directories = (1..17).map { File("synthetic-import-workspace-$it") }
            val janitor = ImportWorkspaceJanitor(backgroundScope, intervalMs = 1_000L) {
                scanned += it
                false
            }
            directories.forEach(janitor::enqueue)

            runCurrent()
            scanned shouldBe directories.take(16)
            advanceTimeBy(1_000L)
            runCurrent()
            (directories.last() in scanned) shouldBe true
        }
    }
})
