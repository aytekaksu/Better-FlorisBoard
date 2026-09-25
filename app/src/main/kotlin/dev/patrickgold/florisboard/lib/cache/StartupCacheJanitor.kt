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

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal data class StartupCacheCleanupReport(
    val removed: Int = 0,
    val failures: Int = 0,
)

/** One process-owned job, started after unlock and awaited before cache consumers run. */
internal class StartupCacheCleanup(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    sweep: suspend () -> StartupCacheCleanupReport,
) {
    private val job: Deferred<StartupCacheCleanupReport> =
        scope.async(dispatcher, start = CoroutineStart.LAZY) {
            try {
                sweep()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A cache access failure must not disable the keyboard or app.
                StartupCacheCleanupReport(failures = 1)
            }
        }

    fun start() {
        job.start()
    }

    suspend fun await(): StartupCacheCleanupReport = job.await()
}

/** Removes only abandoned, app-owned workspaces before their new owners start. */
internal class StartupCacheJanitor(private val cacheRoot: Path) {
    private var removed = 0
    private var failures = 0

    fun sweep(): StartupCacheCleanupReport {
        removed = 0
        failures = 0
        if (!Files.isDirectory(cacheRoot, NOFOLLOW_LINKS)) {
            return StartupCacheCleanupReport()
        }
        for (owner in WORKSPACE_ROOTS) {
            visitChildren(cacheRoot.resolve(owner)) { child ->
                if (isCanonicalUuid(child.fileName.toString()) &&
                    Files.isDirectory(child, NOFOLLOW_LINKS)
                ) {
                    removeDirectory(child)
                }
            }
        }
        for (owner in STAGING_ROOTS) {
            visitChildren(cacheRoot.resolve(owner)) { child ->
                if (STAGED_FILE.matches(child.fileName.toString()) &&
                    Files.isRegularFile(child, NOFOLLOW_LINKS)
                ) {
                    removeFile(child)
                }
            }
        }
        visitChildren(cacheRoot) { child ->
            val name = child.fileName.toString()
            val isEditorStage = name.startsWith(EDITOR_PREFIX) &&
                isCanonicalUuid(name.removePrefix(EDITOR_PREFIX))
            if (Files.isDirectory(child, NOFOLLOW_LINKS) &&
                (isEditorStage || EXPORT_WORKSPACE.matches(name))
            ) {
                removeDirectory(child)
            }
        }
        return StartupCacheCleanupReport(removed, failures)
    }

    private fun visitChildren(root: Path, visit: (Path) -> Unit) {
        try {
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return
            Files.newDirectoryStream(root).use { children ->
                children.forEach(visit)
            }
        } catch (_: Exception) {
            failures++
        }
    }

    private fun removeFile(path: Path) {
        try {
            if (Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.deleteIfExists(path)) {
                removed++
            }
        } catch (_: Exception) {
            failures++
        }
    }

    private fun removeDirectory(path: Path) {
        try {
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) return
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    // Never follow or remove a link, even one nested in a known workspace.
                    if (!attributes.isSymbolicLink && !Files.isSymbolicLink(file)) {
                        Files.deleteIfExists(file)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    if (!Files.isSymbolicLink(directory)) Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
            removed++
        } catch (_: Exception) {
            failures++
        }
    }

    private fun isCanonicalUuid(name: String): Boolean =
        runCatching { UUID.fromString(name).toString() == name }.getOrDefault(false)

    private companion object {
        val WORKSPACE_ROOTS = listOf("importer", "exporter", "editor", "backup-and-restore")
        val STAGING_ROOTS = listOf(
            "clipboard-provider-imports",
            "extension-provider-imports",
            "clipboard-share-previews",
        )
        const val EDITOR_PREFIX = "extension-editor-"
        val EXPORT_WORKSPACE = Regex("extension-export-[0-9]{1,20}")
        // Mirror the importer's prefix/suffix rule for NIO-generated, nonempty names.
        val STAGED_FILE = Regex("\\.clipboard-provider-.+\\.partial")
    }
}
