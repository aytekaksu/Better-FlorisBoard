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

package dev.patrickgold.florisboard.lib.ext

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class ExtensionExportWorkspaceTest : FunSpec({
    test("export workspace lives on IO and is removed after a successful write") {
        val root = Files.createTempDirectory("extension-export-test-")
        try {
            runTest {
                val callerThread = Thread.currentThread()
                lateinit var workspacePath: Path

                val result = withExtensionExportWorkspace({
                    (Thread.currentThread() !== callerThread) shouldBe true
                    root
                }) { workspace ->
                    (Thread.currentThread() !== callerThread) shouldBe true
                    workspacePath = workspace.toPath()
                    workspace.resolve("snapshot.flex").writeText("synthetic archive")
                    Files.exists(workspacePath) shouldBe true
                    "written"
                }

                result shouldBe "written"
                Files.notExists(workspacePath) shouldBe true
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("writer failure is preserved and still removes its workspace") {
        val root = Files.createTempDirectory("extension-export-test-")
        try {
            runTest {
                lateinit var workspacePath: Path
                val failure = IOException("synthetic writer failure")

                val thrown = shouldThrow<IOException> {
                    withExtensionExportWorkspace({ root }) { workspace ->
                        workspacePath = workspace.toPath()
                        workspace.resolve("snapshot.flex").writeText("synthetic archive")
                        throw failure
                    }
                }

                thrown.message shouldBe failure.message
                Files.notExists(workspacePath) shouldBe true
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("cancellation after staging removes its workspace") {
        val root = Files.createTempDirectory("extension-export-test-")
        try {
            runTest {
                val staged = CompletableDeferred<Path>()
                val hold = CompletableDeferred<Unit>()
                val export = async {
                    withExtensionExportWorkspace({ root }) { workspace ->
                        workspace.resolve("snapshot.flex").writeText("synthetic archive")
                        staged.complete(workspace.toPath())
                        hold.await()
                    }
                }

                val workspacePath = staged.await()
                export.cancel()
                export.join()

                export.isCancelled shouldBe true
                Files.notExists(workspacePath) shouldBe true
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
