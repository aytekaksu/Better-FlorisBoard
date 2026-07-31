/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import dev.patrickgold.florisboard.BuildConfig
import java.io.FileDescriptor
import java.io.FileNotFoundException

internal fun clipboardMediaProjectionIsAllowed(
    projection: Array<out String>?,
): Boolean = projection == null ||
    (
        projection.size <= CLIPBOARD_MEDIA_MAX_PROJECTION_COLUMNS &&
            projection.all { it in CLIPBOARD_MEDIA_ALLOWED_PROJECTION }
        )

internal fun clipboardMediaStreamTypeFilterIsAllowed(filter: String): Boolean =
    filter.length <= ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH

/**
 * Shared exact-URI, read-only surface for clipboard media providers.
 */
abstract class ExactClipboardMediaProvider : ContentProvider() {
    protected abstract val mediaAuthority: String
    protected abstract val currentSystemRootsOnly: Boolean

    companion object {
        private val DEFAULT_PROJECTION = arrayOf(
            BaseColumns._ID,
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.Images.Media.ORIENTATION,
        )
    }

    override fun onCreate(): Boolean = context != null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ownedUri = OwnedClipboardMediaUri.parseProviderUri(uri, mediaAuthority) ?: return null
        if (!clipboardMediaProjectionIsAllowed(projection)) return null
        if (!isReadAuthorized(uri)) return null
        val requested = projection?.let { source ->
            Array(source.size) { index -> source[index] }
        } ?: DEFAULT_PROJECTION
        val info = resolveFileInfo(ownedUri) ?: return null
        return MatrixCursor(requested, 1).apply {
            addRow(
                requested.map { column ->
                    when (column) {
                        BaseColumns._ID -> info.id
                        OpenableColumns.DISPLAY_NAME -> info.displayName
                        OpenableColumns.SIZE -> info.size
                        MediaStore.Images.Media.ORIENTATION -> info.orientation
                        else -> null
                    }
                },
            )
        }
    }

    override fun getType(uri: Uri): String? {
        val ownedUri = OwnedClipboardMediaUri.parseProviderUri(uri, mediaAuthority) ?: return null
        if (!isReadAuthorized(uri)) return null
        val prefix = when (ownedUri.type) {
            ItemType.IMAGE -> "image/"
            ItemType.VIDEO -> "video/"
            ItemType.TEXT -> return null
        }
        return resolveFileInfo(ownedUri)
            ?.mimeTypes
            ?.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.lowercase()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun getTypeAnonymous(uri: Uri): String? = null

    override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
        if (!clipboardMediaStreamTypeFilterIsAllowed(mimeTypeFilter)) return null
        val ownedUri = OwnedClipboardMediaUri.parseProviderUri(uri, mediaAuthority) ?: return null
        if (!isReadAuthorized(uri)) return null
        val matching = try {
            resolveFileInfo(ownedUri)
                ?.mimeTypes
                ?.filter { ClipDescription.compareMimeTypes(it, mimeTypeFilter) }
                .orEmpty()
        } catch (_: RuntimeException) {
            return null
        }
        return matching.takeIf(List<String>::isNotEmpty)?.toTypedArray()
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Clipboard media is read-only.")
        val ownedUri = OwnedClipboardMediaUri.parseProviderUri(uri, mediaAuthority)
            ?: throw FileNotFoundException("Clipboard media is unavailable.")
        if (!isReadAuthorized(uri)) {
            throw FileNotFoundException("Clipboard media is unavailable.")
        }
        val file = try {
            if (currentSystemRootsOnly) {
                ClipboardFileStorage.ownedCurrentSystemRootFile(providerContext(), ownedUri)
            } else {
                ClipboardFileStorage.ownedFile(providerContext(), ownedUri)
            }
        } catch (_: ClipboardMediaStorageException) {
            null
        } ?: throw FileNotFoundException("Clipboard media is unavailable.")
        val descriptor = openNoFollow(file.path)
        return try {
            ParcelFileDescriptor.dup(descriptor)
        } catch (_: Exception) {
            throw FileNotFoundException("Clipboard media is unavailable.")
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
    ): AssetFileDescriptor = openTypedAssetFile(uri, mimeTypeFilter, opts, null)

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        signal?.throwIfCanceled()
        if (opts?.isEmpty == false ||
            getStreamTypes(uri, mimeTypeFilter).isNullOrEmpty()
        ) {
            throw FileNotFoundException("Clipboard media is unavailable.")
        }
        signal?.throwIfCanceled()
        val descriptor = openFile(uri, "r")
        return try {
            signal?.throwIfCanceled()
            AssetFileDescriptor(
                descriptor,
                0L,
                AssetFileDescriptor.UNKNOWN_LENGTH,
            )
        } catch (error: Exception) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        throw UnsupportedOperationException("Clipboard media cannot be inserted through the provider.")
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Clipboard media cannot be deleted through the provider.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        throw UnsupportedOperationException("Clipboard media cannot be updated.")
    }

    protected abstract fun isReadAuthorized(providerUri: Uri): Boolean

    private fun providerContext() = context ?: throw IllegalStateException("Clipboard media provider is unavailable.")

    private fun resolveFileInfo(ownedUri: OwnedClipboardMediaUri): ClipboardFileInfo? {
        return try {
            if (currentSystemRootsOnly) {
                ClipboardFileStorage.currentSystemRootFileInfo(providerContext(), ownedUri)
            } else {
                ClipboardFileStorage.fileInfo(providerContext(), ownedUri)
            }
        } catch (_: ClipboardMediaStorageException) {
            null
        }
    }

    private fun openNoFollow(path: String): FileDescriptor {
        val closeOnExec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            OsConstants.O_CLOEXEC
        } else {
            0
        }
        val flags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or closeOnExec
        val descriptor = try {
            Os.open(
                path,
                flags,
                0,
            )
        } catch (_: Exception) {
            throw FileNotFoundException("Clipboard media is unavailable.")
        }
        return try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) {
                throw FileNotFoundException("Clipboard media is unavailable.")
            }
            descriptor
        } catch (error: Exception) {
            runCatching { Os.close(descriptor) }
            if (error is FileNotFoundException) throw error
            throw FileNotFoundException("Clipboard media is unavailable.")
        }
    }
}

/**
 * Private provider used by history, previews, backup, and per-editor grants.
 */
class ClipboardMediaProvider : ExactClipboardMediaProvider() {
    override val mediaAuthority = AUTHORITY
    override val currentSystemRootsOnly = false

    override fun getType(uri: Uri): String? {
        // Before Android 14, a cold MIME lookup may be proxied by the system
        // without preserving the requesting UID. Returning no optional MIME
        // metadata is the only reliable way to keep ungranted URIs private.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return super.getType(uri)
    }

    override fun isReadAuthorized(providerUri: Uri): Boolean {
        val callerUid = Binder.getCallingUid()
        if (callerUid == Process.myUid()) return true
        val providerContext = context ?: return false
        return providerContext.checkUriPermission(
            providerUri,
            Binder.getCallingPid(),
            callerUid,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider.clipboard"
    }
}

/**
 * Android 8's ClipboardService cannot delegate a private-provider URI grant.
 * This separate authority is exported only on API 26-27 and serves exact,
 * current-boot system roots which are still present in the live clipboard.
 * The regular provider stays private so targeted history and commitContent
 * grants keep their normal semantics.
 */
class OreoSystemClipboardMediaProvider : ExactClipboardMediaProvider() {
    override val mediaAuthority = AUTHORITY
    override val currentSystemRootsOnly = true

    private var platformClipboard: ClipboardManager? = null

    override fun onCreate(): Boolean {
        if (!super.onCreate()) return false
        platformClipboard = try {
            context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (_: RuntimeException) {
            null
        }
        return true
    }

    override fun isReadAuthorized(providerUri: Uri): Boolean {
        val clipboard = platformClipboard ?: return false
        return try {
            val clip = clipboard.primaryClip ?: return false
            val itemCount = clip.itemCount.coerceAtMost(MAX_LIVE_CLIP_ITEMS)
            (0 until itemCount).any { index ->
                val item = clip.getItemAt(index)
                item.uri == providerUri || item.intent?.data == providerUri
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        const val AUTHORITY =
            "${BuildConfig.APPLICATION_ID}.provider.clipboard.oreo-system"

        private const val MAX_LIVE_CLIP_ITEMS = 32
    }
}

private val CLIPBOARD_MEDIA_ALLOWED_PROJECTION = setOf(
    BaseColumns._ID,
    OpenableColumns.DISPLAY_NAME,
    OpenableColumns.SIZE,
    MediaStore.Images.Media.ORIENTATION,
)
private const val CLIPBOARD_MEDIA_MAX_PROJECTION_COLUMNS = 4
