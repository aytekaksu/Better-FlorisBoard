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

import android.content.ContentUris
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.cacheManager
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import dev.patrickgold.florisboard.lib.io.FileRegistry
import dev.patrickgold.florisboard.lib.io.ZipUtils
import dev.patrickgold.jetpref.datastore.runtime.AndroidAppDataStorage
import dev.patrickgold.jetpref.datastore.runtime.FileBasedStorage
import dev.patrickgold.jetpref.material.ui.JetPrefListItem
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.writeFromFile
import org.florisboard.lib.compose.FlorisButtonBar
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.rippleClickable
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile
import org.florisboard.lib.kotlin.io.writeJson

object Backup {
    const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider.file"

    internal data class Selection(
        val jetprefDatastore: Boolean,
        val imeKeyboard: Boolean,
        val imeTheme: Boolean,
        val clipboardTextItems: Boolean,
        val clipboardImageItems: Boolean,
        val clipboardVideoItems: Boolean,
    ) {
        fun provideClipboardItems(): Boolean =
            clipboardTextItems || clipboardImageItems || clipboardVideoItems
    }

    enum class Destination {
        FILE_SYS,
        SHARE_INTENT;
    }

    class FilesSelector {
        var jetprefDatastore by mutableStateOf(true)
        var imeKeyboard by mutableStateOf(true)
        var imeTheme by mutableStateOf(true)
        var clipboardTextItems by mutableStateOf(false)
        var clipboardImageItems by mutableStateOf(false)
        var clipboardVideoItems by mutableStateOf(false)

        private var _clipboardData: MutableState<ToggleableState> = mutableStateOf(ToggleableState.Off)
        val clipboardData: State<ToggleableState> = _clipboardData

        fun updateCheckboxState() {
            val newValue = if (
                !clipboardVideoItems && !clipboardImageItems && !clipboardTextItems
            ) {
                ToggleableState.Off
            } else if (
                clipboardVideoItems && clipboardImageItems && clipboardTextItems
            ) {
                ToggleableState.On
            } else {
                ToggleableState.Indeterminate
            }
            _clipboardData.value = newValue
        }

        fun atLeastOneSelected(): Boolean {
            return jetprefDatastore || imeKeyboard || imeTheme || clipboardTextItems || clipboardImageItems || clipboardVideoItems
        }

        internal fun snapshot() = Selection(
            jetprefDatastore = jetprefDatastore,
            imeKeyboard = imeKeyboard,
            imeTheme = imeTheme,
            clipboardTextItems = clipboardTextItems,
            clipboardImageItems = clipboardImageItems,
            clipboardVideoItems = clipboardVideoItems,
        )
    }
}

@Composable
fun BackupScreen() = FlorisScreen {
    title = stringRes(R.string.backup_and_restore__back_up__title)
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current
    val cacheManager by context.cacheManager()
    val scope = rememberCoroutineScope()

    var backupDestination by remember { mutableStateOf(Backup.Destination.FILE_SYS) }
    val backupFilesSelector = remember { Backup.FilesSelector() }
    var backupWorkspace by remember {
        mutableStateOf<CacheManager.BackupAndRestoreWorkspace?>(null)
    }
    var preparedSelection by remember { mutableStateOf<Backup.Selection?>(null) }
    var isBackupBusy by remember { mutableStateOf(false) }

    fun closeBackupWorkspace() {
        backupWorkspace?.close()
        backupWorkspace = null
        preparedSelection = null
    }

    DisposableEffect(Unit) {
        onDispose(::closeBackupWorkspace)
    }

    val backUpToFileSystemLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri ->
            if (uri == null) {
                isBackupBusy = false
                // User can modify checkboxes between cancellation and second
                // trigger, so we make sure to clear out the previous workspace
                closeBackupWorkspace()
                return@rememberLauncherForActivityResult
            }
            runCatching {
                context.contentResolver.writeFromFile(uri, backupWorkspace!!.zipFile)
            }.onSuccess {
                closeBackupWorkspace()
                isBackupBusy = false
                scope.launch {
                    context.showLongToast(R.string.backup_and_restore__back_up__success)
                    navController.popBackStack()
                }
            }.onFailure { error ->
                val failureClass = error.javaClass.simpleName
                flogError { "Failed to save backup: failureClass=$failureClass" }
                closeBackupWorkspace()
                isBackupBusy = false
                scope.launch {
                    context.showLongToast(
                        R.string.backup_and_restore__back_up__failure,
                        "error_message" to failureClass,
                    )
                }
            }
        },
    )

    suspend fun prepareBackupWorkspace(selection: Backup.Selection) {
        val workspace = cacheManager.backupAndRestore.new()
        backupWorkspace = workspace
        preparedSelection = selection
        if (selection.jetprefDatastore) {
            val fileBasedStorage = workspace.inputDir
                .subDir(AndroidAppDataStorage.JETPREF_DIR_NAME)
                .subFile("${FlorisPreferenceModel.NAME}.${AndroidAppDataStorage.JETPREF_FILE_EXT}")
                .let { FileBasedStorage(it.path) }
            FlorisPreferenceStore.export(fileBasedStorage).getOrThrow()
        }
        val workspaceFilesDir = workspace.inputDir.subDir("files")
        if (selection.imeKeyboard) {
            context.filesDir.subDir(ExtensionManager.IME_KEYBOARD_PATH).let { dir ->
                dir.copyRecursively(workspaceFilesDir.subDir(ExtensionManager.IME_KEYBOARD_PATH))
            }
        }
        if (selection.imeTheme) {
            context.filesDir.subDir(ExtensionManager.IME_THEME_PATH).let { dir ->
                dir.copyRecursively(workspaceFilesDir.subDir(ExtensionManager.IME_THEME_PATH))
            }
        }

        if (selection.provideClipboardItems()) {
            val clipboardManager by context.clipboardManager()
            val clipboardHistory = clipboardManager.currentHistory.all
            val clipboardFilesDir = workspace.inputDir.subDir("clipboard")
            clipboardFilesDir.mkdir()

            fun backupClipboardItems(type: ItemType, jsonName: String) {
                val items = clipboardHistory.filter { it.type == type }
                clipboardFilesDir.subFile(jsonName).writeJson(items)
                if (type == ItemType.TEXT) return

                val mediaDir = clipboardFilesDir
                    .subDir(ClipboardFileStorage.CLIPBOARD_FILES_PATH)
                    .also { it.mkdirs() }
                for (item in items) {
                    val id = ContentUris.parseId(item.uri!!)
                    ClipboardFileStorage.getFileForId(context, id).copyTo(mediaDir.subFile("$id"))
                }
            }

            if (selection.clipboardTextItems) {
                backupClipboardItems(ItemType.TEXT, BackupArchive.CLIPBOARD_TEXT_ITEMS_JSON_NAME)
            }
            if (selection.clipboardImageItems) {
                backupClipboardItems(ItemType.IMAGE, BackupArchive.CLIPBOARD_IMAGES_JSON_NAME)
            }
            if (selection.clipboardVideoItems) {
                backupClipboardItems(ItemType.VIDEO, BackupArchive.CLIPBOARD_VIDEO_JSON_NAME)
            }
        }
        workspace.metadata = BackupArchive.Metadata(
            packageName = BuildConfig.APPLICATION_ID,
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            timestamp = System.currentTimeMillis(),
        )
        workspace.inputDir.subFile(BackupArchive.METADATA_JSON_NAME).writeJson(workspace.metadata)
        workspace.zipFile = workspace.outputDir.subFile(BackupArchive.defaultFileName(workspace.metadata))
        ZipUtils.zip(workspace.inputDir, workspace.zipFile)
    }

    suspend fun prepareAndPerformBackup() {
        val selection = backupFilesSelector.snapshot()
        val destination = backupDestination
        runCatching {
            if (backupWorkspace == null ||
                backupWorkspace!!.isClosed() ||
                preparedSelection != selection
            ) {
                closeBackupWorkspace()
                prepareBackupWorkspace(selection)
            }
            when (destination) {
                Backup.Destination.FILE_SYS -> {
                    backUpToFileSystemLauncher.launch(backupWorkspace!!.zipFile.name)
                }

                Backup.Destination.SHARE_INTENT -> {
                    val uri =
                        FileProvider.getUriForFile(context, Backup.FILE_PROVIDER_AUTHORITY, backupWorkspace!!.zipFile)
                    val shareIntent = ShareCompat.IntentBuilder(context)
                        .setStream(uri)
                        .setType(FileRegistry.BackupArchive.mediaType)
                        .createChooserIntent()
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(shareIntent)
                    // Keep the shared file alive for FileProvider, but never
                    // reuse a snapshot after app data may have changed.
                    preparedSelection = null
                    isBackupBusy = false
                }
            }
        }.onFailure { error ->
            val failureClass = error.javaClass.simpleName
            flogError { "Backup failed: destination=$destination, failureClass=$failureClass" }
            closeBackupWorkspace()
            isBackupBusy = false
            context.showLongToast(
                R.string.backup_and_restore__back_up__failure,
                "error_message" to failureClass,
            )
        }
    }

    bottomBar {
        FlorisButtonBar {
            ButtonBarSpacer()
            ButtonBarTextButton(
                onClick = {
                    closeBackupWorkspace()
                    navController.popBackStack()
                },
                text = stringRes(R.string.action__cancel),
                enabled = !isBackupBusy,
            )
            ButtonBarButton(
                onClick = {
                    if (isBackupBusy) return@ButtonBarButton
                    isBackupBusy = true
                    scope.launch { prepareAndPerformBackup() }
                },
                text = stringRes(R.string.action__back_up),
                enabled = !isBackupBusy && backupFilesSelector.atLeastOneSelected(),
            )
        }
    }

    content {
        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
            title = stringRes(R.string.backup_and_restore__back_up__destination),
        ) {
            RadioListItem(
                onClick = {
                    backupDestination = Backup.Destination.FILE_SYS
                },
                selected = backupDestination == Backup.Destination.FILE_SYS,
                text = stringRes(R.string.backup_and_restore__back_up__destination_file_sys),
            )
            RadioListItem(
                onClick = {
                    backupDestination = Backup.Destination.SHARE_INTENT
                },
                selected = backupDestination == Backup.Destination.SHARE_INTENT,
                text = stringRes(R.string.backup_and_restore__back_up__destination_share_intent),
            )
        }
        BackupFilesSelector(
            filesSelector = backupFilesSelector,
            title = stringRes(R.string.backup_and_restore__back_up__files),
        )
    }
}

@Composable
internal fun BackupFilesSelector(
    modifier: Modifier = Modifier,
    filesSelector: Backup.FilesSelector,
    title: String,
) {
    FlorisOutlinedBox(
        modifier = modifier.defaultFlorisOutlinedBox(),
        title = title,
    ) {
        CheckboxListItem(
            onClick = { filesSelector.jetprefDatastore = !filesSelector.jetprefDatastore },
            checked = filesSelector.jetprefDatastore,
            text = stringRes(R.string.backup_and_restore__back_up__files_jetpref_datastore),
        )
        CheckboxListItem(
            onClick = { filesSelector.imeKeyboard = !filesSelector.imeKeyboard },
            checked = filesSelector.imeKeyboard,
            text = stringRes(R.string.backup_and_restore__back_up__files_ime_keyboard),
        )
        CheckboxListItem(
            onClick = { filesSelector.imeTheme = !filesSelector.imeTheme },
            checked = filesSelector.imeTheme,
            text = stringRes(R.string.backup_and_restore__back_up__files_ime_theme),
        )

        TriStateCheckboxListItem(
            onClick = {
                if (
                    filesSelector.clipboardData.value == ToggleableState.Off ||
                    filesSelector.clipboardData.value == ToggleableState.Indeterminate
                ) {
                    filesSelector.clipboardImageItems = true
                    filesSelector.clipboardVideoItems = true
                    filesSelector.clipboardTextItems = true
                } else {
                    filesSelector.clipboardImageItems = false
                    filesSelector.clipboardVideoItems = false
                    filesSelector.clipboardTextItems = false
                }
                filesSelector.updateCheckboxState()
            },
            state = filesSelector.clipboardData.value,
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history),
        )


        CheckboxListItem(
            onClick = {
                filesSelector.clipboardTextItems = !filesSelector.clipboardTextItems
                filesSelector.updateCheckboxState()
            },
            checked = filesSelector.clipboardTextItems,
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_text_items),
            isSecondaryListItem = true,
        )
        CheckboxListItem(
            onClick = {
                filesSelector.clipboardImageItems = !filesSelector.clipboardImageItems
                filesSelector.updateCheckboxState()
            },
            checked = filesSelector.clipboardImageItems,
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_image_items),
            isSecondaryListItem = true,
        )
        CheckboxListItem(
            onClick = {
                filesSelector.clipboardVideoItems = !filesSelector.clipboardVideoItems
                filesSelector.updateCheckboxState()
            },
            checked = filesSelector.clipboardVideoItems,
            text = stringRes(R.string.backup_and_restore__back_up__files_clipboard_history__clipboard_video_items),
            isSecondaryListItem = true,
        )

    }
}

@Composable
internal fun CheckboxListItem(
    onClick: () -> Unit,
    checked: Boolean,
    text: String,
    isSecondaryListItem: Boolean = false
) {
    JetPrefListItem(
        modifier = Modifier.rippleClickable(onClick = onClick),
        icon = {
            Row {
                if (isSecondaryListItem) {
                    Spacer(modifier = Modifier.width(40.dp))
                }
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                )
            }
        },
        text = text,
    )
}

@Composable
internal fun TriStateCheckboxListItem(
    onClick: () -> Unit,
    state: ToggleableState,
    text: String,
    isSecondaryListItem: Boolean = false,
) {
    JetPrefListItem(
        modifier = Modifier.rippleClickable(onClick = onClick),
        icon = {
            Row {
                if (isSecondaryListItem) {
                    Spacer(modifier = Modifier.width(40.dp))
                }
                TriStateCheckbox(
                    state = state,
                    onClick = null,
                )
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
