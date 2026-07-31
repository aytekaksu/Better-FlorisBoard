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

package dev.patrickgold.florisboard.ime.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaImporter
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.InstalledClipboardMedia
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardShareOperationToken
import dev.patrickgold.florisboard.ime.clipboard.provider.StagedClipboardMedia
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal data class ClipboardSharePreviewPlan(
    val sampleSize: Int,
    val width: Int,
    val height: Int,
)

internal class ClipboardSharePreview(
    val bitmap: Bitmap?,
    val mimeType: String?,
)

internal fun clipboardShareMimeTypes(
    decodedMimeType: String?,
    declaredMimeType: String?,
    sourceMimeType: String? = null,
): List<String> {
    val mimeTypes = sequenceOf(decodedMimeType, sourceMimeType, declaredMimeType)
        .mapNotNull { candidate ->
            candidate
                ?.trim()
                ?.lowercase()
                ?.takeIf { normalized ->
                    normalized.length <= MAX_SHARE_MIME_TYPE_LENGTH &&
                        '*' !in normalized &&
                        normalized.startsWith("image/") &&
                        SHARE_MIME_TYPE.matches(normalized)
                }
        }
        .distinct()
        .toList()
    return mimeTypes.ifEmpty { listOf(DEFAULT_SHARE_MIME_TYPE) }
}

internal fun planClipboardSharePreview(
    sourceWidth: Int,
    sourceHeight: Int,
): ClipboardSharePreviewPlan? {
    if (sourceWidth !in 1..MAX_SHARE_PREVIEW_SOURCE_EDGE ||
        sourceHeight !in 1..MAX_SHARE_PREVIEW_SOURCE_EDGE ||
        sourceWidth.toLong() * sourceHeight > MAX_SHARE_PREVIEW_SOURCE_PIXELS
    ) {
        return null
    }
    var sampleSize = 1
    while (
        ceilDiv(sourceWidth, sampleSize) > MAX_SHARE_PREVIEW_DECODE_EDGE ||
        ceilDiv(sourceHeight, sampleSize) > MAX_SHARE_PREVIEW_DECODE_EDGE
    ) {
        sampleSize = sampleSize shl 1
    }
    val scale = minOf(
        1.0,
        MAX_SHARE_PREVIEW_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight),
    )
    return ClipboardSharePreviewPlan(
        sampleSize = sampleSize,
        width = (sourceWidth * scale).roundToInt().coerceAtLeast(1),
        height = (sourceHeight * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * Isolates foreign providers and image decoders from shared app dispatchers.
 * One stuck source occupies at most this single daemon worker.
 */
internal object ClipboardSharePreviewLoader {
    private val busy = AtomicBoolean(false)
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "clipboard-share-preview").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    private val importerLock = Any()

    @Volatile
    private var importer: ClipboardExternalMediaImporter? = null

    suspend fun load(
        context: Context,
        uri: Uri,
        declaredMimeType: String?,
        operation: ClipboardShareOperation,
        publishOwnedMedia:
            suspend (
                OwnedClipboardMediaUri,
                ClipboardShareOperationToken,
                ClipboardShareRequestFingerprint,
            ) -> Boolean,
    ): ClipboardSharePreview? {
        if (!operation.matches(uri.toString(), declaredMimeType)) return null
        if (!busy.compareAndSet(false, true)) return null
        var decoded: ClipboardSharePreview? = null
        return try {
            withContext(dispatcher) {
                val appContext = context.applicationContext
                var installed: InstalledClipboardMedia? = null
                var completed = false
                try {
                    installed = ClipboardFileStorage.resumeShareOperation(
                        context = appContext,
                        token = operation.token,
                        requestFingerprint = operation.requestFingerprint,
                    )
                    installed?.let { resumed ->
                        if (resumed.sharePublicationAttempted) {
                            return@withContext null
                        }
                        val workerContext = currentCoroutineContext()
                        workerContext.ensureActive()
                        val ownedFile = ClipboardFileStorage.ownedFile(
                            appContext,
                            resumed.ownedUri,
                        ) ?: error("Clipboard share media is unavailable.")
                        val preview = decodeOrEmpty(
                            path = ownedFile.toPath(),
                            checkActive = workerContext::ensureActive,
                        )
                        decoded = preview
                        workerContext.ensureActive()
                        if (!publishOwnedMedia(
                                resumed.ownedUri,
                                operation.token,
                                operation.requestFingerprint,
                            )
                        ) {
                            return@withContext null
                        }
                        completed = true
                        return@withContext preview
                    }
                    if (operation.isRestored) return@withContext null

                    stageCancellable(importer(appContext), uri)?.use { staged ->
                        val workerContext = currentCoroutineContext()
                        workerContext.ensureActive()
                        val preview = decodeOrEmpty(
                            path = staged.path,
                            checkActive = workerContext::ensureActive,
                        )
                        decoded = preview
                        val mimeTypes = clipboardShareMimeTypes(
                            decodedMimeType = preview.mimeType,
                            declaredMimeType = declaredMimeType,
                            sourceMimeType = staged.sourceMimeType,
                        )
                        val receipt = ClipboardFileStorage.installFromBackup(
                            context = appContext,
                            source = staged.path,
                            expectedBytes = staged.byteCount,
                            type = ItemType.IMAGE,
                            mimeTypes = mimeTypes,
                            displayName = staged.displayName,
                            shareOperationToken = operation.token,
                            shareRequestFingerprint = operation.requestFingerprint,
                            checkActive = workerContext::ensureActive,
                        )
                        installed = receipt

                        // This is the last cancellation point before ownership
                        // transfers to the non-cancellable clipboard actor.
                        workerContext.ensureActive()
                        if (!publishOwnedMedia(
                                receipt.ownedUri,
                                operation.token,
                                operation.requestFingerprint,
                            )
                        ) {
                            return@use null
                        }
                        completed = true
                        preview
                    }
                } finally {
                    if (!completed) {
                        decoded?.bitmap?.recycle()
                        decoded = null
                        // Deletes only an unchanged pending install. A root
                        // which may have reached ClipboardService is retained.
                        runCatching { installed?.cleanup() }
                    }
                }
            }
        } catch (error: CancellationException) {
            // withContext can deliver cancellation after a successful worker
            // result. The published root remains durable; only the UI preview
            // is no longer needed.
            decoded?.bitmap?.recycle()
            throw error
        } finally {
            busy.set(false)
        }
    }

    private suspend fun stageCancellable(
        importer: ClipboardExternalMediaImporter,
        uri: Uri,
    ): StagedClipboardMedia? = suspendCancellableCoroutine { continuation ->
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation {
            cancellationSignal.cancel()
        }
        val staged = importer.stage(uri, cancellationSignal)
        continuation.resume(staged) { _, unclaimedStage, _ ->
            unclaimedStage?.close()
        }
    }

    private fun importer(context: Context): ClipboardExternalMediaImporter {
        importer?.let { return it }
        return synchronized(importerLock) {
            importer ?: ClipboardExternalMediaImporter(
                context = context,
                timeoutMs = SHARE_PREVIEW_TIMEOUT_MS,
                stageCapacity = ClipboardFileStorage::externalStageCapacity,
                stagingDirectory = SHARE_PREVIEW_STAGING_DIRECTORY,
            ).also { importer = it }
        }
    }

    private fun decodeOrEmpty(
        path: Path,
        checkActive: () -> Unit,
    ): ClipboardSharePreview = decode(path, checkActive)
        ?: ClipboardSharePreview(bitmap = null, mimeType = null)

    private fun decode(
        path: Path,
        checkActive: () -> Unit,
    ): ClipboardSharePreview? {
        checkActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path.toString(), bounds)
        val orientation = readExifOrientation(path)
        checkActive()
        val swapsAxes = orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val orientedWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth
        val orientedHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight
        val plan = planClipboardSharePreview(orientedWidth, orientedHeight)
            ?: return null
        val decoded = BitmapFactory.decodeFile(
            path.toString(),
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = plan.sampleSize
            },
        ) ?: return null
        var ownedBitmap: Bitmap? = decoded
        try {
            checkActive()
            if (decoded.width > MAX_SHARE_PREVIEW_DECODE_EDGE ||
                decoded.height > MAX_SHARE_PREVIEW_DECODE_EDGE ||
                decoded.allocationByteCount > MAX_SHARE_PREVIEW_DECODE_BYTES
            ) {
                return null
            }
            val targetWidth = if (swapsAxes) plan.height else plan.width
            val targetHeight = if (swapsAxes) plan.width else plan.height
            val scaled = if (decoded.width == targetWidth && decoded.height == targetHeight) {
                decoded
            } else {
                decoded.scale(targetWidth, targetHeight).also {
                    decoded.recycle()
                    ownedBitmap = it
                }
            }
            checkActive()
            val preview = applyExifOrientation(scaled, orientation)
            ownedBitmap = preview
            checkActive()
            if (preview.width != plan.width ||
                preview.height != plan.height ||
                preview.allocationByteCount > MAX_SHARE_PREVIEW_DECODE_BYTES
            ) {
                return null
            }
            ownedBitmap = null
            return ClipboardSharePreview(
                bitmap = preview,
                mimeType = bounds.outMimeType,
            )
        } finally {
            ownedBitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    private fun readExifOrientation(path: Path): Int = runCatching {
        ExifInterface(path.toFile()).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(
        bitmap: Bitmap,
        orientation: Int,
    ): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }
}

private fun ceilDiv(value: Int, divisor: Int): Int =
    (value + divisor - 1) / divisor

private const val SHARE_PREVIEW_TIMEOUT_MS = 30_000L
private const val SHARE_PREVIEW_STAGING_DIRECTORY = "clipboard-share-previews"
private const val MAX_SHARE_PREVIEW_EDGE = 512
private const val MAX_SHARE_PREVIEW_DECODE_EDGE = MAX_SHARE_PREVIEW_EDGE * 2
private const val MAX_SHARE_PREVIEW_SOURCE_EDGE = 100_000
private const val MAX_SHARE_PREVIEW_SOURCE_PIXELS = 100_000_000L
private const val MAX_SHARE_PREVIEW_DECODE_BYTES =
    MAX_SHARE_PREVIEW_DECODE_EDGE * MAX_SHARE_PREVIEW_DECODE_EDGE * 4
internal const val MAX_SHARE_MIME_TYPE_LENGTH = 127
private const val DEFAULT_SHARE_MIME_TYPE = "image/unknown"
private val SHARE_MIME_TYPE = Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")
