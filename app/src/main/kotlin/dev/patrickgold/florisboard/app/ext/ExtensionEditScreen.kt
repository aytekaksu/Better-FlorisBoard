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

package dev.patrickgold.florisboard.app.ext

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.OwnedRoutePopResult
import dev.patrickgold.florisboard.app.popOwnedRouteWhenResumed
import dev.patrickgold.florisboard.app.settings.advanced.RadioListItem
import dev.patrickgold.florisboard.app.settings.theme.DialogProperty
import dev.patrickgold.florisboard.app.settings.theme.PrettyPrintConfig
import dev.patrickgold.florisboard.app.settings.theme.ThemeEditorScreen
import dev.patrickgold.florisboard.app.settings.theme.newEmptyThemeStylesheetEditor
import dev.patrickgold.florisboard.cacheManager
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponent
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponentEditor
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponentImpl
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionEditor
import dev.patrickgold.florisboard.ime.theme.ThemeManager
import dev.patrickgold.florisboard.lib.ValidationResult
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.compose.FlorisUnsavedChangesDialog
import dev.patrickgold.florisboard.lib.compose.Validation
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.ext.ExtensionDefaults
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.lib.ext.ExtensionMaintainer
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import dev.patrickgold.florisboard.lib.ext.ExtensionValidation
import dev.patrickgold.florisboard.lib.ext.validateForImport
import dev.patrickgold.florisboard.lib.io.ZipUtils
import dev.patrickgold.florisboard.lib.rememberValidationResult
import dev.patrickgold.florisboard.themeManager
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefTextField
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.FlorisButtonBar
import org.florisboard.lib.compose.FlorisIconButton
import org.florisboard.lib.compose.FlorisInfoCard
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile

private val TextFieldVerticalPadding = 8.dp
private val MetaDataContentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp)

private const val AnimationDuration = 300

private val ActionScreenEnterTransition = fadeIn(tween(AnimationDuration))
private val ActionScreenExitTransition = fadeOut(tween(AnimationDuration))

private val ThemeEditorWorkspaceLifecycleGuard = Mutex()
private val ThemeEditorWorkspaceCleanupScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

private sealed interface ExtensionEditorOpenState {
    object Opening : ExtensionEditorOpenState

    data class Ready(
        val workspace: CacheManager.ThemeEditorWorkspace,
    ) : ExtensionEditorOpenState

    object Failed : ExtensionEditorOpenState
}

private enum class ExtensionEditorOperation {
    IDLE,
    SAVING,
    CLOSING,
}

internal fun newEmptyThemeComponentEditor(
    id: String,
    label: String,
    authors: List<String>,
) = ThemeExtensionComponentEditor(id, label, authors).also {
    it.stylesheetEditor = newEmptyThemeStylesheetEditor()
}

internal fun addThemeComponent(
    workspace: CacheManager.ThemeEditorWorkspace,
    component: ThemeExtensionComponentEditor,
) {
    workspace.update {
        themes.add(component)
    }
    workspace.currentAction = null
}

internal data class ThemeStylesheetSave(
    val relativePath: String,
    val serializedStylesheet: String?,
)

internal data class ExtensionEditorSavePlan(
    val serializedManifest: String,
    val stylesheets: List<ThemeStylesheetSave>,
)

sealed interface ThemeEditorAction {
    object ManageMetaData : ThemeEditorAction

    object ManageDependencies : ThemeEditorAction

    object ManageFiles : ThemeEditorAction

    object CreateTheme : ThemeEditorAction

    data class EditTheme(val editor: ThemeExtensionComponentEditor) : ThemeEditorAction
}

@Composable
fun ExtensionEditScreen(
    id: String,
    createSerialType: String?,
    routeEntry: NavBackStackEntry,
) {
    val context = LocalContext.current
    val cacheManager by context.cacheManager()
    val extensionManager by context.extensionManager()
    val themeManager by context.themeManager()
    val isCreateExt = createSerialType != null
    val ext = remember(id, createSerialType, extensionManager) {
        if (isCreateExt) {
            val meta = ExtensionMeta(
                id = ExtensionDefaults.createLocalId("themes", System.currentTimeMillis().toString()),
                version = "0.0.0",
                title = "My themes",
                maintainers = listOf(ExtensionMaintainer(name = "Local")),
                license = "(none specified)",
            )
            when (createSerialType) {
                ThemeExtension.SERIAL_TYPE -> ThemeExtension(meta, null, emptyList())
                else -> null
            }
        } else {
            extensionManager.getExtensionById(id)
        }
    }
    if (ext !is ThemeExtension || (!isCreateExt && !extensionManager.canDelete(ext))) {
        ExtensionNotFoundScreen(id)
        return
    }

    val uuid = rememberSaveable { UUID.randomUUID().toString() }
    var openState by remember(uuid, ext) {
        mutableStateOf<ExtensionEditorOpenState>(ExtensionEditorOpenState.Opening)
    }

    LaunchedEffect(uuid, ext, isCreateExt) {
        openState = ExtensionEditorOpenState.Opening
        try {
            val workspace = openThemeEditorWorkspace(
                themeManager = themeManager,
                extensionManager = extensionManager,
                container = cacheManager.themeEditor,
                uuid = uuid,
                extension = ext,
                extractArchive = !isCreateExt,
            )
            openState = ExtensionEditorOpenState.Ready(workspace)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            flogError {
                "Extension editor open failed: failureClass=${error.javaClass.simpleName}"
            }
            openState = ExtensionEditorOpenState.Failed
        }
    }

    val title = stringRes(
        if (isCreateExt) {
            R.string.ext__editor__title_create_theme
        } else {
            R.string.ext__editor__title_edit_theme
        },
    )
    when (val state = openState) {
        ExtensionEditorOpenState.Opening -> {
            ExtensionEditorOpeningScreen(title)
        }
        is ExtensionEditorOpenState.Ready -> {
            ExtensionEditScreenSheetSwitcher(
                workspace = state.workspace,
                isCreateExt = isCreateExt,
                themeManager = themeManager,
                routeEntry = routeEntry,
            )
        }
        ExtensionEditorOpenState.Failed -> {
            ExtensionEditorFailureScreen(title)
        }
    }
}

private suspend fun openThemeEditorWorkspace(
    themeManager: ThemeManager,
    extensionManager: ExtensionManager,
    container: CacheManager.WorkspacesContainer<CacheManager.ThemeEditorWorkspace>,
    uuid: String,
    extension: ThemeExtension,
    extractArchive: Boolean,
): CacheManager.ThemeEditorWorkspace = ThemeEditorWorkspaceLifecycleGuard.withLock {
    val existingWorkspace = container.getWorkspaceByUuid(uuid)
    if (existingWorkspace != null) {
        val canReuse = runInterruptible(Dispatchers.IO) {
            existingWorkspace.isOpen()
        } && existingWorkspace.hasEditor
        if (canReuse) {
            return@withLock existingWorkspace
        }
        withContext(NonCancellable) {
            try {
                closeThemeEditorWorkspaceLocked(themeManager, existingWorkspace)
            } finally {
                existingWorkspace.previewMaterialization.retireAndAwaitRelease()
                withContext(Dispatchers.IO) {
                    existingWorkspace.close()
                }
            }
        }
    }

    val workspace = container.factory(uuid)
    initializeOwnedEditorResource(
        resource = workspace,
        initialize = { ownedWorkspace ->
            runInterruptible(Dispatchers.IO) {
                ownedWorkspace.mkdirs()
                container.add(ownedWorkspace)
            }
            if (extractArchive) {
                ownedWorkspace.originalArchiveFingerprint =
                    extensionManager.materializeForEditor(extension, ownedWorkspace.extDir)
            }
            currentCoroutineContext().ensureActive()
            val editor = extension.edit()
            currentCoroutineContext().ensureActive()
            ownedWorkspace.setEditor(editor)
            currentCoroutineContext().ensureActive()
            ownedWorkspace
        },
        cleanup = { ownedWorkspace ->
            runInterruptible(Dispatchers.IO) {
                ownedWorkspace.close()
            }
        },
    )
}

private suspend fun closeThemeEditorWorkspace(
    themeManager: ThemeManager,
    workspace: CacheManager.ThemeEditorWorkspace,
) {
    ThemeEditorWorkspaceLifecycleGuard.withLock {
        closeThemeEditorWorkspaceLocked(themeManager, workspace)
    }
}

private suspend fun closeThemeEditorWorkspaceLocked(
    themeManager: ThemeManager,
    workspace: CacheManager.ThemeEditorWorkspace,
) {
    themeManager.updateActiveTheme {
        val preview = themeManager.previewThemeInfo.value
        if (
            preview?.materialization === workspace.previewMaterialization ||
            preview?.loadedDir == workspace.extDir
        ) {
            themeManager.previewThemeInfo.value = null
        }
    }
    val nextActiveThemeInfo = themeManager.activeThemeInfo.value
    check(
        nextActiveThemeInfo.loadedDir != workspace.extDir &&
            nextActiveThemeInfo.materialization !== workspace.previewMaterialization,
    ) {
        "The active theme still uses editor assets."
    }

    val callerContext = currentCoroutineContext()
    callerContext.ensureActive()
    withContext(NonCancellable) {
        callerContext.ensureActive()
        workspace.previewMaterialization.retireAndAwaitRelease()
        withContext(Dispatchers.IO) {
            workspace.close()
        }
    }
}

@Composable
private fun ExtensionEditorOpeningScreen(title: String) = FlorisScreen {
    this.title = title
    scrollable = false

    navigationIcon {
        FlorisIconButton(
            onClick = { },
            enabled = false,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
        )
    }

    content {
        BackHandler { }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun ExtensionEditorFailureScreen(title: String) = FlorisScreen {
    this.title = title

    content {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            text = stringRes(R.string.ext__editor__open_failure),
        )
    }
}

@Composable
private fun ExtensionEditScreenSheetSwitcher(
    workspace: CacheManager.ThemeEditorWorkspace,
    isCreateExt: Boolean,
    themeManager: ThemeManager,
    routeEntry: NavBackStackEntry,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        EditScreen(workspace, isCreateExt, themeManager, routeEntry)
        AnimatedVisibility(
            visible = workspace.currentAction != null,
            enter = ActionScreenEnterTransition,
            exit = ActionScreenExitTransition,
        ) {
            when (val action = workspace.currentAction) {
                ThemeEditorAction.ManageMetaData -> {
                    ManageMetaDataScreen(workspace, isCreateExt)
                }
                ThemeEditorAction.ManageDependencies -> {
                    ManageDependenciesScreen(workspace)
                }
                ThemeEditorAction.ManageFiles -> {
                    ExtensionEditFilesScreen(workspace)
                }
                ThemeEditorAction.CreateTheme -> {
                    CreateThemeScreen(workspace)
                }
                is ThemeEditorAction.EditTheme -> {
                    ThemeEditorScreen(workspace, action.editor)
                }
                null -> Unit
            }
        }
    }
}

@Composable
private fun EditScreen(
    workspace: CacheManager.ThemeEditorWorkspace,
    isCreateExt: Boolean,
    themeManager: ThemeManager,
    routeEntry: NavBackStackEntry,
) = FlorisScreen {
    title = stringRes(
        if (isCreateExt) {
            R.string.ext__editor__title_create_theme
        } else {
            R.string.ext__editor__title_edit_theme
        },
    )

    val context = LocalContext.current
    val navController = LocalNavController.current
    val extensionManager by context.extensionManager()
    val operationScope = rememberCoroutineScope()

    val extEditor = workspace.editor
    var showUnsavedChangesDialog by remember { mutableStateOf(false) }
    var invalidDetailsMessageResId by remember { mutableStateOf<Int?>(null) }
    var operation by remember { mutableStateOf(ExtensionEditorOperation.IDLE) }
    val isBusy = operation != ExtensionEditorOperation.IDLE || workspace.saveInProgress

    suspend fun popEditorRouteAndScheduleCleanup() {
        currentCoroutineContext().ensureActive()
        when (navController.popOwnedRouteWhenResumed(routeEntry)) {
            OwnedRoutePopResult.NOT_CURRENT -> return
            OwnedRoutePopResult.POPPED -> Unit
            OwnedRoutePopResult.FAILED -> error(
                "The extension editor route could not be closed.",
            )
        }
        ThemeEditorWorkspaceCleanupScope.launch {
            try {
                closeThemeEditorWorkspace(themeManager, workspace)
            } catch (error: Throwable) {
                flogError {
                    "Extension editor cleanup failed: " +
                        "failureClass=${error.javaClass.simpleName}"
                }
            }
        }
    }

    fun closeEditor() {
        if (isBusy) return
        operation = ExtensionEditorOperation.CLOSING
        showUnsavedChangesDialog = false
        operationScope.launch {
            try {
                popEditorRouteAndScheduleCleanup()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                flogError {
                    "Extension editor close failed: failureClass=${error.javaClass.simpleName}"
                }
                context.showLongToast(R.string.ext__editor__close_failure)
            } finally {
                operation = ExtensionEditorOperation.IDLE
            }
        }
    }

    fun handleBackPress() {
        if (isBusy) return
        if (workspace.isModified) {
            showUnsavedChangesDialog = true
        } else {
            closeEditor()
        }
    }

    fun handleSave() {
        if (
            isBusy ||
            workspace.currentAction != null
        ) {
            return
        }
        operation = ExtensionEditorOperation.SAVING
        workspace.saveInProgress = true
        showUnsavedChangesDialog = false
        operationScope.launch {
            var archiveSaved = workspace.archiveSaved
            try {
                val manifest = try {
                    extEditor.build()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    invalidDetailsMessageResId =
                        R.string.ext__editor__metadata__message_invalid
                    return@launch
                }
                if (!manifest.validateForImport().isValid) {
                    invalidDetailsMessageResId =
                        R.string.ext__editor__details__message_invalid
                    return@launch
                }
                val savePlan = createExtensionEditorSavePlan(
                    extEditor = extEditor,
                    manifest = manifest,
                )
                ThemeEditorWorkspaceLifecycleGuard.withLock {
                    runInterruptible(Dispatchers.IO) {
                        stageExtensionEditorSave(workspace, savePlan)
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        if (isCreateExt) {
                            extensionManager.installNew(manifest, workspace.saverDir)
                        } else {
                            extensionManager.replace(
                                ext = manifest,
                                stagingDir = workspace.saverDir,
                                expected = checkNotNull(workspace.originalArchiveFingerprint) {
                                    "The original extension archive is unavailable."
                                },
                            )
                        }
                        workspace.archiveSaved = true
                        archiveSaved = true
                    }
                }
                popEditorRouteAndScheduleCleanup()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val failureStage = if (archiveSaved) "close" else "archive"
                flogError {
                    "Extension editor save failed: stage=$failureStage, " +
                        "failureClass=${error.javaClass.simpleName}"
                }
                val message = if (archiveSaved) {
                    R.string.ext__editor__close_failure
                } else {
                    R.string.ext__editor__save_failure
                }
                context.showLongToast(message)
            } finally {
                workspace.saveInProgress = false
                operation = ExtensionEditorOperation.IDLE
            }
        }
    }

    LaunchedEffect(workspace.archiveSaved, routeEntry) {
        if (workspace.archiveSaved) {
            try {
                popEditorRouteAndScheduleCleanup()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                flogError {
                    "Extension editor close after save failed: " +
                        "failureClass=${error.javaClass.simpleName}"
                }
                context.showLongToast(R.string.ext__editor__close_failure)
            }
        }
    }

    navigationIcon {
        FlorisIconButton(
            onClick = { handleBackPress() },
            enabled = !isBusy,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
        )
    }

    bottomBar {
        FlorisButtonBar {
            ButtonBarSpacer()
            ButtonBarTextButton(
                text = stringRes(R.string.action__cancel),
                enabled = !isBusy,
                onClick = { handleBackPress() },
            )
            ButtonBarButton(
                text = stringRes(R.string.action__save),
                enabled = !isBusy,
                onClick = { handleSave() },
            )
        }
    }

    content {
        BackHandler {
            if (!isBusy) handleBackPress()
        }

        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
        ) {
            Preference(
                onClick = {
                    if (operation == ExtensionEditorOperation.IDLE) {
                        workspace.currentAction = ThemeEditorAction.ManageMetaData
                    }
                },
                enabledIf = { !isBusy },
                icon = Icons.Default.Code,
                title = stringRes(R.string.ext__editor__metadata__title),
            )
            Preference(
                onClick = {
                    if (operation == ExtensionEditorOperation.IDLE) {
                        workspace.currentAction = ThemeEditorAction.ManageDependencies
                    }
                },
                enabledIf = { !isBusy },
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                title = stringRes(R.string.ext__editor__dependencies__title),
            )
            Preference(
                onClick = {
                    if (operation == ExtensionEditorOperation.IDLE) {
                        workspace.currentAction = ThemeEditorAction.ManageFiles
                    }
                },
                enabledIf = { !isBusy },
                icon = ImageVector.vectorResource(R.drawable.ic_file_blank),
                title = stringRes(R.string.ext__editor__files__title),
            )
        }

        ExtensionComponentListView(
            title = stringRes(R.string.ext__meta__components_theme),
            components = extEditor.themes,
            onCreateBtnClick = if (isBusy) null else {
                {
                    if (operation == ExtensionEditorOperation.IDLE) {
                        workspace.currentAction = ThemeEditorAction.CreateTheme
                    }
                }
            },
        ) { component ->
            ExtensionComponentView(
                modifier = Modifier.defaultFlorisOutlinedBox(),
                meta = extEditor.meta,
                component = component,
                onDeleteBtnClick = if (isBusy) null else {
                    {
                        if (operation == ExtensionEditorOperation.IDLE) {
                            workspace.update { themes.remove(component) }
                        }
                    }
                },
                onEditBtnClick = if (isBusy) null else {
                    {
                        if (operation == ExtensionEditorOperation.IDLE) {
                            workspace.currentAction = ThemeEditorAction.EditTheme(component)
                        }
                    }
                },
            )
        }

        if (showUnsavedChangesDialog) {
            FlorisUnsavedChangesDialog(
                onSave = {
                    handleSave()
                },
                onDiscard = {
                    closeEditor()
                },
                onDismiss = {
                    showUnsavedChangesDialog = false
                },
            )
        }

        invalidDetailsMessageResId?.let { messageResId ->
            val titleResId = if (
                messageResId == R.string.ext__editor__metadata__message_invalid
            ) {
                R.string.ext__editor__metadata__title_invalid
            } else {
                R.string.ext__editor__details__title_invalid
            }
            JetPrefAlertDialog(
                title = stringRes(titleResId),
                confirmLabel = stringRes(R.string.action__ok),
                onConfirm = {
                    invalidDetailsMessageResId = null
                },
                onDismiss = {
                    invalidDetailsMessageResId = null
                },
                content = {
                    Text(text = stringRes(messageResId))
                },
            )
        }
    }
}

internal fun createExtensionEditorSavePlan(
    extEditor: ThemeExtensionEditor,
    manifest: ThemeExtension,
): ExtensionEditorSavePlan {
    val stylesheets = extEditor.themes.map { theme ->
        ThemeStylesheetSave(
            relativePath = theme.stylesheetPath(),
            serializedStylesheet = theme.stylesheetEditor?.build()
                ?.toJson(PrettyPrintConfig)
                ?.getOrThrow(),
        )
    }
    return ExtensionEditorSavePlan(
        serializedManifest = ExtensionJsonConfig.encodeToString<Extension>(manifest),
        stylesheets = stylesheets,
    )
}

private fun stageExtensionEditorSave(
    workspace: CacheManager.ThemeEditorWorkspace,
    savePlan: ExtensionEditorSavePlan,
) {
    clearDirectoryInterruptibly(workspace.saverDir)
    val copyBudget = ZipUtils.extensionTransferBudget(::ensureSaveThreadActive)
    writeTextInterruptibly(
        destination = workspace.saverDir.subFile(ExtensionDefaults.MANIFEST_FILE_NAME),
        text = savePlan.serializedManifest,
    )

    for (assetDirectoryName in listOf("fonts", "images")) {
        val sourceDirectory = workspace.extDir.subDir(assetDirectoryName)
        if (sourceDirectory.exists()) {
            ZipUtils.copyDirectoryNoFollow(
                srcDir = sourceDirectory,
                dstDir = workspace.saverDir.subDir(assetDirectoryName),
                budget = copyBudget,
            )
        }
        ensureSaveThreadActive()
    }

    val stylesheets = savePlan.stylesheets.asReversed()
        .distinctBy(ThemeStylesheetSave::relativePath)
        .asReversed()
    for (stylesheet in stylesheets) {
        val destinationFile = workspace.saverDir.subFile(stylesheet.relativePath)
        val serializedStylesheet = stylesheet.serializedStylesheet
        if (serializedStylesheet != null) {
            writeTextInterruptibly(destinationFile, serializedStylesheet)
        } else {
            val sourceFile = workspace.extDir.subFile(stylesheet.relativePath)
            ZipUtils.copyFileNoFollow(
                srcFile = sourceFile,
                dstFile = destinationFile,
                budget = copyBudget,
            )
        }
        ensureSaveThreadActive()
    }

    ensureSaveThreadActive()
}

private fun clearDirectoryInterruptibly(directory: File) {
    val root = directory.toPath()
    Files.createDirectories(root)
    val rootAttributes = Files.readAttributes(
        root,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )
    check(rootAttributes.isDirectory && !rootAttributes.isSymbolicLink) {
        "Editor staging directory is unavailable."
    }
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                ensureSaveThreadActive()
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                throw error
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                ensureSaveThreadActive()
                if (directory != root) {
                    Files.delete(directory)
                }
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun writeTextInterruptibly(
    destination: File,
    text: String,
) {
    ensureSaveThreadActive()
    destination.parentFile?.toPath()?.let { Files.createDirectories(it) }
    Files.newBufferedWriter(
        destination.toPath(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    ).use { writer ->
        var offset = 0
        while (offset < text.length) {
            ensureSaveThreadActive()
            val length = minOf(16 * 1_024, text.length - offset)
            writer.write(text, offset, length)
            offset += length
        }
    }
    ensureSaveThreadActive()
}

private fun ensureSaveThreadActive() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
}

@Composable
private fun ManageMetaDataScreen(
    workspace: CacheManager.ThemeEditorWorkspace,
    isCreateExt: Boolean,
) = FlorisScreen {
    title = stringRes(R.string.ext__editor__metadata__title)

    val meta = workspace.editor.meta
    var showValidationErrors by rememberSaveable { mutableStateOf(false) }

    var id by rememberSaveable { mutableStateOf(meta.id) }
    val idValidation = rememberValidationResult(ExtensionValidation.MetaId, id)
    var version by rememberSaveable { mutableStateOf(meta.version) }
    val versionValidation = rememberValidationResult(ExtensionValidation.MetaVersion, version)
    var title by rememberSaveable { mutableStateOf(meta.title) }
    val titleValidation = rememberValidationResult(ExtensionValidation.MetaTitle, title)
    var description by rememberSaveable { mutableStateOf(meta.description ?: "") }
    var keywords by rememberSaveable { mutableStateOf(meta.keywords?.joinToString("\n") ?: "") }
    var homepage by rememberSaveable { mutableStateOf(meta.homepage ?: "") }
    var issueTracker by rememberSaveable { mutableStateOf(meta.issueTracker ?: "") }
    var maintainers by rememberSaveable { mutableStateOf(meta.maintainers.joinToString("\n")) }
    val maintainersValidation = rememberValidationResult(ExtensionValidation.MetaMaintainers, maintainers)
    var license by rememberSaveable { mutableStateOf(meta.license) }
    val licenseValidation = rememberValidationResult(ExtensionValidation.MetaLicense, license)

    fun handleBackPress() {
        workspace.currentAction = null
    }

    fun handleApply() {
        val invalid = idValidation.isInvalid() ||
            versionValidation.isInvalid() ||
            titleValidation.isInvalid() ||
            maintainersValidation.isInvalid() ||
            licenseValidation.isInvalid()
        if (invalid) {
            showValidationErrors = true
        } else {
            workspace.update {
                this.meta = ExtensionMeta(
                    id = id.trim(),
                    version = version.trim(),
                    title = title.trim(),
                    description = description.trim().takeIf { it.isNotBlank() },
                    keywords = keywords.lines().map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() },
                    homepage = homepage.trim().takeIf { it.isNotBlank() },
                    issueTracker = issueTracker.trim().takeIf { it.isNotBlank() },
                    maintainers = maintainers.lines().map { it.trim() }.filter { it.isNotBlank() }
                        .map { ExtensionMaintainer.fromOrTakeRaw(it) },
                    license = license.trim(),
                )
            }
            workspace.currentAction = null
        }
    }

    navigationIcon {
        FlorisIconButton(
            onClick = { handleBackPress() },
            icon = Icons.Default.Close,
        )
    }

    bottomBar {
        FlorisButtonBar {
            ButtonBarSpacer()
            ButtonBarTextButton(text = stringRes(R.string.action__cancel)) {
                handleBackPress()
            }
            ButtonBarButton(text = stringRes(R.string.action__apply)) {
                handleApply()
            }
        }
    }

    content {
        BackHandler {
            handleBackPress()
        }

        Column(modifier = Modifier.padding(MetaDataContentPadding)) {
            EditorSheetTextField(
                enabled = isCreateExt,
                isRequired = true,
                value = id,
                onValueChange = { id = it },
                label = stringRes(R.string.ext__meta__id),
                showValidationError = showValidationErrors,
                validationResult = idValidation,
            )
            EditorSheetTextField(
                isRequired = true,
                value = version,
                onValueChange = { version = it },
                label = stringRes(R.string.ext__meta__version),
                showValidationError = showValidationErrors,
                validationResult = versionValidation,
            )
            EditorSheetTextField(
                isRequired = true,
                value = title,
                onValueChange = { title = it },
                label = stringRes(R.string.ext__meta__title),
                showValidationError = showValidationErrors,
                validationResult = titleValidation,
            )
            EditorSheetTextField(
                value = description,
                onValueChange = { description = it },
                label = stringRes(R.string.ext__meta__description),
            )
            EditorSheetTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = stringRes(R.string.ext__meta__keywords),
                singleLine = false,
            )
            EditorSheetTextField(
                value = homepage,
                onValueChange = { homepage = it },
                label = stringRes(R.string.ext__meta__homepage),
            )
            EditorSheetTextField(
                value = issueTracker,
                onValueChange = { issueTracker = it },
                label = stringRes(R.string.ext__meta__issue_tracker),
            )
            EditorSheetTextField(
                isRequired = true,
                value = maintainers,
                onValueChange = { maintainers = it },
                label = stringRes(R.string.ext__meta__maintainers),
                singleLine = false,
                showValidationError = showValidationErrors,
                validationResult = maintainersValidation,
            )
            EditorSheetTextField(
                isRequired = true,
                value = license,
                onValueChange = { license = it },
                label = stringRes(R.string.ext__meta__license),
                showValidationError = showValidationErrors,
                validationResult = licenseValidation,
            )
        }
    }
}

@Composable
private fun ManageDependenciesScreen(workspace: CacheManager.ThemeEditorWorkspace) = FlorisScreen {
    title = stringRes(R.string.ext__editor__dependencies__title)

    val dependencyList = workspace.editor.dependencies

    fun handleBackPress() {
        workspace.currentAction = null
    }

    navigationIcon {
        FlorisIconButton(
            onClick = { handleBackPress() },
            icon = Icons.Default.Close,
        )
    }

    content {
        BackHandler {
            handleBackPress()
        }

        FlorisInfoCard(
            modifier = Modifier.padding(all = 8.dp),
            text = """
                Dependencies are currently not implemented, but are already somewhat
                integrated as a placeholder for the future.
                """.trimIndent().replace('\n', ' '),
        )
        if (dependencyList.isEmpty()) {
            Text(text = "no deps found")
        } else {
            for (dependency in dependencyList) {
                Text(text = dependency)
            }
        }
    }
}

private enum class CreateFrom {
    EMPTY,
    EXISTING;
}

@Composable
private fun CreateThemeScreen(
    workspace: CacheManager.ThemeEditorWorkspace,
) = FlorisScreen {
    title = stringRes(R.string.ext__editor__create_component__title_theme)

    val context = LocalContext.current
    val extensionManager by context.extensionManager()
    val themeManager by context.themeManager()
    val createScope = rememberCoroutineScope()
    val editor = workspace.editor

    var createFrom by rememberSaveable { mutableStateOf(CreateFrom.EXISTING) }
    val extId = editor.meta.id
    val components = remember<Map<ExtensionComponentName, ThemeExtensionComponent>> {
        buildMap {
            for (theme in editor.themes) {
                put(ExtensionComponentName(extId, theme.id), theme)
            }
            for ((componentName, theme) in themeManager.indexedThemeConfigs.value.first) {
                if (componentName.extensionId != extId) {
                    put(componentName, theme)
                }
            }
        }
    }
    var selectedComponentName by rememberSaveable(stateSaver = ExtensionComponentName.Saver) {
        mutableStateOf(null)
    }
    var showValidationErrors by rememberSaveable { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }

    var newId by rememberSaveable { mutableStateOf("") }
    val newIdValidation = rememberValidationResult(ExtensionValidation.ComponentId, newId)
    var newLabel by rememberSaveable { mutableStateOf("") }
    val newLabelValidation = rememberValidationResult(ExtensionValidation.ComponentLabel, newLabel)
    var newAuthors by rememberSaveable { mutableStateOf("") }
    val newAuthorsValidation = rememberValidationResult(ExtensionValidation.ComponentAuthors, newAuthors)

    fun handleBackPress() {
        workspace.currentAction = null
    }

    fun handleCreate() {
        val invalid = createFrom == CreateFrom.EMPTY && (newIdValidation.isInvalid() ||
            newLabelValidation.isInvalid() || newAuthorsValidation.isInvalid())
        if (invalid) {
            showValidationErrors = true
        } else {
            if (isCreating) return
            isCreating = true
            createScope.launch {
                try {
                    when (createFrom) {
                        CreateFrom.EMPTY -> {
                            if (editor.themes.any { it.id == newId.trim() }) {
                                context.showLongToast("A theme with this ID already exists!")
                            } else {
                                val componentEditor = newEmptyThemeComponentEditor(
                                    id = newId.trim(),
                                    label = newLabel.trim(),
                                    authors = newAuthors.lines().map { it.trim() }.filter { it.isNotBlank() },
                                )
                                addThemeComponent(workspace, componentEditor)
                            }
                        }
                        CreateFrom.EXISTING -> {
                            val componentName = selectedComponentName ?: return@launch
                            val componentId = if (editor.themes.any { it.id == componentName.componentId }) {
                                var suffix = 1
                                var tempId: String
                                do {
                                    tempId = "${componentName.componentId}_${suffix++}"
                                } while (editor.themes.any { it.id == tempId })
                                tempId
                            } else {
                                componentName.componentId
                            }
                            if (componentName.extensionId == extId) {
                                val component = editor.themes.find {
                                    it.id == componentName.componentId
                                } ?: return@launch
                                val componentEditor = component.let { c ->
                                    ThemeExtensionComponentEditor(
                                        componentId, c.label, c.authors, c.isNightTheme, stylesheetPath = "",
                                    ).also { it.stylesheetEditor = c.stylesheetEditor }
                                }
                                if (componentEditor.stylesheetEditor != null) {
                                    val stylesheetFile = workspace.extDir.subFile(componentEditor.stylesheetPath())
                                    val stylesheet = componentEditor.stylesheetEditor!!.build()
                                        .toJson(PrettyPrintConfig).getOrThrow()
                                    runInterruptible(Dispatchers.IO) {
                                        writeTextInterruptibly(stylesheetFile, stylesheet)
                                    }
                                    componentEditor.stylesheetEditor = null
                                } else {
                                    val srcStylesheetFile = workspace.extDir.subFile(component.stylesheetPath())
                                    val dstStylesheetFile =
                                        workspace.extDir.subFile(componentEditor.stylesheetPath())
                                    runInterruptible(Dispatchers.IO) {
                                        ZipUtils.copyFileNoFollow(
                                            srcFile = srcStylesheetFile,
                                            dstFile = dstStylesheetFile,
                                        )
                                    }
                                }
                                addThemeComponent(workspace, componentEditor)
                            } else {
                                val component = themeManager.indexedThemeConfigs.value.first
                                    .get(componentName) ?: return@launch
                                val componentEditor =
                                    (component as? ThemeExtensionComponentImpl)?.edit() ?: return@launch
                                componentEditor.id = componentId
                                componentEditor.stylesheetPath = ""
                                val externalExt = extensionManager.getExtensionById(
                                    componentName.extensionId,
                                ) ?: return@launch
                                val stylesheetJson = runInterruptible(Dispatchers.IO) {
                                    ZipUtils.readFileFromArchive(
                                        context,
                                        checkNotNull(externalExt.sourceRef),
                                        component.stylesheetPath(),
                                    ).getOrThrow()
                                }
                                val dstStylesheetFile =
                                    workspace.extDir.subFile(componentEditor.stylesheetPath())
                                runInterruptible(Dispatchers.IO) {
                                    writeTextInterruptibly(dstStylesheetFile, stylesheetJson)
                                }
                                addThemeComponent(workspace, componentEditor)
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    context.showLongToast(R.string.error__snackbar_message)
                } finally {
                    isCreating = false
                }
            }
        }
    }

    fun hasSufficientInfoForCreating(): Boolean {
        return when (createFrom) {
            CreateFrom.EMPTY -> newId.isNotBlank() && newLabel.isNotBlank() && newAuthors.isNotBlank()
            CreateFrom.EXISTING -> components.containsKey(selectedComponentName)
        }
    }

    navigationIcon {
        FlorisIconButton(
            onClick = { handleBackPress() },
            enabled = !isCreating,
            icon = Icons.Default.Close,
        )
    }

    bottomBar {
        FlorisButtonBar {
            ButtonBarSpacer()
            ButtonBarTextButton(
                text = stringRes(R.string.action__cancel),
                enabled = !isCreating,
            ) {
                handleBackPress()
            }
            ButtonBarButton(
                text = stringRes(R.string.action__create),
                enabled = !isCreating && hasSufficientInfoForCreating(),
            ) {
                handleCreate()
            }
        }
    }

    content {
        BackHandler {
            if (!isCreating) {
                handleBackPress()
            }
        }

        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
        ) {
            RadioListItem(
                onClick = { createFrom = CreateFrom.EXISTING },
                selected = createFrom == CreateFrom.EXISTING,
                text = stringRes(R.string.ext__editor__create_component__from_existing),
            )
            RadioListItem(
                onClick = { createFrom = CreateFrom.EMPTY },
                selected = createFrom == CreateFrom.EMPTY,
                text = stringRes(R.string.ext__editor__create_component__from_empty),
            )
        }

        if (createFrom == CreateFrom.EXISTING) {
            FlorisOutlinedBox(
                modifier = Modifier.defaultFlorisOutlinedBox(),
            ) {
                for ((componentName, component) in components) {
                    RadioListItem(
                        onClick = { selectedComponentName = componentName },
                        selected = selectedComponentName == componentName,
                        text = component.label,
                        secondaryText = componentName.toString(),
                    )
                }
            }
        } else if (createFrom == CreateFrom.EMPTY) {
            FlorisInfoCard(
                modifier = Modifier.defaultFlorisOutlinedBox(),
                text = stringRes(R.string.ext__editor__create_component__from_empty_warning),
            )
            DialogProperty(
                modifier = Modifier.padding(horizontal = 16.dp),
                text = stringRes(R.string.ext__meta__id),
            ) {
                JetPrefTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = newId,
                    onValueChange = { newId = it },
                    singleLine = true,
                )
                Validation(showValidationErrors, newIdValidation)
            }
            DialogProperty(
                modifier = Modifier.padding(horizontal = 16.dp),
                text = stringRes(R.string.ext__meta__label),
            ) {
                JetPrefTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    singleLine = true,
                )
                Validation(showValidationErrors, newLabelValidation)

            }
            DialogProperty(
                modifier = Modifier.padding(horizontal = 16.dp),
                text = stringRes(R.string.ext__meta__authors),
            ) {
                JetPrefTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = newAuthors,
                    onValueChange = { newAuthors = it },
                )
                Validation(showValidationErrors, newAuthorsValidation)
            }
        }
    }
}

@Composable
private fun EditorSheetTextField(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isRequired: Boolean = false,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = true,
    showValidationError: Boolean = false,
    validationResult: ValidationResult? = null,
) {
    Column(modifier = Modifier.padding(vertical = TextFieldVerticalPadding)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = TextFieldVerticalPadding),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
            )
            if (isRequired) {
                Text(
                    modifier = Modifier.padding(start = 2.dp),
                    text = "*",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        JetPrefTextField(
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
        )
        Validation(showValidationError, validationResult)
    }
}
