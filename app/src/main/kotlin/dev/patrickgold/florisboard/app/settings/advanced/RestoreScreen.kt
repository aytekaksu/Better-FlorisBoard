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

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.importWithLegacyMigrations
import dev.patrickgold.florisboard.app.runOwnedNavigationAction
import dev.patrickgold.florisboard.app.runOwnedNavigationActionWhenResumed
import dev.patrickgold.florisboard.cacheManager
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.runtime.AndroidAppDataStorage
import dev.patrickgold.jetpref.datastore.runtime.FileBasedStorage
import dev.patrickgold.jetpref.datastore.runtime.ImportStrategy
import dev.patrickgold.jetpref.datastore.runtime.LoadStrategy
import dev.patrickgold.jetpref.datastore.runtime.PersistStrategy
import dev.patrickgold.jetpref.datastore.ui.Preference
import java.io.File
import java.nio.file.Path
import java.text.DateFormat
import java.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.FlorisButtonBar
import org.florisboard.lib.compose.FlorisCardDefaults
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.FlorisOutlinedButton
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile

object Restore {
    const val PACKAGE_NAME = "dev.patrickgold.florisboard"
    const val BACKUP_ARCHIVE_FILE_NAME = "backup.zip"

    internal fun isSameVendorPackage(packageName: String): Boolean = packageName in SAME_VENDOR_PACKAGE_NAMES

    private val SAME_VENDOR_PACKAGE_NAMES = setOf(
        PACKAGE_NAME,
        "$PACKAGE_NAME.debug",
        "$PACKAGE_NAME.beta",
        "$PACKAGE_NAME.bench",
    )
}

private enum class RestoreFlowFailure {
    SNAPSHOT_REJECTED,
    ARCHIVE_REJECTED,
    PLAN_REJECTED,
    STAGING_REJECTED,
    CLIPBOARD_PAYLOAD_REJECTED,
    CLIPBOARD_COMMIT_FAILED,
    CLIPBOARD_ROLLBACK_FAILED,
    RESTORE_ROLLBACK_FAILED,
    INTERNAL_FAILURE,
}

private class RestoreFlowException(val failure: RestoreFlowFailure) : RuntimeException()

private fun Exception.safeRestoreFailureName(): String = when (this) {
    is RestoreFlowException -> failure.name
    is RestoreTransactionRollbackException -> RestoreFlowFailure.RESTORE_ROLLBACK_FAILED.name
    else -> RestoreFlowFailure.INTERNAL_FAILURE.name
}

internal class RestoreScreenViewModel : ViewModel() {
    val filesSelector = Backup.FilesSelector()
    var importStrategy by mutableStateOf(ImportStrategy.Merge)
    var workspace by mutableStateOf<CacheManager.BackupAndRestoreWorkspace?>(null)
    var isBusy by mutableStateOf(false)

    val scope: CoroutineScope
        get() = viewModelScope

    override fun onCleared() {
        workspace?.requestClose()
        workspace = null
    }
}

internal object RestorePreferencePreflight {
    private val store by lazy {
        jetprefDataStoreOf(FlorisPreferenceModel::class)
    }
    private val lock = Mutex()

    suspend fun prepare(
        stagedSource: Path,
        snapshot: Path,
        canonicalDestination: Path,
        strategy: ImportStrategy,
        sourceVersionCode: Int?,
        sourceVersionName: String?,
    ) = lock.withLock {
        store.init(
            loadStrategy = LoadStrategy.UseReader(
                FileBasedStorage(snapshot.toFile().path),
            ),
            persistStrategy = PersistStrategy.Disabled,
        ).getOrThrow()
        store.importWithLegacyMigrations(
            strategy = strategy,
            reader = FileBasedStorage(stagedSource.toFile().path),
            sourceVersionCode = sourceVersionCode,
            sourceVersionName = sourceVersionName,
        ).getOrThrow()
        store.export(
            FileBasedStorage(canonicalDestination.toFile().path),
        ).getOrThrow()
    }
}

@Composable
fun RestoreScreen(routeEntry: NavBackStackEntry) = FlorisScreen {
    title = stringRes(R.string.backup_and_restore__restore__title)
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current
    val cacheManager by context.cacheManager()

    val model = remember(routeEntry) {
        ViewModelProvider(routeEntry)[RestoreScreenViewModel::class.java]
    }
    val restoreFilesSelector = model.filesSelector
    var importStrategy by model::importStrategy
    val restoreScope = model.scope
    var restoreWorkspace by model::workspace
    var isRestoreBusy by model::isBusy
    navigationIconVisible = !isRestoreBusy

    suspend fun closeRestoreWorkspace(
        workspace: CacheManager.BackupAndRestoreWorkspace? = restoreWorkspace,
        stagedRestore: StagedRestore? = null,
    ) {
        if (workspace == null) return
        if (restoreWorkspace === workspace) {
            restoreWorkspace = null
        }
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { stagedRestore?.close() }
            runCatching { workspace.close() }
            runCatching { stagedRestore?.close() }
            if (!workspace.isClosed() || stagedRestore?.isClosed == false) {
                workspace.requestClose(stagedRestore)
            }
        }
    }

    suspend fun prepareRestoreWorkspace(uri: Uri): CacheManager.BackupAndRestoreWorkspace {
        closeRestoreWorkspace()
        val workspace = cacheManager.backupAndRestore.new()
        var accepted = false
        try {
            val destination = workspace.inputDir.subFile(Restore.BACKUP_ARCHIVE_FILE_NAME)
            val snapshot = when (
                val result = BackupArchiveSnapshot.capture(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    workspaceDir = workspace.dir.toPath(),
                    destination = destination.toPath(),
                )
            ) {
                is ArchiveSnapshotResult.Valid -> result.snapshot
                is ArchiveSnapshotResult.Invalid -> {
                    throw RestoreFlowException(RestoreFlowFailure.SNAPSHOT_REJECTED)
                }
            }
            workspace.zipFile = destination
            val session = when (val result = BackupArchiveSession.open(snapshot)) {
                is BackupArchiveSessionResult.Valid -> result.session
                is BackupArchiveSessionResult.Invalid -> {
                    throw RestoreFlowException(RestoreFlowFailure.ARCHIVE_REJECTED)
                }
            }
            workspace.replaceRestoreSession(session)
            workspace.metadata = session.archive.metadata
            workspace.restoreWarningId = when {
                !Restore.isSameVendorPackage(workspace.metadata.packageName) -> {
                    R.string.backup_and_restore__restore__metadata_warn_different_vendor
                }
                workspace.metadata.versionCode != BuildConfig.VERSION_CODE -> {
                    R.string.backup_and_restore__restore__metadata_warn_different_version
                }
                else -> null
            }
            restoreFilesSelector.resetForRestore(session.archive.availableComponents)
            accepted = true
            return workspace
        } finally {
            if (!accepted) {
                closeRestoreWorkspace(workspace)
            }
        }
    }

    val restoreDataFromFileSystemLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri == null) {
                isRestoreBusy = false
                return@rememberLauncherForActivityResult
            }
            restoreScope.launch {
                try {
                    restoreWorkspace = prepareRestoreWorkspace(uri)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val failureClass = error.safeRestoreFailureName()
                    context.showLongToast(
                        R.string.backup_and_restore__restore__failure,
                        "error_message" to failureClass,
                    )
                } finally {
                    isRestoreBusy = false
                }
            }
        },
    )

    suspend fun performRestore(
        stagedRoot: File,
        metadata: BackupArchive.Metadata,
        selection: Backup.Selection,
        strategy: ImportStrategy,
    ) {
        val shouldReset = strategy == ImportStrategy.Erase
        val preparedClipboard = withContext(Dispatchers.IO) {
            val clipboardTypes = buildSet {
                if (selection.clipboardTextItems) add(ItemType.TEXT)
                if (selection.clipboardImageItems) add(ItemType.IMAGE)
                if (selection.clipboardVideoItems) add(ItemType.VIDEO)
            }
            val clipboardPayload = if (clipboardTypes.isNotEmpty()) {
                val operationContext = currentCoroutineContext()
                when (
                    val result = ClipboardRestorePayload.prepare(
                        stagedRoot = stagedRoot.toPath(),
                        sourcePackageName = metadata.packageName,
                        selectedTypes = clipboardTypes,
                        checkActive = operationContext::ensureActive,
                    )
                ) {
                    is ClipboardRestorePayloadResult.Valid -> result.payload
                    is ClipboardRestorePayloadResult.Invalid -> {
                        throw RestoreFlowException(RestoreFlowFailure.CLIPBOARD_PAYLOAD_REJECTED)
                    }
                }
            } else {
                null
            }
            clipboardPayload
        }

        suspend fun commitClipboard() {
            val payload = preparedClipboard ?: return
            when (
                val result = ClipboardRestoreCommit.commit(
                    context = context,
                    clipboardManager = context.clipboardManager().value,
                    payload = payload,
                    replaceSelected = shouldReset,
                )
            ) {
                ClipboardRestoreCommitResult.Committed -> Unit
                is ClipboardRestoreCommitResult.Failed -> {
                    val failure = if (result.failure == ClipboardRestoreCommitFailure.MEDIA_CLEANUP_FAILED) {
                        RestoreFlowFailure.CLIPBOARD_ROLLBACK_FAILED
                    } else {
                        RestoreFlowFailure.CLIPBOARD_COMMIT_FAILED
                    }
                    throw RestoreFlowException(failure)
                }
            }
        }

        val preferenceSource = stagedRoot
            .subDir(AndroidAppDataStorage.JETPREF_DIR_NAME)
            .subFile("${FlorisPreferenceModel.NAME}.${AndroidAppDataStorage.JETPREF_FILE_EXT}")
            .toPath()
        val preferenceTransaction = if (selection.jetprefDatastore) {
            RestorePreferenceTransaction(
                stagedSource = preferenceSource,
                snapshot = { destination ->
                    FlorisPreferenceStore.export(
                        FileBasedStorage(destination.toFile().path),
                    ).getOrThrow()
                },
                prepare = { stagedSource, snapshot, canonicalDestination ->
                    RestorePreferencePreflight.prepare(
                        stagedSource = stagedSource,
                        snapshot = snapshot,
                        canonicalDestination = canonicalDestination,
                        strategy = strategy,
                        sourceVersionCode = metadata.versionCode,
                        sourceVersionName = metadata.versionName,
                    )
                },
                apply = { canonicalSource ->
                    FlorisPreferenceStore.import(
                        strategy = ImportStrategy.Erase,
                        reader = FileBasedStorage(canonicalSource.toFile().path),
                    ).getOrThrow()
                },
                rollback = { snapshot ->
                    FlorisPreferenceStore.import(
                        strategy = ImportStrategy.Erase,
                        reader = FileBasedStorage(snapshot.toFile().path),
                    ).getOrThrow()
                },
            )
        } else {
            null
        }
        val stagedFilesRoot = stagedRoot.toPath().resolve("files")
        val liveFilesRoot = context.filesDir.toPath()
        val directoryTransactions = buildList {
            if (selection.imeKeyboard) {
                add(
                    RestoreDirectoryTransaction(
                        stagedSource = stagedFilesRoot.resolve(ExtensionManager.IME_KEYBOARD_PATH),
                        liveTarget = liveFilesRoot.resolve(ExtensionManager.IME_KEYBOARD_PATH),
                    ),
                )
            }
            if (selection.imeTheme) {
                add(
                    RestoreDirectoryTransaction(
                        stagedSource = stagedFilesRoot.resolve(ExtensionManager.IME_THEME_PATH),
                        liveTarget = liveFilesRoot.resolve(ExtensionManager.IME_THEME_PATH),
                    ),
                )
            }
        }

        withContext(Dispatchers.IO) {
            if (preferenceTransaction == null && directoryTransactions.isEmpty()) {
                commitClipboard()
            } else {
                RestoreTransaction.execute(
                    scratchParent = requireNotNull(stagedRoot.toPath().parent),
                    eraseExisting = shouldReset,
                    preferences = preferenceTransaction,
                    directories = directoryTransactions,
                    finalCommit = { commitClipboard() },
                )
            }
        }
    }

    bottomBar {
        FlorisButtonBar {
            ButtonBarSpacer()
            ButtonBarTextButton(
                onClick = {
                    restoreWorkspace?.requestClose()
                    restoreWorkspace = null
                    navController.runOwnedNavigationAction(routeEntry) {
                        navigateUp()
                    }
                },
                text = stringRes(R.string.action__cancel),
                enabled = !isRestoreBusy,
            )
            ButtonBarButton(
                onClick = {
                    if (isRestoreBusy) return@ButtonBarButton
                    isRestoreBusy = true
                    val selection = restoreFilesSelector.snapshot()
                    val strategy = importStrategy
                    val workspace = restoreWorkspace
                    if (restoreWorkspace === workspace) {
                        restoreWorkspace = null
                    }
                    restoreScope.launch {
                        var stagedRestore: StagedRestore? = null
                        var restoreSucceeded = false
                        try {
                            val session = workspace?.restoreSession
                                ?: throw RestoreFlowException(RestoreFlowFailure.ARCHIVE_REJECTED)
                            val plan = when (
                                val result = session.createPlan(
                                    RestoreRequest(
                                        mode = if (strategy == ImportStrategy.Erase) {
                                            RestoreMode.REPLACE_SELECTED
                                        } else {
                                            RestoreMode.MERGE
                                        },
                                        selectedComponents = selection.components(),
                                    ),
                                )
                            ) {
                                is RestorePlanResult.Valid -> result.plan
                                is RestorePlanResult.Invalid -> {
                                    throw RestoreFlowException(RestoreFlowFailure.PLAN_REJECTED)
                                }
                            }
                            stagedRestore = when (
                                val result = BackupArchiveStager.stage(
                                    session = session,
                                    plan = plan,
                                    stagingParent = workspace.outputDir.toPath(),
                                )
                            ) {
                                is BackupArchiveStagingResult.Valid -> result.stagedRestore
                                is BackupArchiveStagingResult.Invalid -> {
                                    throw RestoreFlowException(RestoreFlowFailure.STAGING_REJECTED)
                                }
                            }
                            performRestore(
                                stagedRoot = stagedRestore.root.toFile(),
                                metadata = workspace.metadata,
                                selection = selection,
                                strategy = strategy,
                            )
                            restoreSucceeded = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val failureClass = e.safeRestoreFailureName()
                            flogError { "Restore failed: failureClass=$failureClass" }
                            context.showLongToast(
                                R.string.backup_and_restore__restore__failure,
                                "error_message" to failureClass,
                            )
                        } finally {
                            closeRestoreWorkspace(workspace, stagedRestore)
                            isRestoreBusy = false
                        }
                        if (restoreSucceeded) {
                            currentCoroutineContext().ensureActive()
                            context.showLongToast(R.string.backup_and_restore__restore__success)
                            navController.runOwnedNavigationActionWhenResumed(routeEntry) {
                                navigateUp()
                            }
                        }
                    }
                },
                text = stringRes(R.string.action__restore),
                enabled = !isRestoreBusy &&
                    restoreFilesSelector.atLeastOneSelected() &&
                    restoreWorkspace?.restoreSession?.archive?.availableComponents?.containsAll(
                        restoreFilesSelector.snapshot().components(),
                    ) == true,
            )
        }
    }

    content {
        BackHandler(enabled = isRestoreBusy) { }
        FlorisOutlinedBox(
            modifier = Modifier.defaultFlorisOutlinedBox(),
            title = stringRes(R.string.backup_and_restore__restore__mode),
        ) {
            RadioListItem(
                onClick = {
                    importStrategy = ImportStrategy.Merge
                },
                selected = importStrategy == ImportStrategy.Merge,
                text = stringRes(R.string.backup_and_restore__restore__mode_merge),
            )
            RadioListItem(
                onClick = {
                    importStrategy = ImportStrategy.Erase
                },
                selected = importStrategy == ImportStrategy.Erase,
                text = stringRes(R.string.backup_and_restore__restore__mode_erase_and_overwrite),
            )
        }
        FlorisOutlinedButton(
            onClick = {
                if (isRestoreBusy) return@FlorisOutlinedButton
                isRestoreBusy = true
                runCatching {
                    restoreDataFromFileSystemLauncher.launch("*/*")
                }.onFailure {
                    isRestoreBusy = false
                    restoreScope.launch {
                        context.showLongToast(
                            R.string.backup_and_restore__restore__failure,
                            "error_message" to "INTERNAL_FAILURE",
                        )
                    }
                }
            },
            modifier = Modifier
                .padding(vertical = 16.dp)
                .align(Alignment.CenterHorizontally),
            text = stringRes(R.string.action__select_file),
            enabled = !isRestoreBusy,
        )
        val workspace = restoreWorkspace
        if (workspace == null) {
            Text(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 16.dp),
                text = stringRes(R.string.state__no_file_selected),
                fontStyle = FontStyle.Italic,
            )
        } else {
            FlorisOutlinedBox(
                modifier = Modifier.defaultFlorisOutlinedBox(),
                title = stringRes(R.string.backup_and_restore__restore__metadata),
            ) {
                Preference(
                    icon = Icons.Default.Code,
                    title = workspace.metadata.packageName,
                )
                Preference(
                    icon = Icons.Outlined.Info,
                    title = "${workspace.metadata.versionName} (${workspace.metadata.versionCode})",
                )
                Preference(
                    icon = Icons.Default.Schedule,
                    title = remember(workspace.metadata.timestamp) {
                        val formatter = DateFormat.getDateTimeInstance()
                        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        calendar.timeInMillis = workspace.metadata.timestamp
                        formatter.format(calendar.time)
                    },
                )
                if (workspace.restoreWarningId != null) {
                    Column(modifier = Modifier.padding(FlorisCardDefaults.ContentPadding)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(9.dp)
                                .padding(bottom = 8.dp)
                                .background(LocalContentColor.current)
                        )
                        Text(
                            text = stringRes(workspace.restoreWarningId!!),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
            BackupFilesSelector(
                filesSelector = restoreFilesSelector,
                title = stringRes(R.string.backup_and_restore__restore__files),
                availableComponents = workspace.restoreSession?.archive?.availableComponents.orEmpty(),
            )
        }
    }
}
