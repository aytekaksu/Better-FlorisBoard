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

import android.content.Context
import android.content.Intent
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import androidx.exifinterface.media.ExifInterface
import dev.patrickgold.florisboard.ime.clipboard.ClipboardShareRequestFingerprint
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.kotlin.io.FsFile
import org.florisboard.lib.kotlin.io.subFile

private const val UNKNOWN_BOOT_COUNT = -1

internal enum class ClipboardMediaStorageFailure {
    INVALID_SOURCE,
    INVALID_METADATA,
    SOURCE_UNREADABLE,
    STORAGE_UNAVAILABLE,
    METADATA_UNAVAILABLE,
}

internal class ClipboardMediaStorageException(
    val failure: ClipboardMediaStorageFailure,
) : IOException(failure.name)

/**
 * Opaque identity of one share-to-clipboard request.
 *
 * The token is intentionally unrelated to the source URI or media contents.
 * Its canonical UUID form is small enough to persist safely and strict enough
 * that corrupted or caller-supplied metadata cannot become an operation key.
 */
@JvmInline
internal value class ClipboardShareOperationToken private constructor(
    internal val value: String,
) {
    override fun toString(): String = "ClipboardShareOperationToken(<redacted>)"

    companion object {
        private val FORMAT =
            Regex("""[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}""")

        fun create(): ClipboardShareOperationToken =
            ClipboardShareOperationToken(UUID.randomUUID().toString())

        fun parse(value: String?): ClipboardShareOperationToken? =
            value?.takeIf(FORMAT::matches)?.let(::ClipboardShareOperationToken)
    }
}

internal enum class SharePendingStatus {
    UNEXPIRED,
    EXPIRED,
    UNVERIFIABLE,
    INVALID,
}

internal fun shareOperationBindingHasValidShape(info: ClipboardFileInfo): Boolean {
    if (ClipboardShareOperationToken.parse(info.shareOperationToken) == null ||
        ClipboardShareRequestFingerprint.parse(info.shareRequestFingerprint) == null
    ) {
        return false
    }
    return if (info.ownershipState == ClipboardMediaOwnershipState.PENDING) {
        info.sharePendingBootCount?.let { it >= 0 } == true &&
            info.sharePendingDeadlineElapsedRealtimeMs > 0L
    } else {
        info.sharePendingBootCount == null &&
            info.sharePendingDeadlineElapsedRealtimeMs == 0L
    }
}

internal fun sharePendingStatus(
    info: ClipboardFileInfo,
    currentBootCount: Int,
    elapsedRealtimeMs: Long,
): SharePendingStatus {
    if (info.ownershipState != ClipboardMediaOwnershipState.PENDING ||
        !shareOperationBindingHasValidShape(info)
    ) {
        return SharePendingStatus.INVALID
    }
    val now = elapsedRealtimeMs.coerceAtLeast(0L)
    val latest = if (now > Long.MAX_VALUE - ClipboardFileStorage.SHARE_PENDING_RETENTION_MS) {
        Long.MAX_VALUE
    } else {
        now + ClipboardFileStorage.SHARE_PENDING_RETENTION_MS
    }
    val deadline = info.sharePendingDeadlineElapsedRealtimeMs
    return when {
        deadline > latest -> SharePendingStatus.INVALID
        deadline <= now -> SharePendingStatus.EXPIRED
        currentBootCount < 0 -> SharePendingStatus.UNVERIFIABLE
        info.sharePendingBootCount != currentBootCount -> SharePendingStatus.EXPIRED
        else -> SharePendingStatus.UNEXPIRED
    }
}

internal fun sharePendingIsUnexpired(
    info: ClipboardFileInfo,
    currentBootCount: Int,
    elapsedRealtimeMs: Long,
): Boolean =
    sharePendingStatus(info, currentBootCount, elapsedRealtimeMs) ==
        SharePendingStatus.UNEXPIRED

internal fun normalizeClipboardMediaDisplayName(displayName: String?): String? {
    if (displayName == null) return null
    var start = 0
    var end = displayName.length
    while (start < end && displayName[start].isWhitespace()) start++
    while (end > start && displayName[end - 1].isWhitespace()) end--
    if (start == end) return null

    val maximumLength = ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH
    val normalized = sanitizeDisplayName(displayName, start, end, maximumLength)
    if (normalized.complete) return normalized.value

    val extensionStart = displayName.lastIndexOf('.', end - 1)
    if (extensionStart <= start || extensionStart == end - 1) return normalized.value
    val extension = sanitizeDisplayName(displayName, extensionStart, end, maximumLength)
    if (!extension.complete || extension.value.length >= maximumLength) return normalized.value
    val base = sanitizeDisplayName(
        displayName,
        start,
        extensionStart,
        maximumLength - extension.value.length,
    )
    return base.value + extension.value
}

private data class SanitizedDisplayName(
    val value: String,
    val complete: Boolean,
)

private fun sanitizeDisplayName(
    value: String,
    start: Int,
    end: Int,
    maximumLength: Int,
): SanitizedDisplayName {
    val result = StringBuilder(minOf(end - start, maximumLength))
    var index = start
    while (index < end) {
        val first = value[index]
        val codePoint: Int
        val codeUnits: Int
        if (first.isHighSurrogate() && index + 1 < end && value[index + 1].isLowSurrogate()) {
            codePoint = Character.toCodePoint(first, value[index + 1])
            codeUnits = 2
        } else {
            codePoint = first.code
            codeUnits = 1
        }
        val unsafe = when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SURROGATE.toInt(),
            -> true
            else -> false
        }
        val requiredLength = if (unsafe) 1 else codeUnits
        if (result.length + requiredLength > maximumLength) break
        if (unsafe) result.append('_') else result.appendCodePoint(codePoint)
        index += codeUnits
    }
    return SanitizedDisplayName(result.toString(), complete = index == end)
}

/**
 * Ownership receipt for one freshly installed media file and its metadata.
 *
 * Restore keeps this receipt until commit. Calling [cleanup] removes only this
 * exact install, so rollback can never delete pre-existing clipboard media.
 */
internal class InstalledClipboardMedia internal constructor(
    val ownedUri: OwnedClipboardMediaUri,
    val sharePublicationAttempted: Boolean,
    private val cleanupAction: () -> Boolean,
) {
    fun cleanup(): Boolean = cleanupAction()

    override fun toString(): String =
        "InstalledClipboardMedia(type=${ownedUri.type}, " +
            "sharePublicationAttempted=$sharePublicationAttempted, id=<redacted>)"
}

internal data class ClipboardPasteAdmission(
    val fileInfo: ClipboardFileInfo?,
    val receipt: ClipboardPasteAdmissionReceipt?,
    val expiredRoots: Set<OwnedClipboardMediaUri>,
)

internal class ClipboardPasteAdmissionReceipt internal constructor(
    internal val ownedUri: OwnedClipboardMediaUri,
    internal val token: Long,
    internal val previousRetainedUntilMs: Long,
    internal val previousExternalCapabilityBootCount: Int?,
    internal val admittedUntilMs: Long,
) {
    override fun toString(): String =
        "ClipboardPasteAdmissionReceipt(type=${ownedUri.type}, id=<redacted>)"
}

internal fun pasteAdmissionFits(
    activeRootSizes: Map<Long, Long>,
    candidateId: Long,
    candidateBytes: Long,
    maxRoots: Int,
    maxBytes: Long,
): Boolean {
    if (candidateId <= 0L ||
        candidateBytes <= 0L ||
        maxRoots <= 0 ||
        maxBytes <= 0L ||
        activeRootSizes.size > maxRoots
    ) {
        return false
    }
    var activeBytes = 0L
    for ((id, size) in activeRootSizes) {
        if (id <= 0L || size <= 0L || size > maxBytes - activeBytes) return false
        activeBytes += size
    }
    val existingBytes = activeRootSizes[candidateId]
    if (existingBytes != null) {
        return existingBytes == candidateBytes
    }
    return activeRootSizes.size < maxRoots &&
        candidateBytes <= maxBytes - activeBytes
}

internal fun externalCapabilityIsQuarantined(
    stampedBootCount: Int?,
    currentBootCount: Int,
): Boolean {
    return stampedBootCount != null &&
        (
            stampedBootCount < 0 ||
                currentBootCount < 0 ||
                stampedBootCount == currentBootCount
    )
}

internal fun externalCapabilityIsCurrent(
    stampedBootCount: Int?,
    currentBootCount: Int,
): Boolean = currentBootCount >= 0 && stampedBootCount == currentBootCount

internal fun normalizeExternalCapabilityBootCount(
    stampedBootCount: Int?,
    isSystemRoot: Boolean,
    pasteRetainedUntilMs: Long,
    currentBootCount: Int,
): Int? {
    return when {
        stampedBootCount == LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT ->
            currentBootCount.takeIf { it >= 0 } ?: LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT
        stampedBootCount == null && (isSystemRoot || pasteRetainedUntilMs > 0L) ->
            currentBootCount.takeIf { it >= 0 } ?: LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT
        stampedBootCount == null || stampedBootCount >= -1 -> stampedBootCount
        else -> UNKNOWN_BOOT_COUNT
    }
}

internal const val MAX_ARCHIVE_MEDIA_MIME_CANDIDATES = 4096
internal const val MAX_ARCHIVE_MEDIA_MIME_CANDIDATE_LENGTH = 4096
internal const val MAX_ARCHIVE_MEDIA_MIME_TOTAL_LENGTH = 64 * 1024

private const val IMAGE_MIME_PREFIX = "image/"
private const val VIDEO_MIME_PREFIX = "video/"
private const val IMAGE_MIME_WILDCARD = "$IMAGE_MIME_PREFIX*"
private const val VIDEO_MIME_WILDCARD = "$VIDEO_MIME_PREFIX*"
private val MIME_TYPE = Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")

/**
 * Reduces MIME metadata written by older versions to the current storage
 * limits without guessing whether media is an image or video.
 */
internal fun normalizeArchiveMediaMimeTypes(mimeTypes: List<String>): List<String>? {
    if (mimeTypes.isEmpty() || mimeTypes.size > MAX_ARCHIVE_MEDIA_MIME_CANDIDATES) {
        return null
    }

    var totalLength = 0L
    var hasConcreteImage = false
    var hasConcreteVideo = false
    var hasImageWildcard = false
    var hasVideoWildcard = false
    val accepted = linkedSetOf<String>()
    for (rawCandidate in mimeTypes) {
        if (rawCandidate.length > MAX_ARCHIVE_MEDIA_MIME_CANDIDATE_LENGTH) return null
        totalLength += rawCandidate.length
        if (totalLength > MAX_ARCHIVE_MEDIA_MIME_TOTAL_LENGTH) return null

        val candidate = rawCandidate.trim().lowercase()
        if ('*' in candidate) {
            when (candidate) {
                IMAGE_MIME_WILDCARD -> {
                    hasImageWildcard = true
                    accepted += candidate
                }
                VIDEO_MIME_WILDCARD -> {
                    hasVideoWildcard = true
                    accepted += candidate
                }
                else -> return null
            }
            if ((hasConcreteImage || hasImageWildcard) &&
                (hasConcreteVideo || hasVideoWildcard)
            ) {
                return null
            }
            continue
        }
        if (candidate.length > ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH ||
            !MIME_TYPE.matches(candidate)
        ) {
            continue
        }
        when {
            candidate.startsWith(IMAGE_MIME_PREFIX) -> hasConcreteImage = true
            candidate.startsWith(VIDEO_MIME_PREFIX) -> hasConcreteVideo = true
        }
        if ((hasConcreteImage || hasImageWildcard) &&
            (hasConcreteVideo || hasVideoWildcard)
        ) {
            return null
        }
        accepted += candidate
    }

    val familyPrefix = when {
        (hasConcreteImage || hasImageWildcard) && !hasConcreteVideo && !hasVideoWildcard ->
            IMAGE_MIME_PREFIX
        (hasConcreteVideo || hasVideoWildcard) && !hasConcreteImage && !hasImageWildcard ->
            VIDEO_MIME_PREFIX
        else -> return null
    }
    val familyWildcard = "$familyPrefix*"
    val hasConcreteFamily = when (familyPrefix) {
        IMAGE_MIME_PREFIX -> hasConcreteImage
        VIDEO_MIME_PREFIX -> hasConcreteVideo
        else -> false
    }
    val canonical = buildList {
        for (candidate in accepted) {
            when {
                candidate != familyWildcard -> add(candidate)
                !hasConcreteFamily -> add("${familyPrefix}unknown")
            }
        }
    }
    val familyIndex = canonical.indexOfFirst { it.startsWith(familyPrefix) }
    if (familyIndex < 0) return null
    if (canonical.size <= ClipboardFileStorage.MAX_MEDIA_MIME_TYPES) return canonical

    val bounded = canonical.take(ClipboardFileStorage.MAX_MEDIA_MIME_TYPES).toMutableList()
    if (bounded.none { it.startsWith(familyPrefix) }) {
        bounded[bounded.lastIndex] = canonical[familyIndex]
    }
    return bounded
}

/**
 * Durable private storage for media served by [ClipboardMediaProvider].
 */
object ClipboardFileStorage {
    const val CLIPBOARD_FILES_PATH = "clipboard_files"
    internal const val MAX_MEDIA_BYTES = 100_000_000L
    internal const val MAX_TOTAL_MEDIA_BYTES = 512L * 1024L * 1024L
    internal const val MAX_MEDIA_MIME_TYPE_LENGTH = 127
    internal const val MAX_MEDIA_MIME_TYPES = 16
    internal const val MAX_DISPLAY_NAME_LENGTH = 255

    private const val BUFFER_SIZE = 64 * 1024
    private const val MIN_FREE_SPACE_RESERVE_BYTES = 32L * 1024L * 1024L
    private const val MAX_ID_ATTEMPTS = 128
    private const val UNREAD_BOOT_COUNT = Int.MIN_VALUE
    private const val PARTIAL_PREFIX = ".clipboard-media-"
    private const val PARTIAL_SUFFIX = ".partial"
    private const val MAX_PASTE_ROOTS = 128
    private const val MAX_PASTE_ROOT_BYTES = 128L * 1024L * 1024L
    private const val PASTE_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    internal const val SHARE_PENDING_RETENTION_MS = 15L * 60L * 1_000L

    private val directoryLock = Any()
    private val mutationLock = Any()
    private val pasteAdmissionLock = Any()
    private val bootCountLock = Any()
    private val liveInstalls = mutableSetOf<OwnedClipboardMediaUri>()
    private val pasteAdmissionTokens = mutableMapOf<Long, Long>()
    private val random = SecureRandom()
    @Volatile
    private var cachedBootCount = UNREAD_BOOT_COUNT
    private var nextPasteAdmissionToken = 0L

    internal fun initialize(context: Context) {
        try {
            MetadataStore.initialize(
                context = context.applicationContext,
                directory = storageDirectory(context),
                currentBootCount = currentBootCount(context),
            )
        } catch (error: ClipboardMediaStorageException) {
            throw error
        } catch (_: Exception) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.METADATA_UNAVAILABLE)
        }
    }

    /**
     * Re-runs legacy history recovery after initialization. This is useful
     * when a history database becomes available after an earlier provider
     * startup and keeps the recovery operation directly testable.
     */
    internal fun recoverLegacyHistoryMedia(context: Context) {
        synchronized(mutationLock) {
            initialize(context)
            try {
                MetadataStore.recoverLegacyHistoryMedia(
                    context = context.applicationContext,
                    directory = storageDirectory(context),
                    currentBootCount = currentBootCount(context),
                )
            } catch (error: ClipboardMediaStorageException) {
                throw error
            } catch (_: Exception) {
                throw ClipboardMediaStorageException(
                    ClipboardMediaStorageFailure.METADATA_UNAVAILABLE,
                )
            }
        }
    }

    internal fun installFromBackup(
        context: Context,
        source: Path,
        expectedBytes: Long,
        type: ItemType,
        mimeTypes: List<String>,
        displayName: String? = null,
        shareOperationToken: ClipboardShareOperationToken? = null,
        shareRequestFingerprint: ClipboardShareRequestFingerprint? = null,
        checkActive: () -> Unit = {},
    ): InstalledClipboardMedia {
        if ((shareOperationToken == null) != (shareRequestFingerprint == null)) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_METADATA)
        }
        if (expectedBytes !in 1..MAX_MEDIA_BYTES) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
        }
        val attributes = readAttributes(source)
        if (!attributes.isRegularFile ||
            attributes.isSymbolicLink ||
            attributes.size() != expectedBytes
        ) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
        }
        return install(
            context = context,
            type = type,
            displayName = displayName,
            mimeTypes = mimeTypes,
            expectedBytes = expectedBytes,
            shareOperationToken = shareOperationToken,
            shareRequestFingerprint = shareRequestFingerprint,
            checkActive = checkActive,
        ) {
            checkActive()
            try {
                Channels.newInputStream(
                    FileChannel.open(
                        source,
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.SOURCE_UNREADABLE)
            }
        }
    }

    /**
     * Claims the durable install for a restored share operation.
     *
     * Pending media is protected from ownership reconciliation while the
     * returned receipt is live. Active or rooted media is returned as-is, and
     * its receipt can never clean it up.
     */
    internal fun resumeShareOperation(
        context: Context,
        token: ClipboardShareOperationToken,
        requestFingerprint: ClipboardShareRequestFingerprint,
        type: ItemType = ItemType.IMAGE,
    ): InstalledClipboardMedia? {
        return synchronized(mutationLock) {
            initialize(context)
            existingShareInstall(context, token, requestFingerprint, type)
        }
    }

    /**
     * Atomically claims one exact pending share before its clipboard write.
     *
     * Returning metadata means the file is already a durable system root, so
     * restoration must treat publication as attempted even if Binder fails.
     */
    internal fun claimPendingShareForPublication(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
        token: ClipboardShareOperationToken,
        requestFingerprint: ClipboardShareRequestFingerprint,
    ): ClipboardFileInfo? {
        return synchronized(mutationLock) {
            initialize(context)
            val observedBootCount = currentBootCount(context)
            if (observedBootCount < 0) return@synchronized null
            val info = MetadataStore.getShareOperation(token) ?: return@synchronized null
            if (info.id != ownedUri.id ||
                info.ownershipState != ClipboardMediaOwnershipState.PENDING ||
                info.shareRequestFingerprint != requestFingerprint.value ||
                mediaType(info.mimeTypes) != ownedUri.type ||
                !sharePendingIsUnexpired(
                    info = info,
                    currentBootCount = observedBootCount,
                    elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                ) ||
                !regularFileMatches(context, ownedUri.id, info.size)
            ) {
                return@synchronized null
            }
            MetadataStore.setSystemRoots(
                ownedUris = setOf(ownedUri),
                retainExisting = true,
                externalCapabilityBootCount = observedBootCount,
            )
            MetadataStore.get(ownedUri)?.takeIf { claimed ->
                claimed.ownershipState == ClipboardMediaOwnershipState.ACTIVE &&
                    claimed.isSystemRoot &&
                    claimed.sharePendingBootCount == null &&
                    claimed.sharePendingDeadlineElapsedRealtimeMs == 0L
            }
        }
    }

    internal fun canInstallBatch(context: Context, byteCount: Long): Boolean {
        if (byteCount < 0L || byteCount > MAX_TOTAL_MEDIA_BYTES) return false
        return synchronized(mutationLock) {
            try {
                initialize(context)
                val storedBytes = MetadataStore.totalBytes()
                val quotaAvailable = storedBytes <= MAX_TOTAL_MEDIA_BYTES - byteCount
                val diskAvailable = StatFs(context.noBackupFilesDir.path).availableBytes >=
                    byteCount + MIN_FREE_SPACE_RESERVE_BYTES
                quotaAvailable && diskAvailable
            } catch (_: Exception) {
                false
            }
        }
    }

    internal fun externalStageCapacity(context: Context): Long {
        return synchronized(mutationLock) {
            try {
                initialize(context)
                val quotaAvailable =
                    (MAX_TOTAL_MEDIA_BYTES - MetadataStore.totalBytes()).coerceAtLeast(0L)
                val diskAvailable = (
                    StatFs(context.cacheDir.path).availableBytes -
                        MIN_FREE_SPACE_RESERVE_BYTES
                    ).coerceAtLeast(0L) / 2L
                minOf(MAX_MEDIA_BYTES, quotaAvailable, diskAvailable)
            } catch (_: Exception) {
                0L
            }
        }
    }

    internal fun fileInfo(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
    ): ClipboardFileInfo? {
        initialize(context)
        val info = MetadataStore.get(ownedUri) ?: return null
        return info.takeIf { regularFileMatches(context, ownedUri.id, it.size) }
    }

    /**
     * Resolves media only while it is a durable root of the current system
     * clipboard capability. This is the narrow public-read boundary used by
     * Android 8, whose ClipboardService cannot delegate private provider URIs.
     */
    internal fun currentSystemRootFileInfo(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
    ): ClipboardFileInfo? {
        val observedBootCount = currentBootCount(context)
        return synchronized(mutationLock) {
            initialize(context)
            val info = MetadataStore.get(ownedUri) ?: return@synchronized null
            info.takeIf {
                it.isSystemRoot &&
                    externalCapabilityIsCurrent(
                        it.externalCapabilityBootCount,
                        observedBootCount,
                    ) &&
                    regularFileMatches(context, ownedUri.id, it.size)
            }
        }
    }

    internal fun ownedFile(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
    ): FsFile? = fileInfo(context, ownedUri)?.let { fileForId(context, ownedUri.id) }

    internal fun ownedCurrentSystemRootFile(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
    ): FsFile? = currentSystemRootFileInfo(context, ownedUri)
        ?.let { fileForId(context, ownedUri.id) }

    internal fun deleteOwned(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
        observedBootCount: Int = currentBootCount(context),
        isSafeToDelete: () -> Boolean = { true },
    ): Boolean {
        synchronized(mutationLock) {
            initialize(context)
            val info = MetadataStore.get(ownedUri) ?: return false
            if (info.isSystemRoot ||
                info.pasteRetainedUntilMs > System.currentTimeMillis().coerceAtLeast(0L) ||
                externalCapabilityIsQuarantined(
                    info.externalCapabilityBootCount,
                    observedBootCount,
                )
            ) {
                return false
            }
            if (!isSafeToDelete()) return false
            try {
                Files.deleteIfExists(filePath(context, ownedUri.id))
                forceDirectory(storageDirectory(context))
            } catch (_: Exception) {
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
            }
            try {
                MetadataStore.delete(ownedUri.id)
            } catch (_: Exception) {
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.METADATA_UNAVAILABLE)
            }
            liveInstalls.remove(ownedUri)
            synchronized(pasteAdmissionLock) {
                pasteAdmissionTokens.remove(ownedUri.id)
            }
            runCatching {
                context.revokeUriPermission(ownedUri.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return true
        }
    }

    internal fun isDeletionQuarantined(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
    ): Boolean {
        return synchronized(mutationLock) {
            initialize(context)
            MetadataStore.get(ownedUri)?.let { info ->
                externalCapabilityIsQuarantined(
                    info.externalCapabilityBootCount,
                    currentBootCount(context),
                )
            } ?: false
        }
    }

    internal fun markActive(
        context: Context,
        ownedUris: Iterable<OwnedClipboardMediaUri>,
    ) {
        val roots = ownedUris.toSet()
        updateOwnershipState(context, roots, ClipboardMediaOwnershipState.ACTIVE)
        synchronized(mutationLock) {
            liveInstalls.removeAll(roots)
        }
    }

    internal fun markRetiring(
        context: Context,
        ownedUris: Iterable<OwnedClipboardMediaUri>,
    ) {
        updateOwnershipState(context, ownedUris, ClipboardMediaOwnershipState.RETIRING)
    }

    internal fun prepareSystemRoots(
        context: Context,
        ownedUris: Set<OwnedClipboardMediaUri>,
        observedBootCount: Int = currentBootCount(context),
    ) {
        if (ownedUris.isEmpty()) return
        synchronized(mutationLock) {
            initialize(context)
            val validRoots = ownedUris.takeIf { candidates ->
                candidates.all { fileInfo(context, it) != null }
            } ?: throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
            MetadataStore.setSystemRoots(
                ownedUris = validRoots,
                retainExisting = true,
                externalCapabilityBootCount = observedBootCount,
            )
        }
    }

    internal fun recordSystemRoots(
        context: Context,
        ownedUris: Set<OwnedClipboardMediaUri>,
        observedBootCount: Int = currentBootCount(context),
    ) {
        synchronized(mutationLock) {
            initialize(context)
            val validRoots = ownedUris.takeIf { candidates ->
                candidates.all { fileInfo(context, it) != null }
            } ?: throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
            MetadataStore.setSystemRoots(
                ownedUris = validRoots,
                retainExisting = false,
                externalCapabilityBootCount = observedBootCount,
            )
        }
    }

    internal fun systemRoots(context: Context): Set<OwnedClipboardMediaUri> {
        return synchronized(mutationLock) {
            initialize(context)
            MetadataStore.systemRoots()
        }
    }

    internal fun markPasteRoot(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
        protectedRoots: Set<OwnedClipboardMediaUri> = emptySet(),
        observedBootCount: Int = currentBootCount(context),
    ): ClipboardPasteAdmission {
        return synchronized(mutationLock) {
            initialize(context)
            val info = fileInfo(context, ownedUri)
                ?: throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
            val now = System.currentTimeMillis().coerceAtLeast(0L)
            val retainedUntilMs = if (now > Long.MAX_VALUE - PASTE_RETENTION_MS) {
                Long.MAX_VALUE
            } else {
                now + PASTE_RETENTION_MS
            }
            val admission = MetadataStore.admitPasteRoot(
                info.copy(
                    ownershipState = ClipboardMediaOwnershipState.ACTIVE,
                    pasteRetainedUntilMs = retainedUntilMs,
                    externalCapabilityBootCount = observedBootCount,
                    sharePendingBootCount = null,
                    sharePendingDeadlineElapsedRealtimeMs = 0L,
                ),
                now = now,
                maxRoots = MAX_PASTE_ROOTS,
                maxBytes = MAX_PASTE_ROOT_BYTES,
                protectedRoots = protectedRoots + ownedUri,
            )
            if (admission.admitted) {
                liveInstalls.remove(ownedUri)
            }
            val receipt = if (admission.admitted) {
                synchronized(pasteAdmissionLock) {
                    nextPasteAdmissionToken =
                        if (nextPasteAdmissionToken == Long.MAX_VALUE) {
                            1L
                        } else {
                            nextPasteAdmissionToken + 1L
                        }
                    ClipboardPasteAdmissionReceipt(
                        ownedUri = ownedUri,
                        token = nextPasteAdmissionToken,
                        previousRetainedUntilMs = info.pasteRetainedUntilMs,
                        previousExternalCapabilityBootCount =
                            info.externalCapabilityBootCount,
                        admittedUntilMs = retainedUntilMs,
                    ).also { pasteAdmissionTokens[ownedUri.id] = it.token }
                }
            } else {
                null
            }
            ClipboardPasteAdmission(
                fileInfo = info.takeIf { admission.admitted },
                receipt = receipt,
                expiredRoots = admission.expiredRoots,
            )
        }
    }

    internal fun completePasteAdmission(receipt: ClipboardPasteAdmissionReceipt) {
        synchronized(pasteAdmissionLock) {
            pasteAdmissionTokens.remove(receipt.ownedUri.id, receipt.token)
        }
    }

    internal fun abortPasteAdmission(
        context: Context,
        receipt: ClipboardPasteAdmissionReceipt,
    ): Set<OwnedClipboardMediaUri> {
        return synchronized(mutationLock) {
            if (synchronized(pasteAdmissionLock) {
                    pasteAdmissionTokens[receipt.ownedUri.id]
                } != receipt.token
            ) {
                return@synchronized emptySet()
            }
            initialize(context)
            val current = MetadataStore.get(receipt.ownedUri) ?: run {
                synchronized(pasteAdmissionLock) {
                    pasteAdmissionTokens.remove(receipt.ownedUri.id, receipt.token)
                }
                return@synchronized emptySet()
            }
            if (current.pasteRetainedUntilMs != receipt.admittedUntilMs) {
                synchronized(pasteAdmissionLock) {
                    pasteAdmissionTokens.remove(receipt.ownedUri.id, receipt.token)
                }
                return@synchronized emptySet()
            }
            MetadataStore.update(
                current.copy(
                    pasteRetainedUntilMs = receipt.previousRetainedUntilMs,
                    externalCapabilityBootCount =
                        receipt.previousExternalCapabilityBootCount,
                ),
            )
            synchronized(pasteAdmissionLock) {
                pasteAdmissionTokens.remove(receipt.ownedUri.id, receipt.token)
            }
            if (receipt.previousRetainedUntilMs >
                System.currentTimeMillis().coerceAtLeast(0L)
            ) {
                emptySet()
            } else {
                setOf(receipt.ownedUri)
            }
        }
    }

    internal fun trimPasteRoots(
        context: Context,
        protectedRoots: Set<OwnedClipboardMediaUri> = emptySet(),
        now: Long = System.currentTimeMillis().coerceAtLeast(0L),
    ): Set<OwnedClipboardMediaUri> {
        return synchronized(mutationLock) {
            initialize(context)
            MetadataStore.expirePasteRoots(
                now = now.coerceAtLeast(0L),
                protectedRoots = protectedRoots,
            )
        }
    }

    internal fun pasteRoots(context: Context): Set<OwnedClipboardMediaUri> {
        return synchronized(mutationLock) {
            initialize(context)
            MetadataStore.pasteRoots(System.currentTimeMillis().coerceAtLeast(0L))
        }
    }

    internal fun hasUnresolvedOwnership(
        context: Context,
        knownRoots: Set<OwnedClipboardMediaUri>,
    ): Boolean {
        return synchronized(mutationLock) {
            initialize(context)
            val now = System.currentTimeMillis().coerceAtLeast(0L)
            val observedBootCount = currentBootCount(context)
            MetadataStore.all().any { info ->
                val type = mediaType(info.mimeTypes) ?: return@any false
                val ownedUri = OwnedClipboardMediaUri.create(info.id, type) ?: return@any false
                ownedUri !in knownRoots &&
                    info.pasteRetainedUntilMs <= now &&
                    (
                        info.isSystemRoot ||
                            !externalCapabilityIsQuarantined(
                                info.externalCapabilityBootCount,
                                observedBootCount,
                            ) &&
                            info.ownershipState != ClipboardMediaOwnershipState.PENDING
                        )
            }
        }
    }

    internal fun hasPendingOwnership(context: Context): Boolean {
        return synchronized(mutationLock) {
            initialize(context)
            MetadataStore.all().any {
                it.ownershipState == ClipboardMediaOwnershipState.PENDING
            }
        }
    }

    /**
     * Repairs ownership after process death. New pending or retiring installs
     * are removed unless history or the durable system root still owns them.
     */
    internal fun reconcileOwnership(
        context: Context,
        historyRoots: Set<OwnedClipboardMediaUri>,
        observedSystemRoots: Set<OwnedClipboardMediaUri>,
        systemClipboardObserved: Boolean,
        reserveDeletion: (OwnedClipboardMediaUri) -> Boolean = { true },
        releaseDeletion: (OwnedClipboardMediaUri) -> Unit = {},
        isSystemStateCurrent: () -> Boolean = { true },
        observedBootCount: Int = currentBootCount(context),
        nowMs: Long = System.currentTimeMillis().coerceAtLeast(0L),
        shareElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): Set<OwnedClipboardMediaUri> {
        return synchronized(mutationLock) {
            initialize(context)
            val now = nowMs.coerceAtLeast(0L)
            if (systemClipboardObserved) {
                if (!isSystemStateCurrent()) return@synchronized emptySet()
                val validObservedRoots = observedSystemRoots.takeIf { candidates ->
                    candidates.all { fileInfo(context, it) != null }
                } ?: throw ClipboardMediaStorageException(
                    ClipboardMediaStorageFailure.INVALID_SOURCE,
                )
                MetadataStore.setSystemRoots(
                    ownedUris = validObservedRoots,
                    retainExisting = false,
                    externalCapabilityBootCount = observedBootCount,
                )
            }
            val durableRetained =
                historyRoots +
                    MetadataStore.systemRoots() +
                    MetadataStore.pasteRoots(now) +
                    MetadataStore.quarantinedRoots(observedBootCount)
            val failed = linkedSetOf<OwnedClipboardMediaUri>()
            for (info in MetadataStore.all()) {
                val type = mediaType(info.mimeTypes) ?: continue
                val ownedUri = OwnedClipboardMediaUri.create(info.id, type) ?: continue
                if (ownedUri in durableRetained) {
                    if (info.ownershipState != ClipboardMediaOwnershipState.ACTIVE) {
                        runCatching {
                            MetadataStore.update(
                                info.copy(
                                    ownershipState = ClipboardMediaOwnershipState.ACTIVE,
                                    sharePendingBootCount = null,
                                    sharePendingDeadlineElapsedRealtimeMs = 0L,
                                ),
                            )
                        }
                    }
                    liveInstalls.remove(ownedUri)
                    continue
                }
                when (
                    sharePendingStatus(
                        info = info,
                        currentBootCount = observedBootCount,
                        elapsedRealtimeMs = shareElapsedRealtimeMs,
                    )
                ) {
                    SharePendingStatus.UNEXPIRED -> {
                        // A restored exported Activity claims this bounded
                        // pending install after application bootstrap.
                        continue
                    }
                    SharePendingStatus.UNVERIFIABLE -> {
                        // BOOT_COUNT can be transiently unavailable. Do not
                        // serve or delete the install until maintenance retries.
                        continue
                    }
                    SharePendingStatus.EXPIRED,
                    SharePendingStatus.INVALID,
                    -> Unit
                }
                if (ownedUri in liveInstalls && info.shareOperationToken == null) {
                    continue
                }
                val shouldDelete = systemClipboardObserved ||
                    info.ownershipState == ClipboardMediaOwnershipState.PENDING
                if (shouldDelete) {
                    if (!isSystemStateCurrent()) break
                    if (!reserveDeletion(ownedUri)) {
                        failed += ownedUri
                        continue
                    }
                    try {
                        if (!isSystemStateCurrent()) {
                            failed += ownedUri
                            break
                        }
                        if (!runCatching {
                                deleteOwned(
                                    context = context,
                                    ownedUri = ownedUri,
                                    observedBootCount = observedBootCount,
                                    isSafeToDelete = isSystemStateCurrent,
                                )
                            }.getOrDefault(false)
                        ) {
                            failed += ownedUri
                        }
                    } finally {
                        releaseDeletion(ownedUri)
                    }
                } else {
                    // Unknown system ownership must preserve this root, but the
                    // actor still needs to poll until an exact observation can
                    // decide its lifetime.
                    failed += ownedUri
                }
            }
            failed
        }
    }

    private fun updateOwnershipState(
        context: Context,
        ownedUris: Iterable<OwnedClipboardMediaUri>,
        state: ClipboardMediaOwnershipState,
    ) {
        synchronized(mutationLock) {
            initialize(context)
            for (ownedUri in ownedUris.toSet()) {
                val info = MetadataStore.get(ownedUri) ?: continue
                val updated = info.copy(
                    ownershipState = state,
                    sharePendingBootCount =
                        if (state == ClipboardMediaOwnershipState.PENDING) {
                            info.sharePendingBootCount
                        } else {
                            null
                        },
                    sharePendingDeadlineElapsedRealtimeMs =
                        if (state == ClipboardMediaOwnershipState.PENDING) {
                            info.sharePendingDeadlineElapsedRealtimeMs
                        } else {
                            0L
                        },
                )
                if (updated != info) {
                    MetadataStore.update(updated)
                }
            }
        }
    }

    private fun fileForId(context: Context, id: Long): FsFile {
        require(id > 0L) { "Clipboard media ID must be positive." }
        return context.noBackupFilesDir.subFile("$CLIPBOARD_FILES_PATH/$id")
    }

    private fun install(
        context: Context,
        type: ItemType,
        displayName: String?,
        mimeTypes: List<String>,
        expectedBytes: Long? = null,
        shareOperationToken: ClipboardShareOperationToken? = null,
        shareRequestFingerprint: ClipboardShareRequestFingerprint? = null,
        checkActive: () -> Unit,
        openSource: () -> InputStream,
    ): InstalledClipboardMedia {
        checkActive()
        val normalizedMimeTypes = normalizeMimeTypes(type, mimeTypes)
        val normalizedDisplayName = normalizeDisplayName(type, displayName)
        synchronized(mutationLock) {
            initialize(context)
            shareOperationToken?.let { token ->
                existingShareInstall(
                    context = context,
                    token = token,
                    requestFingerprint = requireNotNull(shareRequestFingerprint),
                    expectedType = type,
                )?.let { return it }
            }
            val pendingShareBootCount = shareOperationToken?.let {
                currentBootCount(context).takeIf { bootCount -> bootCount >= 0 }
                    ?: throw ClipboardMediaStorageException(
                        ClipboardMediaStorageFailure.METADATA_UNAVAILABLE,
                    )
            }
            val directory = storageDirectory(context)
            MetadataStore.cleanupOrphanedFiles(directory)
            val availableQuota = MAX_TOTAL_MEDIA_BYTES - MetadataStore.totalBytes()
            val availableDisk = (
                StatFs(context.noBackupFilesDir.path).availableBytes -
                    MIN_FREE_SPACE_RESERVE_BYTES
                ).coerceAtLeast(0L)
            val availableBytes = minOf(availableQuota, availableDisk)
            if (availableBytes <= 0L || expectedBytes != null && expectedBytes > availableBytes) {
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
            }
            val ownedUri = allocateOwnedUri(directory, type)
            val destination = directory.resolve(ownedUri.id.toString())
            val partial = allocatePartialPath(directory, ownedUri.id)
            var published = false
            try {
                val copiedBytes = openSource().use { source ->
                    copyToPartial(
                        source = source,
                        partial = partial,
                        maximumBytes = minOf(
                            expectedBytes ?: MAX_MEDIA_BYTES,
                            availableBytes,
                        ),
                        checkActive = checkActive,
                    )
                }
                if (copiedBytes <= 0L || (expectedBytes != null && copiedBytes != expectedBytes)) {
                    throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
                }
                checkActive()
                val orientation = imageOrientation(type, partial)
                checkActive()
                publish(partial, destination, directory)
                published = true
                val fileInfo = ClipboardFileInfo(
                    id = ownedUri.id,
                    displayName = normalizedDisplayName,
                    size = copiedBytes,
                    orientation = orientation,
                    mimeTypes = normalizedMimeTypes,
                    ownershipState = ClipboardMediaOwnershipState.PENDING,
                    shareOperationToken = shareOperationToken?.value,
                    shareRequestFingerprint = shareRequestFingerprint?.value,
                    sharePendingBootCount = pendingShareBootCount,
                    sharePendingDeadlineElapsedRealtimeMs =
                        if (pendingShareBootCount != null) {
                            sharePendingDeadline(SystemClock.elapsedRealtime())
                        } else {
                            0L
                        },
                )
                try {
                    MetadataStore.insert(fileInfo)
                } catch (_: Exception) {
                    MetadataStore.deleteBestEffort(ownedUri.id)
                    throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.METADATA_UNAVAILABLE)
                }
                liveInstalls += ownedUri
                val appContext = context.applicationContext
                return installReceipt(appContext, ownedUri, fileInfo)
            } catch (error: CancellationException) {
                if (published) {
                    deleteBestEffort(destination)
                    forceDirectoryBestEffort(directory)
                }
                throw error
            } catch (error: ClipboardMediaStorageException) {
                if (published) {
                    deleteBestEffort(destination)
                    forceDirectoryBestEffort(directory)
                }
                throw error
            } catch (_: Exception) {
                if (published) {
                    deleteBestEffort(destination)
                    forceDirectoryBestEffort(directory)
                }
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
            } finally {
                deleteBestEffort(partial)
            }
        }
    }

    private fun existingShareInstall(
        context: Context,
        token: ClipboardShareOperationToken,
        requestFingerprint: ClipboardShareRequestFingerprint,
        expectedType: ItemType,
    ): InstalledClipboardMedia? {
        val info = MetadataStore.getShareOperation(token) ?: return null
        if (info.shareRequestFingerprint != requestFingerprint.value) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_METADATA)
        }
        val type = mediaType(info.mimeTypes)
        if (type != expectedType ||
            info.id <= 0L ||
            !regularFileMatches(context, info.id, info.size)
        ) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_METADATA)
        }
        val ownedUri = OwnedClipboardMediaUri.create(info.id, type)
            ?: throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_METADATA)
        if (info.ownershipState == ClipboardMediaOwnershipState.PENDING) {
            when (
                sharePendingStatus(
                    info = info,
                    currentBootCount = currentBootCount(context),
                    elapsedRealtimeMs = SystemClock.elapsedRealtime(),
                )
            ) {
                SharePendingStatus.UNEXPIRED -> liveInstalls += ownedUri
                SharePendingStatus.UNVERIFIABLE -> {
                    throw ClipboardMediaStorageException(
                        ClipboardMediaStorageFailure.METADATA_UNAVAILABLE,
                    )
                }
                SharePendingStatus.EXPIRED,
                SharePendingStatus.INVALID,
                -> {
                    deleteOwned(context, ownedUri)
                    return null
                }
            }
        }
        return installReceipt(context.applicationContext, ownedUri, info)
    }

    private fun installReceipt(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
        installedInfo: ClipboardFileInfo,
    ): InstalledClipboardMedia = InstalledClipboardMedia(
        ownedUri = ownedUri,
        sharePublicationAttempted =
            installedInfo.shareOperationToken != null &&
                installedInfo.ownershipState != ClipboardMediaOwnershipState.PENDING,
        cleanupAction = {
            deletePendingInstall(context, ownedUri, installedInfo)
        },
    )

    private fun sharePendingDeadline(elapsedRealtimeMs: Long): Long {
        val now = elapsedRealtimeMs.coerceAtLeast(0L)
        return if (now > Long.MAX_VALUE - SHARE_PENDING_RETENTION_MS) {
            Long.MAX_VALUE
        } else {
            now + SHARE_PENDING_RETENTION_MS
        }
    }

    private fun allocateOwnedUri(directory: Path, type: ItemType): OwnedClipboardMediaUri {
        repeat(MAX_ID_ATTEMPTS) {
            val id = random.nextLong().ushr(1).takeIf { it > 0L } ?: return@repeat
            val ownedUri = OwnedClipboardMediaUri.create(id, type) ?: return@repeat
            if (!MetadataStore.contains(id) &&
                Files.notExists(directory.resolve(id.toString()), LinkOption.NOFOLLOW_LINKS)
            ) {
                return ownedUri
            }
        }
        throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
    }

    private fun deletePendingInstall(
        context: Context,
        ownedUri: OwnedClipboardMediaUri,
        installedInfo: ClipboardFileInfo,
    ): Boolean {
        return synchronized(mutationLock) {
            initialize(context)
            val currentInfo = MetadataStore.get(ownedUri) ?: run {
                liveInstalls.remove(ownedUri)
                return@synchronized true
            }
            if (currentInfo != installedInfo ||
                currentInfo.ownershipState != ClipboardMediaOwnershipState.PENDING ||
                currentInfo.isSystemRoot ||
                currentInfo.pasteRetainedUntilMs >
                System.currentTimeMillis().coerceAtLeast(0L)
            ) {
                return@synchronized false
            }
            deleteOwned(context, ownedUri)
        }
    }

    private fun allocatePartialPath(directory: Path, id: Long): Path {
        repeat(MAX_ID_ATTEMPTS) {
            val suffix = random.nextLong().ushr(1)
            val candidate = directory.resolve("$PARTIAL_PREFIX$id-$suffix$PARTIAL_SUFFIX")
            if (Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate
        }
        throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
    }

    private fun copyToPartial(
        source: InputStream,
        partial: Path,
        maximumBytes: Long,
        checkActive: () -> Unit,
    ): Long {
        if (maximumBytes <= 0L || maximumBytes > MAX_MEDIA_BYTES) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
        }
        try {
            FileChannel.open(
                partial,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copiedBytes = 0L
                while (copiedBytes < maximumBytes) {
                    checkActive()
                    val requestedBytes = minOf(
                        buffer.size.toLong(),
                        maximumBytes - copiedBytes,
                    ).toInt()
                    val read = readSource {
                        source.read(buffer, 0, requestedBytes)
                    }
                    if (read < 0) break
                    if (read == 0) {
                        val oneByte = readSource { source.read() }
                        if (oneByte < 0) break
                        writeFully(output, ByteBuffer.wrap(byteArrayOf(oneByte.toByte())))
                        copiedBytes = Math.addExact(copiedBytes, 1L)
                    } else {
                        writeFully(output, ByteBuffer.wrap(buffer, 0, read))
                        copiedBytes = Math.addExact(copiedBytes, read.toLong())
                    }
                }
                checkActive()
                if (copiedBytes == maximumBytes &&
                    readSource { source.read() } >= 0
                ) {
                    throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
                }
                output.force(true)
                return copiedBytes
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ClipboardMediaStorageException) {
            throw error
        } catch (_: Exception) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
        }
    }

    private inline fun readSource(read: () -> Int): Int {
        return try {
            read()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.SOURCE_UNREADABLE)
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) {
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
            }
        }
    }

    private fun publish(partial: Path, destination: Path, directory: Path) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
        }
        var moved = false
        try {
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE)
            moved = true
            forceDirectory(directory)
        } catch (_: AtomicMoveNotSupportedException) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
        } catch (_: Exception) {
            if (moved) {
                deleteBestEffort(destination)
                forceDirectoryBestEffort(directory)
            }
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
        }
    }

    private fun storageDirectory(context: Context): Path {
        synchronized(directoryLock) {
            val parent = context.noBackupFilesDir.toPath()
            val directory = parent.resolve(CLIPBOARD_FILES_PATH)
            var created = false
            try {
                Files.createDirectory(directory)
                created = true
            } catch (_: FileAlreadyExistsException) {
                // Verified below without following the existing entry.
            } catch (_: Exception) {
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
            }
            val attributes = try {
                Files.readAttributes(
                    directory,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: Exception) {
                if (created) {
                    deleteBestEffort(directory)
                    forceDirectoryBestEffort(parent)
                }
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
            }
            if (!attributes.isDirectory || attributes.isSymbolicLink) {
                if (created) {
                    deleteBestEffort(directory)
                    forceDirectoryBestEffort(parent)
                }
                throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
            }
            if (created) {
                try {
                    forceDirectory(parent)
                } catch (_: Exception) {
                    deleteBestEffort(directory)
                    forceDirectoryBestEffort(parent)
                    throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
                }
            }
            return directory
        }
    }

    private fun filePath(context: Context, id: Long): Path =
        storageDirectory(context).resolve(id.toString())

    private fun regularFileMatches(context: Context, id: Long, expectedSize: Long): Boolean {
        return regularFileMatches(filePath(context, id), expectedSize)
    }

    private fun regularFileMatches(path: Path, expectedSize: Long): Boolean {
        return try {
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() == expectedSize
        } catch (_: NoSuchFileException) {
            false
        } catch (_: Exception) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.STORAGE_UNAVAILABLE)
        }
    }

    private fun readAttributes(path: Path): BasicFileAttributes {
        return try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: Exception) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_SOURCE)
        }
    }

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun forceDirectoryBestEffort(directory: Path) {
        runCatching { forceDirectory(directory) }
    }

    private fun deleteBestEffort(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    private fun imageOrientation(type: ItemType, path: Path): Int {
        if (type != ItemType.IMAGE) return 0
        return runCatching {
            when (
                ExifInterface(path.toFile()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    private fun normalizeDisplayName(type: ItemType, displayName: String?): String {
        val fallback = when (type) {
            ItemType.IMAGE -> "Image"
            ItemType.VIDEO -> "Video"
            ItemType.TEXT -> throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_METADATA)
        }
        return normalizeClipboardMediaDisplayName(displayName) ?: fallback
    }

    private fun normalizeMimeTypes(type: ItemType, mimeTypes: List<String>): List<String> {
        when (type) {
            ItemType.IMAGE, ItemType.VIDEO -> Unit
            ItemType.TEXT -> throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_METADATA)
        }
        if (mimeTypes.isEmpty() ||
            mimeTypes.size > MAX_MEDIA_MIME_TYPES ||
            mimeTypes.any { it.length > MAX_MEDIA_MIME_TYPE_LENGTH }
        ) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_METADATA)
        }
        val normalized = mimeTypes
            .map { it.trim().lowercase() }
            .distinct()
        if (mediaType(normalized) != type) {
            throw ClipboardMediaStorageException(ClipboardMediaStorageFailure.INVALID_METADATA)
        }
        return normalized
    }

    /**
     * Repairs MIME metadata written by older versions, which persisted
     * type-specific wildcards from ClipDescription verbatim.
     *
     * A wildcard is accepted only as unambiguous image or video family
     * evidence. Mixed families and every other non-concrete form stay invalid.
     */
    internal fun normalizePersistedMediaMimeTypes(mimeTypes: List<String>): List<String>? =
        normalizeArchiveMediaMimeTypes(mimeTypes)

    private fun mediaType(mimeTypes: List<String>): ItemType? {
        if (mimeTypes.isEmpty() ||
            mimeTypes.size > MAX_MEDIA_MIME_TYPES ||
            mimeTypes.any { mimeType ->
                mimeType.length > MAX_MEDIA_MIME_TYPE_LENGTH ||
                    !MIME_TYPE.matches(mimeType.lowercase())
            }
        ) {
            return null
        }
        val hasImage = mimeTypes.any { it.startsWith("image/", ignoreCase = true) }
        val hasVideo = mimeTypes.any { it.startsWith("video/", ignoreCase = true) }
        return when {
            hasImage && !hasVideo -> ItemType.IMAGE
            hasVideo && !hasImage -> ItemType.VIDEO
            else -> null
        }
    }

    private fun currentBootCount(context: Context): Int {
        val cached = cachedBootCount
        if (cached != UNREAD_BOOT_COUNT) return cached
        return synchronized(bootCountLock) {
            val observed = cachedBootCount
            if (observed != UNREAD_BOOT_COUNT) {
                observed
            } else {
                val resolved = runCatching {
                    Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.BOOT_COUNT,
                        UNKNOWN_BOOT_COUNT,
                    ).takeIf { it >= 0 } ?: UNKNOWN_BOOT_COUNT
                }.getOrDefault(UNKNOWN_BOOT_COUNT)
                // A transient Settings failure must remain retryable.
                if (resolved >= 0) {
                    cachedBootCount = resolved
                }
                resolved
            }
        }
    }

    private object MetadataStore {
        private val lock = Any()
        private val entries = ConcurrentHashMap<Long, ClipboardFileInfo>()
        private val shareOperations = ConcurrentHashMap<String, Long>()
        private val quarantinedLegacyFiles = mutableMapOf<Long, Long>()

        @Volatile
        private var dao: ClipboardFilesDao? = null
        private var database: ClipboardFilesDatabase? = null

        fun initialize(
            context: Context,
            directory: Path,
            currentBootCount: Int,
        ) {
            if (dao != null) return
            runBlocking(Dispatchers.IO) {
                synchronized(lock) {
                    if (dao != null) return@synchronized
                    val initializedDatabase = ClipboardFilesDatabase.new(context)
                    try {
                        val initializedDao = initializedDatabase.clipboardFilesDao()
                        reconcileLegacyHistory(
                            context = context,
                            directory = directory,
                            currentBootCount = currentBootCount,
                            initializedDatabase = initializedDatabase,
                            initializedDao = initializedDao,
                        )
                        database = initializedDatabase
                        dao = initializedDao
                    } catch (error: Exception) {
                        initializedDatabase.close()
                        throw error
                    }
                }
            }
        }

        fun recoverLegacyHistoryMedia(
            context: Context,
            directory: Path,
            currentBootCount: Int,
        ) {
            runBlocking(Dispatchers.IO) {
                synchronized(lock) {
                    reconcileLegacyHistory(
                        context = context,
                        directory = directory,
                        currentBootCount = currentBootCount,
                        initializedDatabase = requireNotNull(database) {
                            "Clipboard metadata is unavailable."
                        },
                        initializedDao = requireNotNull(dao) {
                            "Clipboard metadata is unavailable."
                        },
                    )
                }
            }
        }

        private fun reconcileLegacyHistory(
            context: Context,
            directory: Path,
            currentBootCount: Int,
            initializedDatabase: ClipboardFilesDatabase,
            initializedDao: ClipboardFilesDao,
        ) {
            val loaded = initializedDao.getAll()
            val loadedById = loaded.associateBy(ClipboardFileInfo::id)
            val historyTypesById = linkedMapOf<Long, ItemType>()
            val conflictingHistoryIds = linkedSetOf<Long>()

            // Finish the streaming lookup before any database or filesystem
            // deletion. Only retain IDs backed by metadata or a regular owned
            // file, so an unlimited history cannot consume unlimited heap.
            forEachLegacyClipboardMediaHistoryRef(context) { reference ->
                val id = reference.sourceId
                if (id in conflictingHistoryIds) return@forEachLegacyClipboardMediaHistoryRef
                val previousType = historyTypesById[id]
                if (previousType == null &&
                    id !in loadedById &&
                    legacyFileSize(directory, id) == null
                ) {
                    return@forEachLegacyClipboardMediaHistoryRef
                }
                if (previousType == null || previousType == reference.type) {
                    historyTypesById[id] = reference.type
                } else {
                    historyTypesById.remove(id)
                    conflictingHistoryIds += id
                }
            }

            val nextEntries = linkedMapOf<Long, ClipboardFileInfo>()
            val nextQuarantinedFiles = linkedMapOf<Long, Long>()
            val updates = mutableListOf<ClipboardFileInfo>()
            val inserts = mutableListOf<ClipboardFileInfo>()
            val deletes = linkedSetOf<Long>()

            for (info in loaded) {
                if (info.id in conflictingHistoryIds) {
                    legacyFileSize(directory, info.id)?.let { size ->
                        nextQuarantinedFiles[info.id] = size
                    }
                    deletes += info.id
                    continue
                }

                val historyType = historyTypesById[info.id]
                val normalizedMimeTypes = normalizePersistedMediaMimeTypes(info.mimeTypes)
                val metadataType = normalizedMimeTypes?.let(::mediaType)
                val validShareBinding = shareOperationBindingHasValidShape(info)
                val valid = info.id > 0L &&
                    normalizedMimeTypes != null &&
                    regularFileMatches(directory, info)
                val normalizedInfo = when {
                    valid && (historyType == null || metadataType == historyType) -> {
                        info.copy(
                            displayName = normalizeDisplayName(
                                type = checkNotNull(metadataType),
                                displayName = info.displayName,
                            ),
                            mimeTypes = checkNotNull(normalizedMimeTypes),
                            externalCapabilityBootCount = normalizeExternalCapabilityBootCount(
                                stampedBootCount = info.externalCapabilityBootCount,
                                isSystemRoot = info.isSystemRoot,
                                pasteRetainedUntilMs = info.pasteRetainedUntilMs,
                                currentBootCount = currentBootCount,
                            ),
                            shareOperationToken =
                                info.shareOperationToken.takeIf { validShareBinding },
                            shareRequestFingerprint =
                                info.shareRequestFingerprint.takeIf { validShareBinding },
                            sharePendingBootCount =
                                info.sharePendingBootCount.takeIf { validShareBinding },
                            sharePendingDeadlineElapsedRealtimeMs =
                                info.sharePendingDeadlineElapsedRealtimeMs
                                    .takeIf { validShareBinding }
                                    ?: 0L,
                        )
                    }
                    historyType != null -> recoverFileInfo(
                        directory = directory,
                        id = info.id,
                        type = historyType,
                        previous = info,
                        currentBootCount = currentBootCount,
                    )
                    else -> null
                }
                if (normalizedInfo == null) {
                    deletes += info.id
                } else {
                    nextEntries[info.id] = normalizedInfo
                    if (normalizedInfo != info) updates += normalizedInfo
                }
            }

            for ((id, type) in historyTypesById) {
                if (id in loadedById) continue
                val recovered = recoverFileInfo(
                    directory = directory,
                    id = id,
                    type = type,
                    previous = null,
                    currentBootCount = currentBootCount,
                ) ?: continue
                nextEntries[id] = recovered
                inserts += recovered
            }
            for (id in conflictingHistoryIds) {
                if (id in nextQuarantinedFiles) continue
                legacyFileSize(directory, id)?.let { size ->
                    nextQuarantinedFiles[id] = size
                }
            }

            initializedDatabase.runInTransaction {
                deletes.forEach(initializedDao::delete)
                updates.forEach(initializedDao::update)
                if (inserts.isNotEmpty()) {
                    initializedDao.insert(*inserts.toTypedArray())
                }
            }
            entries.clear()
            entries.putAll(nextEntries)
            shareOperations.clear()
            nextEntries.values.forEach { info ->
                info.shareOperationToken
                    ?.takeIf { shareOperationBindingHasValidShape(info) }
                    ?.let { token ->
                    shareOperations[token] = info.id
                }
            }
            quarantinedLegacyFiles.clear()
            quarantinedLegacyFiles.putAll(nextQuarantinedFiles)
            cleanupOrphanedFiles(directory)
        }

        private fun recoverFileInfo(
            directory: Path,
            id: Long,
            type: ItemType,
            previous: ClipboardFileInfo?,
            currentBootCount: Int,
        ): ClipboardFileInfo? {
            if (id <= 0L || type == ItemType.TEXT) return null
            val path = directory.resolve(id.toString())
            val size = legacyFileSize(directory, id) ?: return null
            val isSystemRoot = previous?.isSystemRoot ?: false
            val pasteRetainedUntilMs = previous?.pasteRetainedUntilMs?.coerceAtLeast(0L) ?: 0L
            val familyMimeType = when (type) {
                ItemType.IMAGE -> "image/unknown"
                ItemType.VIDEO -> "video/unknown"
                ItemType.TEXT -> return null
            }
            return ClipboardFileInfo(
                id = id,
                displayName = normalizeDisplayName(type, previous?.displayName),
                size = size,
                orientation = imageOrientation(type, path),
                mimeTypes = listOf(familyMimeType),
                ownershipState = ClipboardMediaOwnershipState.ACTIVE,
                isSystemRoot = isSystemRoot,
                pasteRetainedUntilMs = pasteRetainedUntilMs,
                externalCapabilityBootCount = normalizeExternalCapabilityBootCount(
                    stampedBootCount = LEGACY_EXTERNAL_CAPABILITY_BOOT_COUNT,
                    isSystemRoot = isSystemRoot,
                    pasteRetainedUntilMs = pasteRetainedUntilMs,
                    currentBootCount = currentBootCount,
                ),
            )
        }

        private fun legacyFileSize(directory: Path, id: Long): Long? {
            if (id <= 0L) return null
            val attributes = try {
                Files.readAttributes(
                    directory.resolve(id.toString()),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: NoSuchFileException) {
                return null
            }
            return attributes.size().takeIf {
                attributes.isRegularFile && !attributes.isSymbolicLink && it > 0L
            }
        }

        fun contains(id: Long): Boolean = entries.containsKey(id)

        fun all(): List<ClipboardFileInfo> = entries.values.toList()

        fun totalBytes(): Long {
            var total = 0L
            for (info in entries.values) {
                total = Math.addExact(total, info.size)
            }
            for (size in quarantinedLegacyFiles.values) {
                total = Math.addExact(total, size)
            }
            return total
        }

        fun get(ownedUri: OwnedClipboardMediaUri): ClipboardFileInfo? {
            val info = entries[ownedUri.id] ?: return null
            if (mediaType(info.mimeTypes) != ownedUri.type) return null
            return if (ownedUri.type == ItemType.VIDEO && info.orientation != 0) {
                info.copy(orientation = 0)
            } else {
                info
            }
        }

        fun getShareOperation(token: ClipboardShareOperationToken): ClipboardFileInfo? =
            shareOperations[token.value]?.let(entries::get)

        fun insert(info: ClipboardFileInfo) {
            runBlocking(Dispatchers.IO) {
                synchronized(lock) {
                    requireNotNull(dao) { "Clipboard metadata is unavailable." }.insert(info)
                    entries[info.id] = info
                    info.shareOperationToken
                        ?.takeIf { shareOperationBindingHasValidShape(info) }
                        ?.let { token ->
                        shareOperations[token] = info.id
                    }
                }
            }
        }

        fun update(info: ClipboardFileInfo) {
            runBlocking(Dispatchers.IO) {
                synchronized(lock) {
                    requireNotNull(dao) { "Clipboard metadata is unavailable." }.update(info)
                    val previous = entries.put(info.id, info)
                    if (previous?.shareOperationToken != info.shareOperationToken ||
                        previous?.shareRequestFingerprint != info.shareRequestFingerprint ||
                        previous?.sharePendingBootCount != info.sharePendingBootCount ||
                        previous?.sharePendingDeadlineElapsedRealtimeMs !=
                        info.sharePendingDeadlineElapsedRealtimeMs
                    ) {
                        previous?.shareOperationToken?.let(shareOperations::remove)
                        info.shareOperationToken
                            ?.takeIf { shareOperationBindingHasValidShape(info) }
                            ?.let { token ->
                            shareOperations[token] = info.id
                        }
                    }
                }
            }
        }

        data class PasteRootUpdate(
            val admitted: Boolean,
            val expiredRoots: Set<OwnedClipboardMediaUri>,
        )

        fun admitPasteRoot(
            root: ClipboardFileInfo,
            now: Long,
            maxRoots: Int,
            maxBytes: Long,
            protectedRoots: Set<OwnedClipboardMediaUri>,
        ): PasteRootUpdate = updatePasteRoots(
            root = root,
            now = now,
            maxRoots = maxRoots,
            maxBytes = maxBytes,
            protectedRoots = protectedRoots,
        )

        fun expirePasteRoots(
            now: Long,
            protectedRoots: Set<OwnedClipboardMediaUri>,
        ): Set<OwnedClipboardMediaUri> = updatePasteRoots(
            root = null,
            now = now,
            maxRoots = 0,
            maxBytes = 0L,
            protectedRoots = protectedRoots,
        ).expiredRoots

        private fun updatePasteRoots(
            root: ClipboardFileInfo?,
            now: Long,
            maxRoots: Int,
            maxBytes: Long,
            protectedRoots: Set<OwnedClipboardMediaUri>,
        ): PasteRootUpdate {
            return runBlocking(Dispatchers.IO) {
                synchronized(lock) {
                    val initializedDao = requireNotNull(dao) {
                        "Clipboard metadata is unavailable."
                    }
                    val initializedDatabase = requireNotNull(database) {
                        "Clipboard metadata is unavailable."
                    }
                    val protectedIds = protectedRoots.mapTo(mutableSetOf()) { it.id }
                    val sourceEntries = entries.values.associateByTo(linkedMapOf()) { it.id }
                    val expiredRoots = linkedSetOf<OwnedClipboardMediaUri>()
                    for ((id, info) in sourceEntries.toMap()) {
                        if (info.pasteRetainedUntilMs in 1..now &&
                            id !in protectedIds
                        ) {
                            mediaType(info.mimeTypes)
                                ?.let { type -> OwnedClipboardMediaUri.create(id, type) }
                                ?.let(expiredRoots::add)
                            sourceEntries[id] = info.copy(
                                ownershipState = ClipboardMediaOwnershipState.RETIRING,
                                pasteRetainedUntilMs = 0L,
                                sharePendingBootCount = null,
                                sharePendingDeadlineElapsedRealtimeMs = 0L,
                            )
                        }
                    }
                    val activeRootSizes = sourceEntries.values
                        .filter { it.pasteRetainedUntilMs > now }
                        .associate { it.id to it.size }
                    val admitted = root == null || pasteAdmissionFits(
                        activeRootSizes = activeRootSizes,
                        candidateId = root.id,
                        candidateBytes = root.size,
                        maxRoots = maxRoots,
                        maxBytes = maxBytes,
                    )
                    if (root != null && admitted) {
                        sourceEntries[root.id] = root
                    }
                    val updates = entries.values.mapNotNull { current ->
                        sourceEntries.getValue(current.id).takeIf { it != current }
                    }
                    initializedDatabase.runInTransaction {
                        updates.forEach(initializedDao::update)
                    }
                    updates.forEach { entries[it.id] = it }
                    PasteRootUpdate(
                        admitted = admitted,
                        expiredRoots = expiredRoots,
                    )
                }
            }
        }

        fun setSystemRoots(
            ownedUris: Set<OwnedClipboardMediaUri>,
            retainExisting: Boolean,
            externalCapabilityBootCount: Int,
        ) {
            runBlocking(Dispatchers.IO) {
                synchronized(lock) {
                    val initializedDao = requireNotNull(dao) {
                        "Clipboard metadata is unavailable."
                    }
                    val initializedDatabase = requireNotNull(database) {
                        "Clipboard metadata is unavailable."
                    }
                    val updates = entries.values.mapNotNull { info ->
                        val shouldBeRoot = mediaType(info.mimeTypes)
                            ?.let { type -> OwnedClipboardMediaUri.create(info.id, type) }
                            ?.let(ownedUris::contains)
                            ?: false
                        val targetState = if (shouldBeRoot) {
                            ClipboardMediaOwnershipState.ACTIVE
                        } else {
                            info.ownershipState
                        }
                        info.copy(
                            ownershipState = targetState,
                            isSystemRoot = shouldBeRoot || retainExisting && info.isSystemRoot,
                            externalCapabilityBootCount = if (shouldBeRoot) {
                                externalCapabilityBootCount
                            } else {
                                info.externalCapabilityBootCount
                            },
                            sharePendingBootCount = if (shouldBeRoot) {
                                null
                            } else {
                                info.sharePendingBootCount
                            },
                            sharePendingDeadlineElapsedRealtimeMs = if (shouldBeRoot) {
                                0L
                            } else {
                                info.sharePendingDeadlineElapsedRealtimeMs
                            },
                        ).takeIf { it != info }
                    }
                    initializedDatabase.runInTransaction {
                        updates.forEach(initializedDao::update)
                    }
                    updates.forEach { entries[it.id] = it }
                }
            }
        }

        fun systemRoots(): Set<OwnedClipboardMediaUri> =
            entries.values
                .asSequence()
                .filter(ClipboardFileInfo::isSystemRoot)
                .mapNotNull { info ->
                    val type = mediaType(info.mimeTypes) ?: return@mapNotNull null
                    OwnedClipboardMediaUri.create(info.id, type)
                }
                .toSet()

        fun quarantinedRoots(currentBootCount: Int): Set<OwnedClipboardMediaUri> =
            entries.values
                .asSequence()
                .filter { info ->
                    externalCapabilityIsQuarantined(
                        info.externalCapabilityBootCount,
                        currentBootCount,
                    )
                }
                .mapNotNull { info ->
                    val type = mediaType(info.mimeTypes) ?: return@mapNotNull null
                    OwnedClipboardMediaUri.create(info.id, type)
                }
                .toSet()

        fun pasteRoots(now: Long): Set<OwnedClipboardMediaUri> =
            entries.values
                .asSequence()
                .filter { it.pasteRetainedUntilMs > now }
                .mapNotNull { info ->
                    val type = mediaType(info.mimeTypes) ?: return@mapNotNull null
                    OwnedClipboardMediaUri.create(info.id, type)
                }
                .toSet()

        fun delete(id: Long) {
            runBlocking(Dispatchers.IO) {
                synchronized(lock) {
                    requireNotNull(dao) { "Clipboard metadata is unavailable." }.delete(id)
                    entries.remove(id)?.shareOperationToken?.let(shareOperations::remove)
                }
            }
        }

        fun deleteBestEffort(id: Long) {
            runCatching { delete(id) }
        }

        private fun regularFileMatches(directory: Path, info: ClipboardFileInfo): Boolean {
            return ClipboardFileStorage.regularFileMatches(
                directory.resolve(info.id.toString()),
                info.size,
            )
        }

        fun cleanupOrphanedFiles(directory: Path, alreadyChanged: Boolean = false) {
            var changed = alreadyChanged
            val quarantinedIds = quarantinedLegacyFiles.keys
            Files.newDirectoryStream(directory).use { children ->
                for (child in children) {
                    val name = child.fileName.toString()
                    val orphanedPartial = name.startsWith(PARTIAL_PREFIX) &&
                        name.endsWith(PARTIAL_SUFFIX)
                    val orphanedMedia = name.toLongOrNull()
                        ?.takeIf { it > 0L }
                        ?.let { !entries.containsKey(it) && it !in quarantinedIds }
                        ?: false
                    if (orphanedPartial || orphanedMedia) {
                        changed = Files.deleteIfExists(child) || changed
                    }
                }
            }
            if (changed) forceDirectory(directory)
        }
    }
}
