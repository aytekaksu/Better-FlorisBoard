/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.ext

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.clipboard.provider.DisposableExternalContentImporter
import dev.patrickgold.florisboard.ime.clipboard.provider.StagedExternalContent
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.ext.SafeRelativePath
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.conservativeUsableSpace
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.mimeTypeFilterOf
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicReference

private const val EDITOR_ASSET_IMPORT_TIMEOUT_MS = 30_000L
private const val EDITOR_ASSET_IMPORT_STORAGE_HEADROOM = 128L * 1_024L * 1_024L

private class PendingEditorAsset(val staged: StagedExternalContent, val suggestedName: String) {
    override fun toString() = "PendingEditorAsset(staged=true)"
}

private class PendingEditorAssetOwner : RememberObserver {
    private val guard = Any()
    private val disposalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var staged: StagedExternalContent? = null
    private var retirement: Job? = null
    private var closed = false

    fun takeOwnership(next: StagedExternalContent): Boolean = synchronized(guard) {
        if (closed || retirement?.isCompleted == false || (staged != null && staged !== next)) {
            return@synchronized false
        }
        staged = next
        true
    }

    fun detach(owned: StagedExternalContent): Boolean = synchronized(guard) {
        if (closed || staged !== owned) return@synchronized false
        staged = null
        true
    }

    fun close(owned: StagedExternalContent) {
        synchronized(guard) {
            if (staged === owned) retireCurrent()
        }
    }

    fun closeCurrent(): Job? = synchronized(guard) { retireCurrent() }

    // The guard keeps one stage and one retirement; the next import joins this job.
    private fun retireCurrent(): Job? {
        val owned = staged ?: return retirement
        check(retirement?.isCompleted != false)
        val job = disposalScope.launch(start = CoroutineStart.LAZY) { owned.close() }
        staged = null
        retirement = job
        job.start()
        return job
    }

    private fun release() {
        synchronized(guard) {
            if (closed) return
            closed = true
            val finalRetirement = retireCurrent()
            if (finalRetirement == null) {
                disposalScope.cancel()
            } else {
                // No new work can enter after release; cancel only after physical cleanup ends.
                finalRetirement.invokeOnCompletion { disposalScope.cancel() }
            }
        }
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = release()

    override fun onAbandoned() = release()

    override fun toString() = "PendingEditorAssetOwner(hasStage=${synchronized(guard) { staged != null }})"
}

private enum class EditorAssetInstallResult {
    SUCCESS,
    INVALID_NAME,
    ALREADY_EXISTS,
    FAILURE,
}

private val editorAssetMimeTypes = mapOf(
    FONTS to mimeTypeFilterOf(
        // Source: https://www.alienfactory.co.uk/articles/mime-types-for-web-fonts-in-bedsheet#mimeTypes
        "font/*",
        "application/font-*",
        "application/x-font-*",
        "application/vnd.ms-fontobject",
    ),
    IMAGES to mimeTypeFilterOf(
        "image/*",
    ),
)

@Composable
fun ExtensionEditFilesScreen(workspace: CacheManager.ThemeEditorWorkspace) = FlorisScreen {
    title = stringRes(R.string.ext__editor__files__title)

    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    val pendingAssetOwner = remember { PendingEditorAssetOwner() }
    var isImportingFile by remember { mutableStateOf(false) }
    var isMutatingFile by remember { mutableStateOf(false) }
    val assetStore = remember(workspace) { EditorAssetStore(workspace.dir.toPath()) }
    val externalContentImporter = remember(context, workspace.uuid) {
        DisposableExternalContentImporter(
            context = context,
            timeoutMs = EDITOR_ASSET_IMPORT_TIMEOUT_MS,
            stageCapacity = { CacheManager.MaxImportSourceSize },
            stagingDirectory = "extension-editor-${workspace.uuid}",
        )
    }
    DisposableEffect(externalContentImporter) {
        onDispose {
            externalContentImporter.close()
        }
    }

    fun handleBackPress() {
        if (!isImportingFile && !isMutatingFile) {
            workspace.currentAction = null
        }
    }

    navigationIcon {
        FlorisIconButton(
            onClick = { handleBackPress() },
            enabled = !isImportingFile && !isMutatingFile,
            icon = Icons.Default.Close,
        )
    }

    content {
        val files = rememberEditorAssetFiles(workspace)

        var currentImportDest by remember { mutableStateOf<String?>(null) }
        var currentImportResult by remember { mutableStateOf<Result<PendingEditorAsset>?>(null) }

        val importLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
            onResult = { uri ->
                val destination = currentImportDest
                if (uri == null || destination == null || isImportingFile) {
                    return@rememberLauncherForActivityResult
                }
                isImportingFile = true
                val priorRetirement = pendingAssetOwner.closeCurrent()
                currentImportResult = null
                importScope.launch {
                    val pendingStage = AtomicReference<StagedExternalContent?>()
                    try {
                        priorRetirement?.join()
                        val stagedResult = try {
                            Result.success(
                                runInterruptible(Dispatchers.IO) {
                                    val maximumBytes = minOf(
                                        CacheManager.MaxImportSourceSize,
                                        (
                                            context.cacheDir.conservativeUsableSpace() -
                                                EDITOR_ASSET_IMPORT_STORAGE_HEADROOM
                                            ).coerceAtLeast(0L),
                                    )
                                    check(maximumBytes > 0L) {
                                        "Editor asset storage is unavailable."
                                    }
                                    val staged = checkNotNull(
                                        externalContentImporter.stage(
                                            source = uri,
                                            maximumBytes = maximumBytes,
                                            minimumBytes = 1L,
                                        ),
                                    ) {
                                        "Selected asset could not be staged."
                                    }
                                    pendingStage.set(staged)
                                    check(
                                        editorAssetMimeTypes.getValue(destination)
                                            .matches(staged.sourceMimeType),
                                    ) {
                                        "Unsupported selected file type."
                                    }
                                    PendingEditorAsset(
                                        staged = staged,
                                        suggestedName = CacheManager.sanitizeImportDisplayLabel(
                                            staged.displayName,
                                        ),
                                    )
                                },
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                        val callerContext = currentCoroutineContext()
                        withContext(NonCancellable) {
                            callerContext.ensureActive()
                            stagedResult.getOrNull()?.let { pending ->
                                if (!pendingAssetOwner.takeOwnership(pending.staged)) return@withContext
                                check(pendingStage.compareAndSet(pending.staged, null))
                            }
                            currentImportResult = stagedResult
                        }
                    } finally {
                        try {
                            pendingStage.getAndSet(null)?.let { unhanded ->
                                withContext(NonCancellable + Dispatchers.IO) { unhanded.close() }
                            }
                        } finally {
                            isImportingFile = false
                        }
                    }
                }
            },
        )

        LaunchedEffect(currentImportResult) {
            if (currentImportResult?.isFailure == true) {
                context.showLongToast(R.string.error__snackbar_message)
            }
        }

        BackHandler {
            handleBackPress()
        }

        @Composable
        fun FileList(
            title: String,
            icon: ImageVector,
            files: List<EditorAssetFile>,
            addEnabled: Boolean,
            onAdd: () -> Unit,
        ) {
            var dialogFile by remember { mutableStateOf<EditorAssetFile?>(null) }
            ListItem(
                headlineContent = {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    Spacer(modifier = Modifier.width(24.dp))
                },
                trailingContent = {
                    IconButton(
                        onClick = onAdd,
                        enabled = addEnabled,
                    ) {
                        Icon(Icons.Default.Add, null)
                    }
                },
            )
            for (file in files) {
                Preference(
                    onClick = {
                        dialogFile = file
                    },
                    icon = icon,
                    title = file.name,
                )
            }

            dialogFile?.let { file ->
                var fileNameInput by rememberSaveable(file) { mutableStateOf(file.name) }

                fun mutateFile(newName: String? = null) {
                    if (isImportingFile || isMutatingFile) return
                    isMutatingFile = true
                    importScope.launch {
                        try {
                            val callerContext = currentCoroutineContext()
                            val result = withContext(NonCancellable) {
                                callerContext.ensureActive()
                                val outcome = withContext(Dispatchers.IO) {
                                    workspace.withOpenFileOperation {
                                        if (newName == null) {
                                            assetStore.delete(file)
                                        } else {
                                            assetStore.rename(file, newName)
                                        }
                                    } ?: EditorAssetMutationResult.FAILURE
                                }
                                if (outcome == EditorAssetMutationResult.SUCCESS) workspace.update { }
                                outcome
                            }
                            if (dialogFile !== file) return@launch
                            when {
                                newName == null -> {
                                    context.showShortToast(
                                        if (result == EditorAssetMutationResult.SUCCESS) {
                                            "Successfully deleted"
                                        } else {
                                            "Failed to delete"
                                        },
                                    )
                                    dialogFile = null
                                }

                                result == EditorAssetMutationResult.INVALID_NAME ->
                                    context.showLongToast("Invalid file name!")

                                result == EditorAssetMutationResult.ALREADY_EXISTS ->
                                    context.showShortToast("Filename already exists.")

                                else -> {
                                    context.showShortToast(
                                        if (result == EditorAssetMutationResult.SUCCESS) {
                                            "Successfully renamed"
                                        } else {
                                            "Failed to rename the file."
                                        },
                                    )
                                    dialogFile = null
                                }
                            }
                        } finally {
                            isMutatingFile = false
                        }
                    }
                }

                JetPrefAlertDialog(
                    title = stringRes(R.string.general__properties),
                    confirmLabel = stringRes(R.string.action__apply),
                    dismissLabel = stringRes(R.string.action__cancel),
                    neutralLabel = stringRes(R.string.action__delete),
                    allowOutsideDismissal = true,
                    onNeutral = { mutateFile() },
                    onConfirm = { mutateFile(fileNameInput) },
                    onDismiss = {
                        if (!isMutatingFile) dialogFile = null
                    },
                ) {
                    JetPrefTextField(
                        labelText = stringRes(R.string.general__file_name),
                        value = fileNameInput,
                        onValueChange = { fileNameInput = it },
                        singleLine = true,
                    )
                }
            }
        }

        if (files == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            FileList(
                title = stringRes(R.string.ext__editor__files__type_fonts),
                icon = Icons.Default.TextFields,
                files = files.fonts,
                addEnabled = !isImportingFile && !isMutatingFile,
            ) {
                currentImportDest = FONTS
                importLauncher.launch("*/*")
            }

            FileList(
                title = stringRes(R.string.ext__editor__files__type_images),
                icon = Icons.Default.Photo,
                files = files.images,
                addEnabled = !isImportingFile && !isMutatingFile,
            ) {
                currentImportDest = IMAGES
                importLauncher.launch("*/*")
            }
        }

        val dest = currentImportDest
        val result = currentImportResult?.getOrNull()
        if (dest != null && result != null) {
            var fileNameInput by rememberSaveable(result.staged.path) {
                mutableStateOf(result.suggestedName)
            }
            JetPrefAlertDialog(
                title = stringRes(R.string.action__import_file),
                confirmLabel = stringRes(R.string.action__add),
                onConfirm = {
                    if (isImportingFile) return@JetPrefAlertDialog
                    isImportingFile = true
                    importScope.launch {
                        try {
                            val callerContext = currentCoroutineContext()
                            val installResult = withContext(NonCancellable) {
                                callerContext.ensureActive()
                                if (!pendingAssetOwner.detach(result.staged)) {
                                    return@withContext EditorAssetInstallResult.FAILURE
                                }
                                var installed = false
                                try {
                                    val requestedFileName = fileNameInput.trim()
                                    val committedResult = withContext(Dispatchers.IO) {
                                        (
                                            workspace.withOpenFileOperation {
                                                installStagedEditorAsset(
                                                    destinationDirectory = workspace.extDir.subDir(dest),
                                                    requestedFileName = requestedFileName,
                                                    staged = result.staged,
                                                )
                                            } ?: EditorAssetInstallResult.FAILURE
                                            ).also { installed = it == EditorAssetInstallResult.SUCCESS }
                                    }
                                    if (installed) {
                                        workspace.update { }
                                        currentImportDest = null
                                        currentImportResult = null
                                    }
                                    committedResult
                                } finally {
                                    if (installed || !pendingAssetOwner.takeOwnership(result.staged)) {
                                        withContext(Dispatchers.IO) { result.staged.close() }
                                    }
                                }
                            }
                            when (installResult) {
                                EditorAssetInstallResult.SUCCESS -> Unit

                                EditorAssetInstallResult.INVALID_NAME -> {
                                    context.showShortToast("Invalid file name")
                                }

                                EditorAssetInstallResult.ALREADY_EXISTS -> {
                                    context.showShortToast("File already exists")
                                }

                                EditorAssetInstallResult.FAILURE -> {
                                    context.showShortToast("Failed to add file")
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } finally {
                            isImportingFile = false
                        }
                    }
                },
                dismissLabel = stringRes(R.string.action__cancel),
                onDismiss = {
                    if (isImportingFile) return@JetPrefAlertDialog
                    pendingAssetOwner.close(result.staged)
                    currentImportDest = null
                    currentImportResult = null
                },
            ) {
                JetPrefTextField(
                    value = fileNameInput,
                    onValueChange = { fileNameInput = it },
                    singleLine = true,
                )
            }
        }
    }
}

private fun installStagedEditorAsset(
    destinationDirectory: File,
    requestedFileName: String,
    staged: StagedExternalContent,
): EditorAssetInstallResult {
    val safeName = SafeRelativePath.parse(requestedFileName).getOrNull()
        ?: return EditorAssetInstallResult.INVALID_NAME
    if ('/' in safeName.value) return EditorAssetInstallResult.INVALID_NAME

    return try {
        val destinationRoot = destinationDirectory.toPath()
        Files.createDirectories(destinationRoot)
        if (
            !Files.isDirectory(destinationRoot, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(destinationRoot)
        ) {
            return EditorAssetInstallResult.FAILURE
        }
        val canonicalRoot = destinationRoot.toRealPath()
        val destination = safeName.resolveWithin(canonicalRoot).getOrNull()
            ?: return EditorAssetInstallResult.INVALID_NAME
        if (destination.parent != canonicalRoot) {
            return EditorAssetInstallResult.INVALID_NAME
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return EditorAssetInstallResult.ALREADY_EXISTS
        }
        if (
            !Files.isRegularFile(staged.path, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(staged.path) ||
            Files.size(staged.path) != staged.byteCount
        ) {
            return EditorAssetInstallResult.FAILURE
        }
        Files.move(staged.path, destination)
        EditorAssetInstallResult.SUCCESS
    } catch (_: FileAlreadyExistsException) {
        EditorAssetInstallResult.ALREADY_EXISTS
    } catch (_: Exception) {
        EditorAssetInstallResult.FAILURE
    }
}
