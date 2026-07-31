/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.patrickgold.florisboard.app.ext.EditorAction
import dev.patrickgold.florisboard.app.settings.advanced.BackupArchive
import dev.patrickgold.florisboard.app.settings.advanced.BackupArchiveSession
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.clipboard.provider.DisposableExternalContentImporter
import dev.patrickgold.florisboard.ime.clipboard.provider.StagedExternalContent
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionEditor
import dev.patrickgold.florisboard.ime.theme.ThemeMaterialization
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionDefaults
import dev.patrickgold.florisboard.lib.ext.ExtensionEditor
import dev.patrickgold.florisboard.lib.ext.InstalledExtensionArchiveFingerprint
import dev.patrickgold.florisboard.lib.ext.decodeExtensionManifest
import dev.patrickgold.florisboard.lib.ext.validateForImport
import dev.patrickgold.florisboard.lib.io.BoundedExtensionArchive
import dev.patrickgold.florisboard.lib.io.ExtensionImportBudget
import dev.patrickgold.florisboard.lib.io.ExtensionImportLimitException
import dev.patrickgold.florisboard.lib.io.FileRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.florisboard.lib.android.conservativeUsableSpace
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.FsFile
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal inline fun <T> StagedExternalContent?.useForExtensionImport(
    block: (StagedExternalContent) -> T,
): T = use { staged ->
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    block(staged ?: throw ExtensionImportException())
}

class CacheManager(context: Context) {
    companion object {
        private const val InputDirName = "input"
        private const val OutputDirName = "output"

        private const val ImporterDirName = "importer"
        private const val ExporterDirName = "exporter"
        private const val EditorDirName = "editor"
        private const val BackupAndRestoreDirName = "backup-and-restore"

        private const val DefaultImportDisplayLabel = "Extension file"
        private const val ExtensionSourceStagingDirName = "extension-provider-imports"
        private const val ExtensionSourceTimeoutMs = 30_000L
        private const val ImportStorageHeadroom = 128L * 1_024L * 1_024L
        private const val MaxImportFiles = 64
        private const val MaxManifestSize = 1L * 1_024L * 1_024L
        internal const val MaxImportDisplayLabelLength = 128
        const val MaxImportSourceSize = 64L * 1024L * 1024L

        internal fun sanitizeImportDisplayLabel(rawLabel: String?): String {
            if (rawLabel == null) return DefaultImportDisplayLabel
            return buildString(minOf(rawLabel.length, MaxImportDisplayLabelLength)) {
                var pendingSpace = false
                for (char in rawLabel) {
                    val isUnsafe = char == '/' ||
                        char == '\\' ||
                        char.isISOControl() ||
                        Character.getType(char) == Character.FORMAT.toInt()
                    if (isUnsafe || char.isWhitespace()) {
                        pendingSpace = isNotEmpty()
                        continue
                    }
                    if (pendingSpace && length < MaxImportDisplayLabelLength) {
                        append(' ')
                    }
                    if (length >= MaxImportDisplayLabelLength) break
                    append(char)
                    pendingSpace = false
                }
            }.ifBlank { DefaultImportDisplayLabel }
        }
    }

    private val appContext by context.appContext()
    private val extensionSourceImporter = DisposableExternalContentImporter(
        context = context,
        timeoutMs = ExtensionSourceTimeoutMs,
        stageCapacity = { MaxImportSourceSize },
        stagingDirectory = ExtensionSourceStagingDirName,
    )
    private val workspaceCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val importer = WorkspacesContainer(ImporterDirName) { ImporterWorkspace(it) }
    val exporter = WorkspacesContainer(ExporterDirName) { ExporterWorkspace(it) }
    val themeExtEditor = WorkspacesContainer(EditorDirName) { ExtEditorWorkspace<ThemeExtensionEditor>(it) }
    val backupAndRestore = WorkspacesContainer(BackupAndRestoreDirName) { BackupAndRestoreWorkspace(it) }

    private fun createWorkspaceDirectory(directory: FsDir) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create private workspace.")
        }
    }

    private fun requireImportStorage(directory: FsDir, maximumWriteBytes: Long) {
        val requiredBytes = maximumWriteBytes + ImportStorageHeadroom
        if (maximumWriteBytes < 0L ||
            requiredBytes < maximumWriteBytes ||
            directory.conservativeUsableSpace() < requiredBytes
        ) {
            throw ExtensionImportException()
        }
    }

    private fun removeRejectedImport(file: FsFile, extractedDir: FsDir) {
        val extractedRemoved = !extractedDir.exists() || extractedDir.deleteRecursively()
        val sourceRemoved = !file.exists() || file.delete()
        if (!extractedRemoved || !sourceRemoved) {
            throw IOException("Unable to clean private import data.")
        }
    }

    private fun ensureImportThreadActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    suspend fun readFromUriIntoCache(uri: Uri) = readFromUriIntoCache(listOf(uri))

    suspend fun readFromUriIntoCache(uriList: List<Uri>): ImporterWorkspace {
        var completedWorkspace: ImporterWorkspace? = null
        try {
            return runInterruptible(Dispatchers.IO) {
                readFromUriIntoCacheBlocking(uriList).also { completedWorkspace = it }
            }
        } catch (error: CancellationException) {
            runCatching { completedWorkspace?.close() }
            throw error
        }
    }

    private fun readFromUriIntoCacheBlocking(uriList: List<Uri>): ImporterWorkspace {
        if (uriList.size !in 1..MaxImportFiles) {
            throw ExtensionImportException()
        }
        val workspace = try {
            importer.new()
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (_: Exception) {
            throw ExtensionImportException()
        }
        var completed = false
        try {
            val importBudget = ExtensionImportBudget()
            workspace.inputFileInfos = buildList {
                for (uri in uriList) {
                    importBudget.beginInput().use { admission ->
                        requireImportStorage(workspace.dir, MaxImportSourceSize)
                        val sourceLimit = minOf(
                            MaxImportSourceSize,
                            admission.remainingSourceBytes(),
                        )
                        if (sourceLimit <= 0L) throw ExtensionImportLimitException()
                        extensionSourceImporter.stage(
                            source = uri,
                            maximumBytes = sourceLimit,
                            minimumBytes = 0L,
                        ).useForExtensionImport { staged ->
                            val file = workspace.inputDir.subFile("${UUID.randomUUID()}.flex")
                            val displayLabel = sanitizeImportDisplayLabel(staged.displayName)
                            val actualSize = staged.byteCount
                            val sourceMimeType = staged.sourceMimeType
                            Files.move(staged.path, file.toPath())
                            admission.addSourceBytes(actualSize.toInt())
                            requireImportStorage(
                                workspace.dir,
                                BoundedExtensionArchive.DefaultLimits.maxExpandedBytes,
                            )
                            val extWorkingDir = workspace.outputDir.subDir(file.nameWithoutExtension)
                            val ext = try {
                                val decodedExtension =
                                    BoundedExtensionArchive.extractAfterInspectingText(
                                    source = file.toPath(),
                                    destination = extWorkingDir.toPath(),
                                    relativePath = ExtensionDefaults.MANIFEST_FILE_NAME,
                                    maxTextBytes = MaxManifestSize,
                                    admission = admission,
                                ) { manifestJson ->
                                    decodeExtensionManifest<Extension>(manifestJson)
                                        .getOrThrow()
                                        .also {
                                            check(it.validateForImport().isValid) {
                                                "Extension manifest is invalid."
                                            }
                                        }
                                    }
                                ensureImportThreadActive()
                                decodedExtension.also { it.workingDir = extWorkingDir }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: InterruptedException) {
                                throw error
                            } catch (error: ExtensionImportLimitException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                            ensureImportThreadActive()
                            if (ext == null) {
                                removeRejectedImport(file, extWorkingDir)
                                admission.commitAttempt()
                            } else {
                                admission.commit()
                            }
                            add(
                                FileInfo(
                                    file,
                                    displayLabel,
                                    FileRegistry.guessMediaType(file, sourceMimeType),
                                    actualSize,
                                    ext,
                                ),
                            )
                        }
                    }
                }
            }
            completed = true
            return workspace
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (_: Exception) {
            throw ExtensionImportException()
        } finally {
            if (!completed) {
                runCatching { workspace.close() }
            }
        }
    }

    open inner class WorkspacesContainer<T : Workspace> internal constructor(
        val dirName: String,
        val factory: (uuid: String) -> T,
    ) {
        private val workspaces = ConcurrentHashMap<String, T>()

        val dir: FsDir = appContext.cacheDir.subDir(dirName)

        fun new(uuid: String = UUID.randomUUID().toString()): T {
            val workspace = factory(uuid)
            add(workspace)
            try {
                workspace.mkdirs()
                return workspace
            } catch (error: Throwable) {
                runCatching { workspace.close() }
                throw error
            }
        }

        internal fun add(workspace: T) {
            workspaces[workspace.uuid] = workspace
        }

        internal fun remove(workspace: Workspace) {
            val registered = workspaces[workspace.uuid]
            if (registered === workspace) {
                workspaces.remove(workspace.uuid, registered)
            }
        }

        fun getWorkspaceByUuid(uuid: String): T? = workspaces[uuid]
    }

    abstract inner class Workspace(val uuid: String) : Closeable {
        abstract val dir: FsDir
        private val closeGuard = Any()

        open fun mkdirs() {
            createWorkspaceDirectory(dir)
        }

        fun isOpen() = dir.exists()

        fun isClosed() = !dir.exists()

        override fun close() {
            synchronized(closeGuard) {
                try {
                    repeat(2) {
                        if (dir.exists()) {
                            dir.deleteRecursively()
                        }
                    }
                    if (dir.exists()) {
                        throw IOException("Unable to delete private workspace.")
                    }
                } finally {
                    unregister()
                }
            }
        }

        protected open fun unregister() = Unit
    }

    inner class ImporterWorkspace(uuid: String) : Workspace(uuid) {
        override val dir: FsDir = importer.dir.subDir(uuid)

        val inputDir: FsDir = dir.subDir(InputDirName)
        val outputDir: FsDir = dir.subDir(OutputDirName)

        var inputFileInfos = emptyList<FileInfo>()

        override fun mkdirs() {
            super.mkdirs()
            createWorkspaceDirectory(inputDir)
            createWorkspaceDirectory(outputDir)
        }

        override fun unregister() {
            importer.remove(this)
        }
    }

    inner class ExporterWorkspace(uuid: String) : Workspace(uuid) {
        override val dir: FsDir = exporter.dir.subDir(uuid)

        override fun unregister() {
            exporter.remove(this)
        }
    }

    inner class ExtEditorWorkspace<T : ExtensionEditor>(uuid: String) : Workspace(uuid) {
        override val dir: FsDir = themeExtEditor.dir.subDir(uuid)

        val extDir: FsDir = dir.subDir("ext")
        val saverDir: FsDir = dir.subDir("saver")
        val previewMaterialization = ThemeMaterialization(extDir) { }

        var currentAction by mutableStateOf<EditorAction?>(null)
        var ext: Extension? = null
        var editor by mutableStateOf<T?>(null)
        internal var originalArchiveFingerprint: InstalledExtensionArchiveFingerprint? = null
        var saveInProgress by mutableStateOf(false)
        var archiveSaved by mutableStateOf(false)
        var version by mutableIntStateOf(0)

        val isModified get() = version > 0

        override fun mkdirs() {
            super.mkdirs()
            createWorkspaceDirectory(extDir)
            createWorkspaceDirectory(saverDir)
        }

        inline fun <R> update(block: T.() -> R): R {
            // Method is designed to only be called when editor has been previously initialized
            val ret = block(editor!!)
            version++
            return ret
        }

        override fun unregister() {
            themeExtEditor.remove(this)
        }
    }

    inner class BackupAndRestoreWorkspace(uuid: String) : Workspace(uuid) {
        override val dir: FsDir = backupAndRestore.dir.subDir(uuid)

        val inputDir: FsDir = dir.subDir(InputDirName)
        val outputDir: FsDir = dir.subDir(OutputDirName)

        lateinit var zipFile: FsFile
        internal lateinit var metadata: BackupArchive.Metadata
        var restoreWarningId: Int? = null
        private val restoreSessionGuard = Any()
        private val restoreCloseGuard = Any()
        private var restoreLifecycleClosed = false
        private var currentRestoreSession: BackupArchiveSession? = null

        internal val restoreSession: BackupArchiveSession?
            get() = synchronized(restoreSessionGuard) { currentRestoreSession }

        internal fun replaceRestoreSession(session: BackupArchiveSession?) {
            synchronized(restoreSessionGuard) {
                if (restoreLifecycleClosed) {
                    session?.close()
                    return
                }
                if (currentRestoreSession === session) return
                val previousSession = currentRestoreSession
                currentRestoreSession = session
                previousSession?.close()
            }
        }

        internal fun requestClose(child: Closeable? = null) {
            workspaceCleanupScope.launch {
                repeat(2) {
                    child?.close()
                    close()
                }
            }
        }

        override fun mkdirs() {
            super.mkdirs()
            createWorkspaceDirectory(inputDir)
            createWorkspaceDirectory(outputDir)
        }

        override fun close() {
            synchronized(restoreCloseGuard) {
                val sessionToClose = synchronized(restoreSessionGuard) {
                    if (restoreLifecycleClosed) {
                        null
                    } else {
                        restoreLifecycleClosed = true
                        currentRestoreSession.also { currentRestoreSession = null }
                    }
                }
                try {
                    sessionToClose?.close()
                } finally {
                    super.close()
                }
            }
        }

        override fun unregister() {
            backupAndRestore.remove(this)
        }
    }

    data class FileInfo(
        val file: FsFile,
        val displayLabel: String,
        val mediaType: String?,
        val size: Long,
        val ext: Extension?,
        var skipReason: Int? = null,
    )
}

class ExtensionImportException internal constructor() :
    IOException("Unable to import selected extension data.")
