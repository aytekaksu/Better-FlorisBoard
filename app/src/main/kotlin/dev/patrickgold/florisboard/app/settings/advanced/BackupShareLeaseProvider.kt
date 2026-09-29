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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.io.FileNotFoundException

/** Exact-URI, read-only access to active backup share leases. */
class BackupShareLeaseProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (selection != null || selectionArgs != null || sortOrder != null) return null
        val columns = projection?.let { requested ->
            if (requested.size > DEFAULT_PROJECTION.size || requested.any { it !in ALLOWED_COLUMNS }) return null
            Array(requested.size) { index -> requested[index] }
        } ?: DEFAULT_PROJECTION
        if (!isReadAuthorized(uri)) return null
        val resolved = resolve(uri) ?: return null
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> resolved.displayName
                    OpenableColumns.SIZE -> resolved.size
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri): String? {
        // Before Android 14, a cold MIME query may be proxied without the caller UID.
        // A constant supports typed-open recipients without revealing lease existence.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return MIME_TYPE
        return if (isReadAuthorized(uri) && resolve(uri) != null) MIME_TYPE else null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun getTypeAnonymous(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || !isReadAuthorized(uri)) throw unavailable()
        val resolved = resolve(uri) ?: throw unavailable()
        val descriptor = openNoFollow(resolved.path.toString(), resolved.size)
        return try {
            ParcelFileDescriptor.dup(descriptor)
        } catch (_: Exception) {
            throw unavailable()
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Backup shares cannot be inserted through the provider.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Backup shares cannot be deleted through the provider.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Backup shares cannot be updated through the provider.")

    private fun resolve(uri: Uri): BackupShareLeaseStore.Resolved? {
        val providerContext = context ?: return null
        return try {
            BackupShareLeaseStore(providerContext).resolve(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun isReadAuthorized(uri: Uri): Boolean {
        val callerUid = Binder.getCallingUid()
        if (callerUid == Process.myUid()) return true
        val providerContext = context ?: return false
        return try {
            providerContext.checkUriPermission(
                uri,
                Binder.getCallingPid(),
                callerUid,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun openNoFollow(path: String, expectedSize: Long): FileDescriptor {
        val closeOnExec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            OsConstants.O_CLOEXEC
        } else {
            0
        }
        val descriptor = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExec, 0)
        } catch (_: Exception) {
            throw unavailable()
        }
        return try {
            val status = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(status.st_mode) || status.st_size != expectedSize) {
                throw unavailable()
            }
            descriptor
        } catch (_: Exception) {
            runCatching { Os.close(descriptor) }
            throw unavailable()
        }
    }

    private fun unavailable() = FileNotFoundException("Backup share is unavailable.")

    companion object {
        private const val MIME_TYPE = "application/zip"
        private val DEFAULT_PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        private val ALLOWED_COLUMNS = DEFAULT_PROJECTION.toSet()
    }
}
