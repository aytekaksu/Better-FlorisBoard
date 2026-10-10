/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.writeFromFile
import java.util.concurrent.atomic.AtomicReference

internal enum class BackupExportPhase {
    IDLE,
    PREPARING,
    DOCUMENT_PICKER_PENDING,
    AWAITING_DOCUMENT,
    WRITING,
    SHARE_PENDING,
    SHARING,
    SUCCEEDED,
    FAILED,
}

/** Owns one prepared archive across configuration changes and picker callbacks. */
internal class BackupExportViewModel : ViewModel() {
    val filesSelector = Backup.FilesSelector()

    var destination by mutableStateOf(Backup.Destination.FILE_SYS)

    var phase by mutableStateOf(BackupExportPhase.IDLE)
        private set

    var failureClass by mutableStateOf<String?>(null)
        private set

    private var workspace: CacheManager.BackupAndRestoreWorkspace? = null
    private var pendingShare: BackupShareLeaseStore.Lease? = null
    private var shareStore: BackupShareLeaseStore? = null
    val isBusy: Boolean
        get() = when (phase) {
            BackupExportPhase.PREPARING,
            BackupExportPhase.DOCUMENT_PICKER_PENDING,
            BackupExportPhase.AWAITING_DOCUMENT,
            BackupExportPhase.WRITING,
            BackupExportPhase.SHARE_PENDING,
            BackupExportPhase.SHARING -> true
            else -> false
        }

    fun submit(context: Context, cacheManager: CacheManager) {
        if (phase != BackupExportPhase.IDLE || !filesSelector.atLeastOneSelected()) return
        val selection = filesSelector.snapshot()
        val selectedDestination = destination
        val appContext = context.applicationContext
        retireWorkspace()
        retirePendingShare()
        failureClass = null
        phase = BackupExportPhase.PREPARING
        viewModelScope.launch {
            try {
                val prepared = prepareBackupWorkspace(appContext, cacheManager, selection)
                when (selectedDestination) {
                    Backup.Destination.FILE_SYS -> {
                        workspace = prepared
                        phase = BackupExportPhase.DOCUMENT_PICKER_PENDING
                    }
                    Backup.Destination.SHARE_INTENT -> {
                        val store = BackupShareLeaseStore(appContext)
                        val unclaimed = AtomicReference<BackupShareLeaseStore.Lease?>()
                        try {
                            val lease = withContext(Dispatchers.IO) {
                                store.publish(prepared.zipFile).also(unclaimed::set)
                            }
                            withContext(NonCancellable + Dispatchers.IO) { closeWorkspace(prepared) }
                            currentCoroutineContext().ensureActive()
                            shareStore = store
                            pendingShare = lease
                            unclaimed.set(null)
                            phase = BackupExportPhase.SHARE_PENDING
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                if (!prepared.isClosed()) closeWorkspace(prepared)
                                unclaimed.getAndSet(null)?.let(store::release)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val name = error.javaClass.simpleName
                flogError { "Backup failed: destination=$selectedDestination, failureClass=$name" }
                failureClass = name
                phase = BackupExportPhase.FAILED
            }
        }
    }

    fun claimDocumentPicker(): String? {
        if (phase != BackupExportPhase.DOCUMENT_PICKER_PENDING) return null
        val name = workspace?.zipFile?.name ?: run {
            failSourceUnavailable()
            return null
        }
        phase = BackupExportPhase.AWAITING_DOCUMENT
        return name
    }

    fun onDocumentPickerLaunchFailed(error: Exception) {
        if (phase != BackupExportPhase.AWAITING_DOCUMENT) return
        val name = error.javaClass.simpleName
        flogError { "Backup document picker failed: failureClass=$name" }
        retireWorkspace()
        failureClass = name
        phase = BackupExportPhase.FAILED
    }

    fun onDocumentResult(context: Context, uri: Uri?) {
        if (phase != BackupExportPhase.AWAITING_DOCUMENT) return
        if (uri == null) {
            retireWorkspace()
            phase = BackupExportPhase.IDLE
            return
        }
        val source = workspace ?: run {
            failSourceUnavailable()
            return
        }
        val appContext = context.applicationContext
        phase = BackupExportPhase.WRITING
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var writeFailure: String? = null
            try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.writeFromFile(uri, source.zipFile)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                writeFailure = error.javaClass.simpleName
                flogError { "Failed to save backup: failureClass=${error.javaClass.simpleName}" }
            } finally {
                workspace = null
                withContext(NonCancellable + Dispatchers.IO) { closeWorkspace(source) }
            }
            if (writeFailure == null) {
                phase = BackupExportPhase.SUCCEEDED
            } else {
                failureClass = writeFailure
                phase = BackupExportPhase.FAILED
            }
        }
    }

    suspend fun claimShareUri(): Uri? {
        if (phase != BackupExportPhase.SHARE_PENDING) return null
        val lease = pendingShare ?: run {
            failSourceUnavailable()
            return null
        }
        val store = shareStore ?: run {
            failSourceUnavailable()
            return null
        }
        // A recreated route can retry this if cancellation happens during the IO handoff.
        val uri = withContext(Dispatchers.IO) { store.renewPending(lease) }
        currentCoroutineContext().ensureActive()
        if (phase != BackupExportPhase.SHARE_PENDING || pendingShare !== lease) return null
        phase = BackupExportPhase.SHARING
        return uri
    }

    fun onShareLaunched() {
        if (phase != BackupExportPhase.SHARING) return
        // The launched lease is independent of this route until its fixed expiry.
        pendingShare = null
        shareStore = null
        phase = BackupExportPhase.IDLE
    }

    fun onShareLaunchFailed(error: Exception) {
        if (phase != BackupExportPhase.SHARING && phase != BackupExportPhase.SHARE_PENDING) return
        val name = error.javaClass.simpleName
        flogError { "Backup failed: destination=${Backup.Destination.SHARE_INTENT}, failureClass=$name" }
        retirePendingShare()
        failureClass = name
        phase = BackupExportPhase.FAILED
    }

    fun acknowledgeFailure() {
        if (phase != BackupExportPhase.FAILED) return
        failureClass = null
        phase = BackupExportPhase.IDLE
    }

    fun discard() {
        if (isBusy) return
        retireWorkspace()
        retirePendingShare()
    }

    private fun retireWorkspace() {
        workspace?.requestClose()
        workspace = null
    }

    private fun retirePendingShare() {
        pendingShare?.let { shareStore?.requestRelease(it) }
        pendingShare = null
        shareStore = null
    }

    private fun closeWorkspace(source: CacheManager.BackupAndRestoreWorkspace) {
        try {
            source.close()
        } catch (error: Exception) {
            flogError { "Backup workspace cleanup failed: failureClass=${error.javaClass.simpleName}" }
        } finally {
            if (!source.isClosed()) source.requestClose()
        }
    }

    private fun failSourceUnavailable() {
        failureClass = "SOURCE_UNAVAILABLE"
        phase = BackupExportPhase.FAILED
    }

    override fun onCleared() {
        // WRITING's non-cancellable finally owns its source; do not delete it mid-copy.
        if (phase != BackupExportPhase.WRITING) retireWorkspace()
        retirePendingShare()
        super.onCleared()
    }
}
