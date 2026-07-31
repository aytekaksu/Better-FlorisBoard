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
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileInfo
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.clipboard.provider.normalizeClipboardMediaDisplayName
import dev.patrickgold.florisboard.lib.io.ZipUtils
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile

internal class ClipboardBackupPayloadException(
    val failure: ClipboardBackupPayloadFailure,
) : IOException(failure.name)

internal enum class ClipboardBackupPayloadFailure {
    INVALID_ITEM,
    MEDIA_UNAVAILABLE,
    LIMIT_EXCEEDED,
    GENERATED_PAYLOAD_INVALID,
}

/**
 * Writes a bounded, self-validating clipboard payload from an actor-leased
 * history snapshot.
 */
internal object ClipboardBackupPayload {
    fun write(
        context: Context,
        stagedRoot: FsDir,
        sourcePackageName: String,
        selectedTypes: Set<ItemType>,
        items: List<ClipboardItem>,
        transferBudget: ZipUtils.TransferBudget,
        checkActive: () -> Unit,
        maxIndexBytes: Long = ClipboardRestorePayloadLimits.Default.maxIndexBytes,
    ) {
        require(
            maxIndexBytes in 1L..ClipboardRestorePayloadLimits.Default.maxIndexBytes,
        )
        if (selectedTypes.isEmpty() || items.any { it.type !in selectedTypes }) {
            fail(ClipboardBackupPayloadFailure.INVALID_ITEM)
        }
        val clipboardDirectory = stagedRoot
            .subDir(BackupArchive.CLIPBOARD_ROOT)
            .also { directory ->
                if (!directory.mkdirs() && !directory.isDirectory) {
                    fail(ClipboardBackupPayloadFailure.MEDIA_UNAVAILABLE)
                }
            }
        val canonicalItems = mutableListOf<SerializedClipboardItem>()
        val media = linkedMapOf<OwnedClipboardMediaUri, Pair<ClipboardFileInfo, java.io.File>>()
        var mediaBytes = 0L
        for (item in items) {
            checkActive()
            if (item.type == ItemType.TEXT) {
                canonicalItems += item.serialized(displayName = null)
                continue
            }
            val ownedUri = item.uri
                ?.let { OwnedClipboardMediaUri.parse(it, item.type) }
                ?: fail(ClipboardBackupPayloadFailure.INVALID_ITEM)
            val fileInfo = try {
                ClipboardFileStorage.fileInfo(context, ownedUri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: fail(ClipboardBackupPayloadFailure.MEDIA_UNAVAILABLE)
            val file = try {
                ClipboardFileStorage.ownedFile(context, ownedUri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: fail(ClipboardBackupPayloadFailure.MEDIA_UNAVAILABLE)
            if (fileInfo.size !in 1L..ClipboardFileStorage.MAX_MEDIA_BYTES) {
                fail(ClipboardBackupPayloadFailure.LIMIT_EXCEEDED)
            }
            if (!media.containsKey(ownedUri)) {
                if (fileInfo.size > ClipboardFileStorage.MAX_TOTAL_MEDIA_BYTES - mediaBytes) {
                    fail(ClipboardBackupPayloadFailure.LIMIT_EXCEEDED)
                }
                mediaBytes += fileInfo.size
                media[ownedUri] = fileInfo to file
            }
            canonicalItems += item.serialized(
                uri = ownedUri.uri.toString(),
                mimeTypes = fileInfo.mimeTypes.toList(),
                displayName = normalizeClipboardMediaDisplayName(fileInfo.displayName),
            )
        }

        for (type in RESTORE_TYPES) {
            if (type !in selectedTypes) continue
            writeIndex(
                destination = clipboardDirectory
                    .subFile(type.indexFileName())
                    .toPath(),
                items = canonicalItems.filter { it.type == type },
                checkActive = checkActive,
                maxBytes = maxIndexBytes,
            )
        }

        if (media.isNotEmpty()) {
            val mediaDirectory = clipboardDirectory
                .subDir(ClipboardFileStorage.CLIPBOARD_FILES_PATH)
                .also { directory ->
                    if (!directory.mkdirs() && !directory.isDirectory) {
                        fail(ClipboardBackupPayloadFailure.MEDIA_UNAVAILABLE)
                    }
                }
            for ((ownedUri, source) in media) {
                checkActive()
                val (fileInfo, sourceFile) = source
                val destination = mediaDirectory.subFile(ownedUri.id.toString())
                val partial = mediaDirectory.subFile(".${ownedUri.id}.partial")
                try {
                    ZipUtils.copyFileNoFollow(sourceFile, partial, transferBudget)
                    if (partial.length() != fileInfo.size) {
                        fail(ClipboardBackupPayloadFailure.MEDIA_UNAVAILABLE)
                    }
                    try {
                        Files.move(
                            partial.toPath(),
                            destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(partial.toPath(), destination.toPath())
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    fail(ClipboardBackupPayloadFailure.MEDIA_UNAVAILABLE)
                } finally {
                    runCatching { Files.deleteIfExists(partial.toPath()) }
                }
            }
        }

        when (
            ClipboardRestorePayload.prepare(
                stagedRoot = stagedRoot.toPath(),
                sourcePackageName = sourcePackageName,
                selectedTypes = selectedTypes,
                checkActive = checkActive,
            )
        ) {
            is ClipboardRestorePayloadResult.Valid -> Unit
            is ClipboardRestorePayloadResult.Invalid ->
                fail(ClipboardBackupPayloadFailure.GENERATED_PAYLOAD_INVALID)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeIndex(
        destination: Path,
        items: List<SerializedClipboardItem>,
        checkActive: () -> Unit,
        maxBytes: Long,
    ) {
        val partial = destination.resolveSibling(".${destination.fileName}.partial")
        try {
            FileChannel.open(
                partial,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val output = BoundedOutputStream(
                    delegate = Channels.newOutputStream(channel),
                    maxBytes = maxBytes,
                    checkActive = checkActive,
                )
                JSON.encodeToStream(
                    serializer = ListSerializer(SerializedClipboardItem.serializer()),
                    value = items,
                    stream = output,
                )
                output.flush()
                channel.force(true)
            }
            try {
                Files.move(
                    partial,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial, destination)
            }
        } catch (error: ClipboardBackupPayloadException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fail(ClipboardBackupPayloadFailure.LIMIT_EXCEEDED)
        } finally {
            runCatching { Files.deleteIfExists(partial) }
        }
    }

    private class BoundedOutputStream(
        delegate: OutputStream,
        private val maxBytes: Long,
        private val checkActive: () -> Unit,
    ) : FilterOutputStream(delegate) {
        private var writtenBytes = 0L

        override fun write(value: Int) {
            reserve(1)
            out.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            reserve(length)
            out.write(buffer, offset, length)
        }

        private fun reserve(byteCount: Int) {
            checkActive()
            if (byteCount < 0 || byteCount.toLong() > maxBytes - writtenBytes) {
                fail(ClipboardBackupPayloadFailure.LIMIT_EXCEEDED)
            }
            writtenBytes += byteCount
        }
    }

    private fun ItemType.indexFileName(): String = when (this) {
        ItemType.TEXT -> BackupArchive.CLIPBOARD_TEXT_ITEMS_JSON_NAME
        ItemType.IMAGE -> BackupArchive.CLIPBOARD_IMAGES_JSON_NAME
        ItemType.VIDEO -> BackupArchive.CLIPBOARD_VIDEO_JSON_NAME
    }

    private fun ClipboardItem.serialized(
        uri: String? = this.uri?.toString(),
        mimeTypes: List<String> = this.mimeTypes,
        displayName: String?,
    ) = SerializedClipboardItem(
        id = id,
        type = type,
        text = text,
        uri = uri,
        creationTimestampMs = creationTimestampMs,
        isPinned = isPinned,
        mimeTypes = mimeTypes,
        isSensitive = isSensitive,
        isRemoteDevice = isRemoteDevice,
        displayName = displayName,
    )

    private fun fail(failure: ClipboardBackupPayloadFailure): Nothing =
        throw ClipboardBackupPayloadException(failure)

    private val RESTORE_TYPES = listOf(ItemType.TEXT, ItemType.IMAGE, ItemType.VIDEO)
    private val JSON = Json {
        encodeDefaults = true
    }
}
