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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.writeFromFile

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
        failureClass = null
        phase = BackupExportPhase.PREPARING
        viewModelScope.launch {
            try {
                val prepared = prepareBackupWorkspace(appContext, cacheManager, selection)
                workspace = prepared
                phase = when (selectedDestination) {
                    Backup.Destination.FILE_SYS -> BackupExportPhase.DOCUMENT_PICKER_PENDING
                    Backup.Destination.SHARE_INTENT -> BackupExportPhase.SHARE_PENDING
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
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        source.close()
                    } catch (error: Exception) {
                        flogError { "Backup workspace cleanup failed: failureClass=${error.javaClass.simpleName}" }
                    } finally {
                        if (!source.isClosed()) source.requestClose()
                    }
                }
            }
            if (writeFailure == null) {
                phase = BackupExportPhase.SUCCEEDED
            } else {
                failureClass = writeFailure
                phase = BackupExportPhase.FAILED
            }
        }
    }

    fun claimShareWorkspace(): CacheManager.BackupAndRestoreWorkspace? {
        if (phase != BackupExportPhase.SHARE_PENDING) return null
        val source = workspace ?: run {
            failSourceUnavailable()
            return null
        }
        phase = BackupExportPhase.SHARING
        return source
    }

    fun onShareLaunched() {
        if (phase != BackupExportPhase.SHARING) return
        // Keep the FileProvider source until this route is closed or another backup begins.
        // The chooser does not acknowledge when its recipient has finished opening the URI.
        phase = BackupExportPhase.IDLE
    }

    fun onShareLaunchFailed(error: Exception) {
        if (phase != BackupExportPhase.SHARING) return
        val name = error.javaClass.simpleName
        flogError { "Backup failed: destination=${Backup.Destination.SHARE_INTENT}, failureClass=$name" }
        retireWorkspace()
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
    }

    private fun retireWorkspace() {
        workspace?.requestClose()
        workspace = null
    }

    private fun failSourceUnavailable() {
        failureClass = "SOURCE_UNAVAILABLE"
        phase = BackupExportPhase.FAILED
    }

    override fun onCleared() {
        // WRITING's non-cancellable finally owns its source; do not delete it mid-copy.
        if (phase != BackupExportPhase.WRITING) retireWorkspace()
        super.onCleared()
    }
}
