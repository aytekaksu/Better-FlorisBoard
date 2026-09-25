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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class StartupCacheJanitorTest : FunSpec({
    test("startup removes only known abandoned workspaces and provider stages") {
        val root = Files.createTempDirectory("startup-cache-")
        try {
            val workspaceId = UUID.randomUUID().toString()
            val workspaces = listOf("importer", "exporter", "editor", "backup-and-restore")
                .map { owner -> root.resolve(owner).resolve(workspaceId) }
            workspaces.forEach { workspace ->
                Files.createDirectories(workspace.resolve("nested"))
                Files.writeString(workspace.resolve("nested/data"), "stale")
            }
            val stages = listOf(
                "clipboard-provider-imports",
                "extension-provider-imports",
                "clipboard-share-previews",
            ).map { owner ->
                val directory = Files.createDirectories(root.resolve(owner))
                Files.createTempFile(directory, ".clipboard-provider-", ".partial")
            }
            val editorStage = Files.createDirectories(root.resolve("extension-editor-$workspaceId"))
            Files.writeString(editorStage.resolve("stage"), "stale")
            val exportStage = Files.createTempDirectory(root, "extension-export-")
            Files.writeString(exportStage.resolve("archive.flex"), "stale")

            val unknownWorkspace = Files.createDirectories(root.resolve("importer/notes"))
            val unknownStage = Files.writeString(root.resolve("clipboard-provider-imports/notes.partial"), "keep")
            val unknownExport = Files.createDirectories(root.resolve("extension-export-not-a-temp-name"))
            val themeAssets = Files.createDirectories(root.resolve("theme-materializations/live"))
            val runtimeAssets = Files.createDirectories(root.resolve("extension-runtime/live"))
            val unrelated = Files.createDirectories(root.resolve("androidx/owned"))

            val report = StartupCacheJanitor(root).sweep()

            report shouldBe StartupCacheCleanupReport(removed = 9)
            (workspaces + stages + editorStage + exportStage).forEach {
                Files.exists(it) shouldBe false
            }
            listOf(unknownWorkspace, unknownStage, unknownExport, themeAssets, runtimeAssets, unrelated)
                .forEach { Files.exists(it) shouldBe true }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("links are never followed or removed, including inside a known workspace") {
        val root = Files.createTempDirectory("startup-cache-links-")
        val outside = Files.createTempDirectory("startup-cache-outside-")
        val rootLink = root.resolve("extension-export-123456")
        val ownerLink = root.resolve("editor")
        val stagedFileLink = root.resolve("clipboard-provider-imports/.clipboard-provider-123.partial")
        val linkedCacheRoot = root.resolve("linked-cache")
        var insideLink: Path? = null
        try {
            val sentinel = Files.writeString(outside.resolve("sentinel"), "keep")
            val foreignWorkspace = Files.createDirectories(
                outside.resolve("importer").resolve(UUID.randomUUID().toString()),
            )
            val workspace = Files.createDirectories(
                root.resolve("importer").resolve(UUID.randomUUID().toString()),
            )
            Files.writeString(workspace.resolve("owned"), "stale")
            val independentWorkspace = Files.createDirectories(
                root.resolve("backup-and-restore").resolve(UUID.randomUUID().toString()),
            )
            Files.writeString(independentWorkspace.resolve("owned"), "stale")
            val link = workspace.resolve("outside-link")
            insideLink = link
            Files.createDirectories(stagedFileLink.parent)
            val linksSupported = runCatching {
                Files.createSymbolicLink(link, outside)
                Files.createSymbolicLink(rootLink, outside)
                Files.createSymbolicLink(ownerLink, outside)
                Files.createSymbolicLink(stagedFileLink, sentinel)
                Files.createSymbolicLink(linkedCacheRoot, outside)
            }.isSuccess
            if (linksSupported) {
                val report = StartupCacheJanitor(root).sweep()
                report.failures shouldBe 1 // The retained link prevents removing its parent.
                StartupCacheJanitor(linkedCacheRoot).sweep() shouldBe StartupCacheCleanupReport()
                Files.isSymbolicLink(link) shouldBe true
                Files.isSymbolicLink(rootLink) shouldBe true
                Files.isSymbolicLink(ownerLink) shouldBe true
                Files.isSymbolicLink(stagedFileLink) shouldBe true
                Files.exists(sentinel) shouldBe true
                Files.exists(foreignWorkspace) shouldBe true
                Files.exists(workspace.resolve("owned")) shouldBe false
                Files.exists(independentWorkspace) shouldBe false
            }
        } finally {
            insideLink?.let { Files.deleteIfExists(it) }
            Files.deleteIfExists(rootLink)
            Files.deleteIfExists(ownerLink)
            Files.deleteIfExists(stagedFileLink)
            Files.deleteIfExists(linkedCacheRoot)
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    test("locked startup defers cleanup until unlock and cache users await one sweep") {
        runTest {
            val release = CompletableDeferred<Unit>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            var sweeps = 0
            val cleanup = StartupCacheCleanup(
                CoroutineScope(SupervisorJob() + dispatcher),
                dispatcher,
            ) {
                sweeps++
                release.await()
                StartupCacheCleanupReport(removed = 1)
            }

            runCurrent() // Direct boot does not touch credential-protected cache.
            sweeps shouldBe 0

            cleanup.start() // FlorisApplication.init after unlock.
            runCurrent()
            sweeps shouldBe 1

            val unlockedInitialization = async { cleanup.await() }
            runCurrent()
            unlockedInitialization.isCompleted shouldBe false
            release.complete(Unit)
            runCurrent()
            unlockedInitialization.await() shouldBe StartupCacheCleanupReport(removed = 1)

            cleanup.start()
            cleanup.await() shouldBe StartupCacheCleanupReport(removed = 1)
            sweeps shouldBe 1
        }
    }

    test("cleanup failure reports retained paths without preventing initialization") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val cleanup = StartupCacheCleanup(
                CoroutineScope(SupervisorJob() + dispatcher),
                dispatcher,
            ) { throw IOException("synthetic cache failure") }

            cleanup.start()
            runCurrent()
            cleanup.await() shouldBe StartupCacheCleanupReport(failures = 1)
        }
    }
})
