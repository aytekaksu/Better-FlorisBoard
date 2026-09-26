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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.ext.SafeRelativePath
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

const val FONTS = "fonts"
const val IMAGES = "images"

internal enum class EditorAssetKind(val directory: String) {
    FONT(FONTS),
    IMAGE(IMAGES),
}

internal data class EditorAssetIdentity(
    val fileKey: Any?,
    val size: Long,
    val modified: FileTime,
    val created: FileTime,
) {
    companion object {
        fun of(attributes: BasicFileAttributes) = EditorAssetIdentity(
            fileKey = attributes.fileKey(),
            size = attributes.size(),
            modified = attributes.lastModifiedTime(),
            created = attributes.creationTime(),
        )
    }
}

internal data class EditorAssetFile(
    val kind: EditorAssetKind,
    val name: String,
    val identity: EditorAssetIdentity,
) {
    val relativePath get() = "/${kind.directory}/$name"
}

internal data class EditorAssetFiles(
    val fonts: List<EditorAssetFile> = emptyList(),
    val images: List<EditorAssetFile> = emptyList(),
)

internal enum class EditorAssetMutationResult {
    SUCCESS,
    INVALID_NAME,
    ALREADY_EXISTS,
    FAILURE,
}

/** Filesystem work for the editor's file list and property picker; call on Dispatchers.IO. */
internal class EditorAssetStore(private val workspaceRoot: Path) {
    private val extensionRoot = workspaceRoot.resolve("ext")

    fun list(): EditorAssetFiles {
        if (!isOpen()) return EditorAssetFiles()
        val files = EditorAssetFiles(
            fonts = list(EditorAssetKind.FONT),
            images = list(EditorAssetKind.IMAGE),
        )
        return files.takeIf { isOpen() } ?: EditorAssetFiles()
    }

    fun delete(file: EditorAssetFile): EditorAssetMutationResult {
        return try {
            val directory = safeDirectory(file.kind) ?: return EditorAssetMutationResult.FAILURE
            val source = safeFile(directory, file.name) ?: return EditorAssetMutationResult.FAILURE
            if (!isUnchanged(source, file)) {
                return EditorAssetMutationResult.FAILURE
            }
            Files.delete(source)
            EditorAssetMutationResult.SUCCESS
        } catch (_: Exception) {
            EditorAssetMutationResult.FAILURE
        }
    }

    fun rename(file: EditorAssetFile, newName: String): EditorAssetMutationResult {
        val targetName = safeName(newName) ?: return EditorAssetMutationResult.INVALID_NAME
        return try {
            val directory = safeDirectory(file.kind) ?: return EditorAssetMutationResult.FAILURE
            val source = safeFile(directory, file.name) ?: return EditorAssetMutationResult.FAILURE
            if (!isUnchanged(source, file)) {
                return EditorAssetMutationResult.FAILURE
            }
            val target = directory.resolve(targetName.value)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return EditorAssetMutationResult.ALREADY_EXISTS
            }
            if (targetName.resolveWithin(directory).getOrNull() != target) {
                return EditorAssetMutationResult.INVALID_NAME
            }
            Files.move(source, target)
            EditorAssetMutationResult.SUCCESS
        } catch (_: FileAlreadyExistsException) {
            EditorAssetMutationResult.ALREADY_EXISTS
        } catch (_: Exception) {
            EditorAssetMutationResult.FAILURE
        }
    }

    private fun list(kind: EditorAssetKind): List<EditorAssetFile> {
        return try {
            val directory = safeDirectory(kind) ?: return emptyList()
            Files.newDirectoryStream(directory).use { entries ->
                entries.mapNotNull { entry ->
                    try {
                        val attributes = Files.readAttributes(
                            entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
                        )
                        if (attributes.isRegularFile) {
                            EditorAssetFile(kind, entry.fileName.toString(), EditorAssetIdentity.of(attributes))
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isOpen() =
        Files.isDirectory(workspaceRoot, LinkOption.NOFOLLOW_LINKS) &&
            Files.isDirectory(extensionRoot, LinkOption.NOFOLLOW_LINKS)

    private fun safeDirectory(kind: EditorAssetKind): Path? {
        val directory = extensionRoot.resolve(kind.directory)
        if (!isOpen() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return null
        return directory.toRealPath()
    }

    private fun safeFile(directory: Path, name: String): Path? =
        safeName(name)?.resolveWithin(directory)?.getOrNull()

    private fun isUnchanged(path: Path, file: EditorAssetFile): Boolean {
        val attributes = Files.readAttributes(
            path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        return attributes.isRegularFile && EditorAssetIdentity.of(attributes) == file.identity
    }

    private fun safeName(name: String): SafeRelativePath? =
        SafeRelativePath.parse(name).getOrNull()?.takeIf { '/' !in it.value }
}

@Composable
internal fun rememberEditorAssetFiles(workspace: CacheManager.ThemeEditorWorkspace): EditorAssetFiles? {
    val store = remember(workspace) { EditorAssetStore(workspace.dir.toPath()) }
    var files by remember(workspace) { mutableStateOf<EditorAssetFiles?>(null) }
    val requestedVersion = workspace.version
    val requestedAction = workspace.currentAction
    LaunchedEffect(store, requestedVersion, requestedAction) {
        files = null
        if (requestedAction == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            workspace.withOpenFileOperation { store.list() } ?: EditorAssetFiles()
        }
        currentCoroutineContext().ensureActive()
        if (workspace.version == requestedVersion && workspace.currentAction === requestedAction) {
            files = loaded
        }
    }
    return files
}
