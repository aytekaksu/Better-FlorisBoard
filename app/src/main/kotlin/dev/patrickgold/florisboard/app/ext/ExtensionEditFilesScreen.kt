/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import org.florisboard.lib.kotlin.io.subFile
import org.florisboard.lib.kotlin.mimeTypeFilterOf

private const val EditorAssetImportTimeoutMs = 30_000L
private const val EditorAssetImportStorageHeadroom = 128L * 1_024L * 1_024L

const val FONTS = "fonts"
const val IMAGES = "images"

private class PendingEditorAsset(
    val staged: StagedExternalContent,
    val suggestedName: String,
) {
    override fun toString() = "PendingEditorAsset(staged=true)"
}

private class PendingEditorAssetOwner : RememberObserver {
    private val guard = Any()
    private var staged: StagedExternalContent? = null

    fun takeOwnership(next: StagedExternalContent) {
        val previous = synchronized(guard) {
            staged.also { staged = next }
        }
        if (previous !== next) previous?.close()
    }

    fun detach(owned: StagedExternalContent) {
        synchronized(guard) {
            if (staged === owned) staged = null
        }
    }

    fun close(owned: StagedExternalContent) {
        val toClose = synchronized(guard) {
            owned.takeIf { staged === it }?.also { staged = null }
        }
        toClose?.close()
    }

    fun closeCurrent() {
        val toClose = synchronized(guard) {
            staged.also { staged = null }
        }
        toClose?.close()
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = closeCurrent()

    override fun onAbandoned() = closeCurrent()

    override fun toString() = "PendingEditorAssetOwner(hasStage=${synchronized(guard) { staged != null }})"
}

private enum class EditorAssetInstallResult {
    SUCCESS,
    INVALID_NAME,
    ALREADY_EXISTS,
    FAILURE,
}

val MIME_TYPES = mapOf(
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
fun ExtensionEditFilesScreen(workspace: CacheManager.ExtEditorWorkspace<*>) = FlorisScreen {
    title = stringRes(R.string.ext__editor__files__title)

    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    val pendingAssetOwner = remember { PendingEditorAssetOwner() }
    var isImportingFile by remember { mutableStateOf(false) }
    val externalContentImporter = remember(context, workspace.uuid) {
        DisposableExternalContentImporter(
            context = context,
            timeoutMs = EditorAssetImportTimeoutMs,
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
        if (!isImportingFile) {
            workspace.currentAction = null
        }
    }

    navigationIcon {
        FlorisIconButton(
            onClick = { handleBackPress() },
            enabled = !isImportingFile,
            icon = Icons.Default.Close,
        )
    }

    content {
        var version by rememberSaveable { mutableIntStateOf(0) }
        val fontFiles = remember(version) {
            workspace.extDir.subDir(FONTS).listFiles { it.isFile }.orEmpty().asList()
        }
        val imageFiles = remember(version) {
            workspace.extDir.subDir(IMAGES).listFiles { it.isFile }.orEmpty().asList()
        }

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
                pendingAssetOwner.closeCurrent()
                currentImportResult = null
                importScope.launch {
                    val pendingStage = AtomicReference<StagedExternalContent?>()
                    try {
                        val stagedResult = try {
                            Result.success(
                                runInterruptible(Dispatchers.IO) {
                                    val maximumBytes = minOf(
                                        CacheManager.MaxImportSourceSize,
                                        (
                                            context.cacheDir.conservativeUsableSpace() -
                                                EditorAssetImportStorageHeadroom
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
                                        MIME_TYPES.getValue(destination)
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
                                pendingAssetOwner.takeOwnership(pending.staged)
                                check(pendingStage.compareAndSet(pending.staged, null))
                            }
                            currentImportResult = stagedResult
                        }
                    } finally {
                        pendingStage.getAndSet(null)?.close()
                        isImportingFile = false
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
            if (!isImportingFile) handleBackPress()
        }

        @Composable
        fun FileList(
            title: String,
            icon: ImageVector,
            files: List<File>,
            addEnabled: Boolean,
            onAdd: () -> Unit,
        ) {
            var dialogFile by remember { mutableStateOf<File?>(null) }
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
                var fileNameInput by rememberSaveable { mutableStateOf(file.name) }
                JetPrefAlertDialog(
                    title = stringRes(R.string.general__properties),
                    confirmLabel = stringRes(R.string.action__apply),
                    dismissLabel = stringRes(R.string.action__cancel),
                    neutralLabel = stringRes(R.string.action__delete),
                    allowOutsideDismissal = true,
                    onNeutral = {
                        val message = if (file.delete()) {
                            workspace.update { }
                            "Successfully deleted"
                        } else {
                            "Failed to delete"
                        }
                        importScope.launch { context.showShortToast(message) }
                        dialogFile = null
                        version++
                    },
                    onConfirm = {
                        val newFile = file.parentFile!!.subFile(fileNameInput).canonicalFile
                        if (newFile.parentFile != file.canonicalFile.parentFile) {
                            importScope.launch { context.showLongToast("Invalid file name!") }
                            return@JetPrefAlertDialog
                        }
                        if (newFile.exists()) {
                            importScope.launch { context.showShortToast("Filename already exists.") }
                            return@JetPrefAlertDialog
                        }
                        val message = if (file.renameTo(newFile)) {
                            workspace.update { }
                            "Successfully renamed"
                        } else {
                            "Failed to rename the file."
                        }
                        importScope.launch { context.showShortToast(message) }
                        dialogFile = null
                        version++
                    },
                    onDismiss = {
                        dialogFile = null
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

        FileList(
            title = stringRes(R.string.ext__editor__files__type_fonts),
            icon = Icons.Default.TextFields,
            files = fontFiles,
            addEnabled = !isImportingFile,
        ) {
            currentImportDest = FONTS
            importLauncher.launch("*/*")
        }

        FileList(
            title = stringRes(R.string.ext__editor__files__type_images),
            icon = Icons.Default.Photo,
            files = imageFiles,
            addEnabled = !isImportingFile,
        ) {
            currentImportDest = IMAGES
            importLauncher.launch("*/*")
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
                                val committedResult = withContext(Dispatchers.IO) {
                                    installStagedEditorAsset(
                                        destinationDirectory = workspace.extDir.subDir(dest),
                                        requestedFileName = fileNameInput.trim(),
                                        staged = result.staged,
                                    )
                                }
                                if (committedResult == EditorAssetInstallResult.SUCCESS) {
                                    workspace.update { }
                                    version++
                                    pendingAssetOwner.detach(result.staged)
                                    currentImportDest = null
                                    currentImportResult = null
                                }
                                committedResult
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
