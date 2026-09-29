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

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavBackStackEntry
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.OwnedRoutePopResult
import dev.patrickgold.florisboard.app.popOwnedRoute
import dev.patrickgold.florisboard.cacheManager
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangementSave
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import dev.patrickgold.florisboard.lib.io.ZipUtils
import dev.patrickgold.jetpref.datastore.runtime.AndroidAppDataStorage
import dev.patrickgold.jetpref.datastore.runtime.FileBasedStorage
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.FlorisButtonBar
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.rippleClickable
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile
import org.florisboard.lib.kotlin.io.writeJson

object Backup {
    internal fun createShareIntent(context: Context, uri: Uri): Intent {
        return ShareCompat.IntentBuilder(context)
            .setStream(uri)
            .setType("application/zip")
            .createChooserIntent()
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    enum class Destination {
        FILE_SYS,
        SHARE_INTENT;
    }

    class FilesSelector {
        private var selectedComponents by mutableStateOf(DEFAULT_COMPONENTS)

        internal fun isSelected(component: BackupComponent): Boolean = component in selectedComponents

        internal fun toggle(component: BackupComponent) {
            selectedComponents = if (isSelected(component)) {
                selectedComponents - component
            } else {
                selectedComponents + component
            }
        }

        internal fun resetForRestore(availableComponents: Set<BackupComponent>) {
            selectedComponents = DEFAULT_COMPONENTS.intersect(availableComponents)
        }

        internal fun clipboardState(availableComponents: Set<BackupComponent>? = null): ToggleableState {
            val available = clipboardComponents.filter { availableComponents == null || it in availableComponents }
            return when {
                available.none(::isSelected) -> ToggleableState.Off
                available.all(::isSelected) -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
        }

        internal fun setClipboardSelected(
            selected: Boolean,
            availableComponents: Set<BackupComponent>? = null,
        ) {
            val affected = clipboardComponents.filter { availableComponents == null || it in availableComponents }
            selectedComponents = if (selected) selectedComponents + affected else selectedComponents - affected
        }

        fun atLeastOneSelected(): Boolean = selectedComponents.isNotEmpty()

        internal fun snapshot(): Set<BackupComponent> = Collections.unmodifiableSet(selectedComponents.toSet())

        private val clipboardComponents = listOf(
            BackupComponent.CLIPBOARD_TEXT,
            BackupComponent.CLIPBOARD_IMAGES,
            BackupComponent.CLIPBOARD_VIDEOS,
        )

        private companion object {
            val DEFAULT_COMPONENTS = setOf(
                BackupComponent.PREFERENCES,
                BackupComponent.KEYBOARD_EXTENSIONS,
                BackupComponent.THEME_EXTENSIONS,
            )
        }
    }
}

internal fun Set<BackupComponent>.clipboardItemTypes(): Set<ItemType> = buildSet {
    if (BackupComponent.CLIPBOARD_TEXT in this@clipboardItemTypes) add(ItemType.TEXT)
    if (BackupComponent.CLIPBOARD_IMAGES in this@clipboardItemTypes) add(ItemType.IMAGE)
    if (BackupComponent.CLIPBOARD_VIDEOS in this@clipboardItemTypes) add(ItemType.VIDEO)
}

@Composable
fun BackupScreen(routeEntry: NavBackStackEntry) = FlorisScreen {
    title = stringRes(R.string.backup_and_restore__back_up__title)
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current
    val cacheManager by context.cacheManager()
    val model = remember(routeEntry) {
        ViewModelProvider(routeEntry)[BackupExportViewModel::class.java]
    }
    val phase = model.phase
    val isBackupBusy = model.isBusy

    val backUpToFileSystemLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> model.onDocumentResult(context, uri) },
    )

    LaunchedEffect(phase, routeEntry) {
        if (
            phase == BackupExportPhase.DOCUMENT_PICKER_PENDING ||
            phase == BackupExportPhase.SHARE_PENDING ||
            phase == BackupExportPhase.SUCCEEDED ||
            phase == BackupExportPhase.FAILED
        ) {
            val currentEntryFlow = navController.currentBackStackEntryFlow.onStart {
                navController.currentBackStackEntry?.let { emit(it) }
            }
            while (true) {
                combine(routeEntry.lifecycle.currentStateFlow, currentEntryFlow) { ownerState, currentEntry ->
                    ownerState == Lifecycle.State.DESTROYED ||
                        (ownerState == Lifecycle.State.RESUMED && currentEntry === routeEntry)
                }.first { it }
                if (routeEntry.lifecycle.currentState == Lifecycle.State.DESTROYED) return@LaunchedEffect
                if (
                    routeEntry.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    navController.currentBackStackEntry === routeEntry
                ) break
            }
        }
        when (phase) {
            BackupExportPhase.DOCUMENT_PICKER_PENDING -> {
                model.claimDocumentPicker()?.let { fileName ->
                    try {
                        backUpToFileSystemLauncher.launch(fileName)
                    } catch (error: Exception) {
                        model.onDocumentPickerLaunchFailed(error)
                    }
                }
            }
            BackupExportPhase.SHARE_PENDING -> {
                try {
                    val uri = model.claimShareUri() ?: return@LaunchedEffect
                    context.startActivity(Backup.createShareIntent(context, uri))
                    model.onShareLaunched()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    model.onShareLaunchFailed(error)
                }
            }
            BackupExportPhase.SUCCEEDED -> {
                if (navController.popOwnedRoute(routeEntry) == OwnedRoutePopResult.POPPED) {
                    context.showLongToast(R.string.backup_and_restore__back_up__success)
                }
            }
            BackupExportPhase.FAILED -> {
                if (model.failureClass == BackupShareCapacityException::class.java.simpleName) {
                    context.showLongToast(R.string.backup_and_restore__back_up__share_capacity_full)
                } else {
                    context.showLongToast(
                        R.string.backup_and_restore__back_up__failure,
                        "error_message" to (model.failureClass ?: "INTERNAL_FAILURE"),
                    )
                }
                model.acknowledgeFailure()
            }
            else -> Unit
        }
    }

    bottomBar {
        FlorisButtonBar {
            ButtonBarSpacer()
            ButtonBarTextButton(
                onClick = {
                    model.discard()
                    navController.popOwnedRoute(routeEntry)
                },
                text = stringRes(R.string.action__cancel),
                enabled = !isBackupBusy,
            )
            ButtonBarButton(
                onClick = { model.submit(context, cacheManager) },
                text = stringRes(R.string.action__back_up),
                enabled = !isBackupBusy && model.filesSelector.atLeastOneSelected(),
            )
        }
    }

    content {
        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
            title = stringRes(R.string.backup_and_restore__back_up__destination),
        ) {
            RadioListItem(
                onClick = { model.destination = Backup.Destination.FILE_SYS },
                selected = model.destination == Backup.Destination.FILE_SYS,
                text = stringRes(R.string.backup_and_restore__back_up__destination_file_sys),
            )
            RadioListItem(
                onClick = { model.destination = Backup.Destination.SHARE_INTENT },
                selected = model.destination == Backup.Destination.SHARE_INTENT,
                text = stringRes(R.string.backup_and_restore__back_up__destination_share_intent),
            )
        }
        BackupFilesSelector(
            filesSelector = model.filesSelector,
            title = stringRes(R.string.backup_and_restore__back_up__files),
        )
    }
}

internal suspend fun prepareBackupWorkspace(
    context: Context,
    cacheManager: CacheManager,
    selection: Set<BackupComponent>,
): CacheManager.BackupAndRestoreWorkspace {
    // Keep cleanup ownership if cancellation happens during the dispatcher handoff.
    val pendingWorkspace = AtomicReference<CacheManager.BackupAndRestoreWorkspace?>()
    var accepted = false
    try {
        val workspace = withContext(Dispatchers.IO) {
            cacheManager.backupAndRestore.new().also(pendingWorkspace::set)
        }
        withContext(Dispatchers.IO) {
            val operationContext = currentCoroutineContext()
            val archiveLimits = ArchiveLimits.Default
            val transferBudget = ZipUtils.TransferBudget(
                maxEntries = archiveLimits.maxEntries,
                maxBytes = archiveLimits.maxExpandedBytes,
                maxFileBytes = archiveLimits.maxEntryBytes,
                checkCancelled = { operationContext.ensureActive() },
            )
            if (BackupComponent.PREFERENCES in selection) {
                val fileBasedStorage = workspace.inputDir
                    .subDir(AndroidAppDataStorage.JETPREF_DIR_NAME)
                    .subFile("${FlorisPreferenceModel.NAME}.${AndroidAppDataStorage.JETPREF_FILE_EXT}")
                    .let { FileBasedStorage(it.path) }
                QuickActionArrangementSave.withBarrier {
                    FlorisPreferenceStore.export(fileBasedStorage).getOrThrow()
                }
            }
            val workspaceFilesDir = workspace.inputDir.subDir("files")
            ExtensionManager.withStorageMutation {
                if (BackupComponent.KEYBOARD_EXTENSIONS in selection) {
                    ZipUtils.copyDirectoryNoFollow(
                        srcDir = context.filesDir.subDir(ExtensionManager.IME_KEYBOARD_PATH),
                        dstDir = workspaceFilesDir.subDir(ExtensionManager.IME_KEYBOARD_PATH),
                        allowMissing = true,
                        budget = transferBudget,
                    )
                }
                if (BackupComponent.THEME_EXTENSIONS in selection) {
                    ZipUtils.copyDirectoryNoFollow(
                        srcDir = context.filesDir.subDir(ExtensionManager.IME_THEME_PATH),
                        dstDir = workspaceFilesDir.subDir(ExtensionManager.IME_THEME_PATH),
                        allowMissing = true,
                        budget = transferBudget,
                    )
                }
            }

            val selectedTypes = selection.clipboardItemTypes()
            if (selectedTypes.isNotEmpty()) {
                val clipboardManager by context.clipboardManager()
                val snapshot = withContext(NonCancellable) {
                    clipboardManager.acquireBackupSnapshot(selectedTypes)
                }
                try {
                    operationContext.ensureActive()
                    ClipboardBackupPayload.write(
                        context = context,
                        stagedRoot = workspace.inputDir,
                        sourcePackageName = BuildConfig.APPLICATION_ID,
                        selectedTypes = selectedTypes,
                        items = snapshot.items,
                        transferBudget = transferBudget,
                        checkActive = operationContext::ensureActive,
                    )
                } finally {
                    withContext(NonCancellable) {
                        runCatching { snapshot.release() }
                    }
                }
            }
            workspace.metadata = BackupArchive.Metadata(
                packageName = BuildConfig.APPLICATION_ID,
                versionCode = BuildConfig.VERSION_CODE,
                versionName = BuildConfig.VERSION_NAME,
                timestamp = System.currentTimeMillis(),
            )
            workspace.inputDir.subFile(BackupArchive.METADATA_JSON_NAME).writeJson(workspace.metadata)
            workspace.inputDir.subFile(BackupArchive.MANIFEST_JSON_NAME).writeJson(
                BackupArchive.Manifest(
                    formatVersion = BackupArchive.CURRENT_MANIFEST_VERSION,
                    components = BackupComponent.entries
                        .filter { it in selection }
                        .map { it.wireId },
                ),
            )
            operationContext.ensureActive()
            workspace.zipFile = workspace.outputDir.subFile(BackupArchive.defaultFileName(workspace.metadata))
            ZipUtils.zip(
                workspace.inputDir,
                workspace.zipFile,
                ZipUtils.WriteLimits(
                    maxEntries = archiveLimits.maxEntries,
                    maxSourceBytes = archiveLimits.maxExpandedBytes,
                    maxFileBytes = archiveLimits.maxEntryBytes,
                    maxPathBytes = archiveLimits.maxPathBytes,
                    maxPathSegmentBytes = archiveLimits.maxPathSegmentBytes,
                    maxOutputBytes = archiveLimits.maxArchiveBytes,
                    maxFileBytesForPath = archiveLimits::maxEntryBytesFor,
                    checkCancelled = { operationContext.ensureActive() },
                ),
            )
            val snapshot = ArchiveSnapshot(workspace.zipFile.toPath(), workspace.zipFile.length())
            when (val result = BackupArchiveSession.open(snapshot)) {
                is BackupArchiveSessionResult.Valid -> result.session.close()
                is BackupArchiveSessionResult.Invalid -> error("Generated backup failed validation.")
            }
        }
        accepted = true
        pendingWorkspace.set(null)
        return workspace
    } finally {
        if (!accepted) {
            withContext(NonCancellable + Dispatchers.IO) {
                pendingWorkspace.getAndSet(null)?.close()
            }
        }
    }
}

@Composable
internal fun BackupFilesSelector(
    modifier: Modifier = Modifier,
    filesSelector: Backup.FilesSelector,
    title: String,
    availableComponents: Set<BackupComponent>? = null,
) {
    fun isAvailable(component: BackupComponent): Boolean =
        availableComponents == null || component in availableComponents

    FlorisOutlinedBox(
        modifier = modifier.defaultFlorisOutlinedBox(),
        title = title,
    ) {
        CheckboxListItem(
            onClick = { filesSelector.toggle(BackupComponent.PREFERENCES) },
            checked = filesSelector.isSelected(BackupComponent.PREFERENCES),
            text = stringRes(R.string.backup_and_restore__back_up__files_jetpref_datastore),
            enabled = isAvailable(BackupComponent.PREFERENCES),
        )
        CheckboxListItem(
            onClick = { filesSelector.toggle(BackupComponent.KEYBOARD_EXTENSIONS) },
            checked = filesSelector.isSelected(BackupComponent.KEYBOARD_EXTENSIONS),
            text = stringRes(R.string.backup_and_restore__back_up__files_ime_keyboard),
            enabled = isAvailable(BackupComponent.KEYBOARD_EXTENSIONS),
        )
        CheckboxListItem(
            onClick = { filesSelector.toggle(BackupComponent.THEME_EXTENSIONS) },
            checked = filesSelector.isSelected(BackupComponent.THEME_EXTENSIONS),
            text = stringRes(R.string.backup_and_restore__back_up__files_ime_theme),
            enabled = isAvailable(BackupComponent.THEME_EXTENSIONS),
        )

        val clipboardAvailable = availableComponents == null ||
            BackupComponent.CLIPBOARD_TEXT in availableComponents ||
            BackupComponent.CLIPBOARD_IMAGES in availableComponents ||
            BackupComponent.CLIPBOARD_VIDEOS in availableComponents
        TriStateCheckboxListItem(
            onClick = {
                val select = filesSelector.clipboardState(availableComponents) != ToggleableState.On
                filesSelector.setClipboardSelected(select, availableComponents)
            },
            state = filesSelector.clipboardState(availableComponents),
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history),
            enabled = clipboardAvailable,
        )

        CheckboxListItem(
            onClick = { filesSelector.toggle(BackupComponent.CLIPBOARD_TEXT) },
            checked = filesSelector.isSelected(BackupComponent.CLIPBOARD_TEXT),
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_text_items),
            isSecondaryListItem = true,
            enabled = isAvailable(BackupComponent.CLIPBOARD_TEXT),
        )
        CheckboxListItem(
            onClick = { filesSelector.toggle(BackupComponent.CLIPBOARD_IMAGES) },
            checked = filesSelector.isSelected(BackupComponent.CLIPBOARD_IMAGES),
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_image_items),
            isSecondaryListItem = true,
            enabled = isAvailable(BackupComponent.CLIPBOARD_IMAGES),
        )
        CheckboxListItem(
            onClick = { filesSelector.toggle(BackupComponent.CLIPBOARD_VIDEOS) },
            checked = filesSelector.isSelected(BackupComponent.CLIPBOARD_VIDEOS),
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_video_items),
            isSecondaryListItem = true,
            enabled = isAvailable(BackupComponent.CLIPBOARD_VIDEOS),
        )
    }
}

@Composable
internal fun CheckboxListItem(
    onClick: () -> Unit,
    checked: Boolean,
    text: String,
    isSecondaryListItem: Boolean = false,
    enabled: Boolean = true,
) {
    BackupToggleListItem(onClick, text, isSecondaryListItem = isSecondaryListItem, enabled = enabled) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun TriStateCheckboxListItem(
    onClick: () -> Unit,
    state: ToggleableState,
    text: String,
    isSecondaryListItem: Boolean = false,
    enabled: Boolean = true,
) {
    BackupToggleListItem(onClick, text, isSecondaryListItem = isSecondaryListItem, enabled = enabled) {
        TriStateCheckbox(state = state, onClick = null, enabled = enabled)
    }
}

@Composable
private fun BackupToggleListItem(
    onClick: () -> Unit,
    text: String,
    isSecondaryListItem: Boolean,
    enabled: Boolean,
    indicator: @Composable () -> Unit,
) {
    JetPrefListItem(
        modifier = Modifier.rippleClickable(enabled = enabled, onClick = onClick),
        icon = {
            Row {
                if (isSecondaryListItem) {
                    Spacer(modifier = Modifier.width(40.dp))
                }
                indicator()
            }
        },
        text = text,
    )
}

@Composable
internal fun RadioListItem(
    onClick: () -> Unit,
    selected: Boolean,
    text: String,
    secondaryText: String? = null,
) {
    JetPrefListItem(
        modifier = Modifier.rippleClickable(onClick = onClick),
        icon = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
        text = text,
        secondaryText = secondaryText,
    )
}
