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

import dev.patrickgold.florisboard.ime.clipboard.provider.ArchiveClipboardMediaRef
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.MAX_ARCHIVE_MEDIA_MIME_CANDIDATES
import dev.patrickgold.florisboard.ime.clipboard.provider.MAX_ARCHIVE_MEDIA_MIME_CANDIDATE_LENGTH
import dev.patrickgold.florisboard.ime.clipboard.provider.clipboardMediaAuthority
import dev.patrickgold.florisboard.ime.clipboard.provider.normalizeArchiveMediaMimeTypes
import dev.patrickgold.florisboard.ime.clipboard.provider.normalizeClipboardMediaDisplayName
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence

/**
 * Serialized clipboard history from an archive.
 *
 * The URI stays a string until it has been reduced to an inert numeric
 * reference. This type must never be inserted into the live clipboard database.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class SerializedClipboardItem(
    val id: Long,
    val type: ItemType,
    val text: String?,
    val uri: String?,
    val creationTimestampMs: Long,
    val isPinned: Boolean,
    @Serializable(with = BoundedMimeTypesSerializer::class)
    val mimeTypes: List<String>,
    val isSensitive: Boolean = false,
    val isRemoteDevice: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = BoundedDisplayNameSerializer::class)
    val displayName: String? = null,
) {
    override fun toString(): String =
        "SerializedClipboardItem(type=$type, hasText=${text != null}, hasUri=${uri != null}, " +
            "mimeTypeCount=${mimeTypes.size}, isPinned=$isPinned, isSensitive=$isSensitive, " +
            "isRemoteDevice=$isRemoteDevice, hasDisplayName=${displayName != null})"
}

internal data class ClipboardRestorePayloadLimits(
    val maxIndexBytes: Long,
    val maxTotalIndexBytes: Long,
    val maxItems: Int,
    val maxTextChars: Int,
    val maxTotalTextChars: Long,
    val maxMimeTypesPerItem: Int,
    val maxTotalMimeTypes: Long,
    val maxMimeTypeChars: Int,
    val maxTotalMimeTypeChars: Long,
    val maxUriChars: Int,
    val maxMediaBytes: Long,
    val maxTotalMediaBytes: Long,
) {
    init {
        require(maxIndexBytes in 1..Int.MAX_VALUE.toLong())
        require(maxTotalIndexBytes >= maxIndexBytes)
        require(maxItems in 0..MAX_PREPARED_ITEMS)
        require(maxTextChars in 0..MAX_PREPARED_TEXT_CHARS)
        require(maxTotalTextChars in 0L..MAX_PREPARED_TOTAL_TEXT_CHARS)
        require(maxMimeTypesPerItem in 0..ClipboardFileStorage.MAX_MEDIA_MIME_TYPES)
        require(maxTotalMimeTypes in 0L..MAX_PREPARED_TOTAL_MIME_TYPES)
        require(maxMimeTypeChars in 0..ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH)
        require(maxTotalMimeTypeChars in 0L..MAX_PREPARED_TOTAL_MIME_TYPE_CHARS)
        require(maxUriChars > 0)
        require(maxMediaBytes in 0L..ClipboardFileStorage.MAX_MEDIA_BYTES)
        require(maxTotalMediaBytes in 0L..ClipboardFileStorage.MAX_TOTAL_MEDIA_BYTES)
    }

    companion object {
        val Default = ClipboardRestorePayloadLimits(
            maxIndexBytes = ArchiveLimits.Default.maxPreferencesOrJsonBytes,
            maxTotalIndexBytes = ArchiveLimits.Default.maxPreferencesOrJsonBytes * 3L,
            maxItems = MAX_PREPARED_ITEMS,
            maxTextChars = MAX_PREPARED_TEXT_CHARS,
            maxTotalTextChars = MAX_PREPARED_TOTAL_TEXT_CHARS,
            maxMimeTypesPerItem = 16,
            maxTotalMimeTypes = MAX_PREPARED_TOTAL_MIME_TYPES,
            maxMimeTypeChars = 127,
            maxTotalMimeTypeChars = MAX_PREPARED_TOTAL_MIME_TYPE_CHARS,
            maxUriChars = 1_024,
            maxMediaBytes = ArchiveLimits.Default.maxEntryBytes,
            maxTotalMediaBytes = ClipboardFileStorage.MAX_TOTAL_MEDIA_BYTES,
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
private object BoundedMimeTypesSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = ListSerializer(String.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val values = mutableListOf<String>()
        decoder.decodeStructure(descriptor) {
            if (decodeSequentially()) {
                val size = decodeCollectionSize(descriptor)
                if (size !in 0..MAX_SERIALIZED_MIME_TYPES) {
                    throw ClipboardMimeLimitException()
                }
                repeat(size) { index ->
                    values += decodeMimeType(index)
                }
            } else {
                while (true) {
                    val index = decodeElementIndex(descriptor)
                    if (index == CompositeDecoder.DECODE_DONE) break
                    if (index !in 0 until MAX_SERIALIZED_MIME_TYPES) {
                        throw ClipboardMimeLimitException()
                    }
                    values += decodeMimeType(index)
                }
            }
        }
        return values
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        if (value.size > MAX_SERIALIZED_MIME_TYPES ||
            value.any { it.length > MAX_SERIALIZED_MIME_TYPE_CHARS }
        ) {
            throw ClipboardMimeLimitException()
        }
        encoder.encodeStructure(descriptor) {
            value.forEachIndexed { index, mimeType ->
                encodeStringElement(descriptor, index, mimeType)
            }
        }
    }

    private fun CompositeDecoder.decodeMimeType(index: Int): String {
        val value = decodeStringElement(descriptor, index)
        if (value.length > MAX_SERIALIZED_MIME_TYPE_CHARS) {
            throw ClipboardMimeLimitException()
        }
        return value
    }

    private const val MAX_SERIALIZED_MIME_TYPES = MAX_ARCHIVE_MEDIA_MIME_CANDIDATES
    private const val MAX_SERIALIZED_MIME_TYPE_CHARS = MAX_ARCHIVE_MEDIA_MIME_CANDIDATE_LENGTH
}

private class ClipboardMimeLimitException : SerializationException("Invalid clipboard MIME metadata.")

@OptIn(ExperimentalSerializationApi::class)
private object BoundedDisplayNameSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BoundedClipboardDisplayName", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        return decoder.decodeString().also { value ->
            if (value.length > ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH) {
                throw ClipboardDisplayNameLimitException()
            }
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }
        if (value.length > ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH) {
            throw ClipboardDisplayNameLimitException()
        }
        encoder.encodeNotNullMark()
        encoder.encodeString(value)
    }
}

private class ClipboardDisplayNameLimitException :
    SerializationException("Invalid clipboard display name metadata.")

private const val TEXT_PLAIN = "text/plain"
private const val MAX_PREPARED_ITEMS = 10_000
private const val MAX_PREPARED_TEXT_CHARS = 1_000_000
private const val MAX_PREPARED_TOTAL_TEXT_CHARS = 16_000_000L
private const val MAX_PREPARED_TOTAL_MIME_TYPES = 100_000L
private const val MAX_PREPARED_TOTAL_MIME_TYPE_CHARS = 1_000_000L
private val MIME_TYPE = Regex("""[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+*-]+""")

internal enum class ClipboardRestorePayloadFailure {
    EMPTY_SELECTION,
    INVALID_SOURCE,
    INDEX_UNAVAILABLE,
    INDEX_TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    LIMIT_EXCEEDED,
    INVALID_ITEM,
    INVALID_MEDIA_REFERENCE,
    CONFLICTING_MEDIA_REFERENCE,
    MEDIA_UNAVAILABLE,
}

internal sealed interface ClipboardRestorePayloadResult {
    data class Valid(val payload: PreparedClipboardRestore) : ClipboardRestorePayloadResult

    data class Invalid(val failure: ClipboardRestorePayloadFailure) : ClipboardRestorePayloadResult
}

internal sealed interface ClipboardMediaReferenceInspectionResult {
    class Valid(references: Set<ArchiveClipboardMediaRef>) :
        ClipboardMediaReferenceInspectionResult {
        val references: Set<ArchiveClipboardMediaRef> =
            Collections.unmodifiableSet(references.toCollection(linkedSetOf()))

        override fun toString(): String = "Valid(referenceCount=${references.size})"
    }

    data class Invalid(val failure: ClipboardRestorePayloadFailure) :
        ClipboardMediaReferenceInspectionResult
}

/**
 * A validated item with no archive-controlled URI capability.
 */
internal class PreparedClipboardItem internal constructor(
    val type: ItemType,
    val text: String?,
    val creationTimestampMs: Long,
    val isPinned: Boolean,
    mimeTypes: List<String>,
    val isSensitive: Boolean,
    val isRemoteDevice: Boolean,
    val mediaRef: ArchiveClipboardMediaRef?,
) {
    val mimeTypes: List<String> = Collections.unmodifiableList(mimeTypes.toList())

    init {
        require(creationTimestampMs >= 0L)
        require(text == null || text.length <= MAX_PREPARED_TEXT_CHARS)
        require(validPreparedMimeTypes(type, this.mimeTypes))
        when (type) {
            ItemType.TEXT -> {
                require(
                    text != null &&
                        mediaRef == null &&
                        this.mimeTypes == listOf(TEXT_PLAIN),
                )
            }
            ItemType.IMAGE, ItemType.VIDEO -> {
                require(mediaRef != null && mediaRef.type == type)
            }
        }
    }

    override fun toString(): String =
        "PreparedClipboardItem(type=$type, hasText=${text != null}, hasMedia=${mediaRef != null}, " +
            "mimeTypeCount=${mimeTypes.size}, isPinned=$isPinned, isSensitive=$isSensitive, " +
            "isRemoteDevice=$isRemoteDevice)"
}

internal class PreparedClipboardMedia internal constructor(
    val ref: ArchiveClipboardMediaRef,
    val stagedFile: Path,
    val byteCount: Long,
    mimeTypes: List<String>,
    displayName: String? = null,
) {
    val mimeTypes: List<String> = Collections.unmodifiableList(mimeTypes.toList())
    val displayName: String? = normalizeClipboardMediaDisplayName(displayName)

    init {
        require(byteCount in 1L..ClipboardFileStorage.MAX_MEDIA_BYTES)
        require(validPreparedMimeTypes(ref.type, this.mimeTypes))
    }

    internal fun withMetadata(
        additionalMimeTypes: List<String>,
        displayName: String?,
    ): PreparedClipboardMedia =
        PreparedClipboardMedia(
            ref = ref,
            stagedFile = stagedFile,
            byteCount = byteCount,
            mimeTypes = (mimeTypes + additionalMimeTypes).distinct(),
            displayName = this.displayName ?: displayName,
        )

    override fun toString(): String =
        "PreparedClipboardMedia(type=${ref.type}, mimeTypeCount=${mimeTypes.size}, " +
            "hasDisplayName=${displayName != null}, stagedFile=<redacted>)"
}

internal class PreparedClipboardRestore internal constructor(
    selectedTypes: Set<ItemType>,
    items: List<PreparedClipboardItem>,
    media: List<PreparedClipboardMedia>,
) {
    val selectedTypes: Set<ItemType> =
        Collections.unmodifiableSet(selectedTypes.toCollection(linkedSetOf()))
    val items: List<PreparedClipboardItem> = Collections.unmodifiableList(items.toList())
    val media: List<PreparedClipboardMedia> = Collections.unmodifiableList(media.toList())

    init {
        val limits = ClipboardRestorePayloadLimits.Default
        require(this.selectedTypes.isNotEmpty())
        require(this.items.size <= limits.maxItems)
        require(this.items.all { it.type in this.selectedTypes })
        require(this.items.all { item ->
            (item.type == ItemType.TEXT) == (item.mediaRef == null)
        })
        require(this.items.totalWithin(limits.maxTotalTextChars) { it.text?.length?.toLong() ?: 0L })
        require(this.items.totalWithin(limits.maxTotalMimeTypes) { it.mimeTypes.size.toLong() })
        require(this.items.totalWithin(limits.maxTotalMimeTypeChars) { item ->
            item.mimeTypes.sumOf(String::length).toLong()
        })
        require(this.media.totalWithin(ClipboardFileStorage.MAX_TOTAL_MEDIA_BYTES) { it.byteCount })
        val referencedMedia = this.items.mapNotNullTo(mutableSetOf(), PreparedClipboardItem::mediaRef)
        val declaredMedia = this.media.mapTo(mutableSetOf(), PreparedClipboardMedia::ref)
        require(declaredMedia.size == this.media.size && declaredMedia == referencedMedia)
        val mediaByRef = this.media.associateBy(PreparedClipboardMedia::ref)
        require(this.items.all { item ->
            item.mediaRef?.let { ref ->
                mediaByRef[ref]?.mimeTypes?.containsAll(item.mimeTypes) == true
            } ?: true
        })
    }

    fun items(type: ItemType): List<PreparedClipboardItem> =
        Collections.unmodifiableList(items.filter { it.type == type })

    override fun toString(): String =
        "PreparedClipboardRestore(selectedTypeCount=${selectedTypes.size}, itemCount=${items.size}, " +
            "mediaCount=${media.size})"
}

private inline fun <T> Iterable<T>.totalWithin(limit: Long, amount: (T) -> Long): Boolean {
    var total = 0L
    for (value in this) {
        val increment = amount(value)
        if (increment < 0L || total > limit || increment > limit - total) return false
        total += increment
    }
    return true
}

private fun validPreparedMimeTypes(type: ItemType, mimeTypes: List<String>): Boolean {
    if (mimeTypes.isEmpty() ||
        mimeTypes.size > ClipboardFileStorage.MAX_MEDIA_MIME_TYPES ||
        mimeTypes != mimeTypes.map(String::lowercase).distinct() ||
        mimeTypes.any {
            it.length > ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH ||
                '*' in it ||
                !MIME_TYPE.matches(it)
        }
    ) {
        return false
    }
    return when (type) {
        ItemType.TEXT -> mimeTypes == listOf(TEXT_PLAIN)
        ItemType.IMAGE -> mimeTypes.any { it.startsWith("image/") } &&
            mimeTypes.none { it.startsWith("video/") }
        ItemType.VIDEO -> mimeTypes.any { it.startsWith("video/") } &&
            mimeTypes.none { it.startsWith("image/") }
    }
}

internal object ClipboardRestorePayload {
    fun prepare(
        stagedRoot: Path,
        sourcePackageName: String,
        selectedTypes: Set<ItemType>,
        limits: ClipboardRestorePayloadLimits = ClipboardRestorePayloadLimits.Default,
        nowMs: Long = System.currentTimeMillis(),
        checkActive: () -> Unit = {},
    ): ClipboardRestorePayloadResult {
        val state = ValidationState(limits)
        val inspection = inspectSelectedIndexes(
            stagedRoot = stagedRoot,
            sourcePackageName = sourcePackageName,
            selectedTypes = selectedTypes,
            limits = limits,
            checkActive = checkActive,
        ) { item, type, sourceAuthority, normalizedRoot ->
            state.validateAndAdd(
                item = item,
                expectedType = type,
                sourceAuthority = sourceAuthority,
                stagedRoot = normalizedRoot,
                nowMs = nowMs,
            )
        }
        if (inspection is SelectedIndexInspectionResult.Invalid) {
            return ClipboardRestorePayloadResult.Invalid(inspection.failure)
        }
        inspection as SelectedIndexInspectionResult.Valid

        return ClipboardRestorePayloadResult.Valid(
            PreparedClipboardRestore(
                selectedTypes = inspection.orderedTypes.toCollection(linkedSetOf()),
                items = state.items,
                media = state.mediaBySourceId.values.toList(),
            ),
        )
    }

    /** Applies [prepare]'s bounded record validation without resolving media files. */
    fun inspectMediaReferences(
        stagedRoot: Path,
        sourcePackageName: String,
        selectedTypes: Set<ItemType>,
        limits: ClipboardRestorePayloadLimits = ClipboardRestorePayloadLimits.Default,
        nowMs: Long = System.currentTimeMillis(),
        checkActive: () -> Unit = {},
    ): ClipboardMediaReferenceInspectionResult {
        val state = RecordValidationState(limits)
        val inspection = inspectSelectedIndexes(
            stagedRoot = stagedRoot,
            sourcePackageName = sourcePackageName,
            selectedTypes = selectedTypes,
            limits = limits,
            checkActive = checkActive,
        ) { item, expectedType, sourceAuthority, _ ->
            when (
                val result = state.validate(
                    item = item,
                    expectedType = expectedType,
                    sourceAuthority = sourceAuthority,
                    nowMs = nowMs,
                )
            ) {
                is RecordValidationResult.Valid -> null
                is RecordValidationResult.Invalid -> result.failure
            }
        }
        return when (inspection) {
            is SelectedIndexInspectionResult.Invalid -> {
                ClipboardMediaReferenceInspectionResult.Invalid(inspection.failure)
            }

            is SelectedIndexInspectionResult.Valid -> {
                ClipboardMediaReferenceInspectionResult.Valid(state.mediaReferences())
            }
        }
    }

    private fun inspectSelectedIndexes(
        stagedRoot: Path,
        sourcePackageName: String,
        selectedTypes: Set<ItemType>,
        limits: ClipboardRestorePayloadLimits,
        checkActive: () -> Unit,
        consume: (
            item: SerializedClipboardItem,
            expectedType: ItemType,
            sourceAuthority: String,
            normalizedRoot: Path,
        ) -> ClipboardRestorePayloadFailure?,
    ): SelectedIndexInspectionResult {
        checkActive()
        if (selectedTypes.isEmpty()) {
            return SelectedIndexInspectionResult.Invalid(ClipboardRestorePayloadFailure.EMPTY_SELECTION)
        }
        if (!BackupArchive.isValidPackageName(sourcePackageName)) {
            return SelectedIndexInspectionResult.Invalid(ClipboardRestorePayloadFailure.INVALID_SOURCE)
        }
        val normalizedRoot = stagedRoot.normalize()
        if (!directoryExistsNoFollow(normalizedRoot) ||
            !directoryExistsNoFollow(normalizedRoot.resolve(BackupArchive.CLIPBOARD_ROOT))
        ) {
            return SelectedIndexInspectionResult.Invalid(ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE)
        }

        val orderedTypes = RESTORE_TYPES.filter(selectedTypes::contains)
        if (orderedTypes.size != selectedTypes.size) {
            return SelectedIndexInspectionResult.Invalid(ClipboardRestorePayloadFailure.EMPTY_SELECTION)
        }

        var totalIndexBytes = 0L
        var itemCount = 0
        val sourceAuthority = clipboardMediaAuthority(sourcePackageName)
        for (type in orderedTypes) {
            checkActive()
            val index = when (
                val result = inspectIndex(normalizedRoot.resolve(type.indexPath()), limits.maxIndexBytes)
            ) {
                is IndexInspection.Valid -> result
                is IndexInspection.Invalid -> return SelectedIndexInspectionResult.Invalid(result.failure)
            }
            totalIndexBytes = checkedAdd(totalIndexBytes, index.byteCount, limits.maxTotalIndexBytes)
                ?: return SelectedIndexInspectionResult.Invalid(
                    ClipboardRestorePayloadFailure.INDEX_TOO_LARGE,
                )
            validateUtf8(
                index = index,
                maxStringChars = maxOf(
                    limits.maxTextChars,
                    limits.maxUriChars,
                    limits.maxMimeTypeChars,
                    MIN_JSON_STRING_LIMIT,
                ),
                checkActive = checkActive,
            )?.let { failure ->
                return SelectedIndexInspectionResult.Invalid(failure)
            }
            decodeIndex(index, checkActive) { item ->
                if (itemCount >= limits.maxItems) {
                    ClipboardRestorePayloadFailure.LIMIT_EXCEEDED
                } else {
                    itemCount++
                    consume(item, type, sourceAuthority, normalizedRoot)
                }
            }?.let { failure ->
                return SelectedIndexInspectionResult.Invalid(failure)
            }
        }
        checkActive()
        return SelectedIndexInspectionResult.Valid(orderedTypes)
    }

    private sealed interface SelectedIndexInspectionResult {
        data class Valid(val orderedTypes: List<ItemType>) : SelectedIndexInspectionResult

        data class Invalid(val failure: ClipboardRestorePayloadFailure) : SelectedIndexInspectionResult
    }

    private class ValidatedClipboardRecord(
        val type: ItemType,
        val text: String?,
        val creationTimestampMs: Long,
        val isPinned: Boolean,
        val mimeTypes: List<String>,
        val isSensitive: Boolean,
        val isRemoteDevice: Boolean,
        val mediaRef: ArchiveClipboardMediaRef?,
        val displayName: String?,
    )

    private class ValidatedClipboardMediaMetadata(
        val ref: ArchiveClipboardMediaRef,
        val mimeTypes: List<String>,
        val displayName: String?,
    )

    private sealed interface RecordValidationResult {
        data class Valid(val record: ValidatedClipboardRecord) : RecordValidationResult

        data class Invalid(val failure: ClipboardRestorePayloadFailure) : RecordValidationResult
    }

    private class RecordValidationState(
        private val limits: ClipboardRestorePayloadLimits,
    ) {
        private val mediaBySourceId = linkedMapOf<Long, ValidatedClipboardMediaMetadata>()
        private var textChars = 0L
        private var mimeTypeCount = 0L
        private var mimeTypeChars = 0L

        fun mediaReferences(): Set<ArchiveClipboardMediaRef> =
            mediaBySourceId.values.mapTo(linkedSetOf()) { it.ref }

        fun validate(
            item: SerializedClipboardItem,
            expectedType: ItemType,
            sourceAuthority: String,
            nowMs: Long,
        ): RecordValidationResult {
            if (item.type != expectedType) {
                return invalidRecord(ClipboardRestorePayloadFailure.INVALID_ITEM)
            }
            val normalizedTimestampMs =
                item.creationTimestampMs.coerceIn(0L, nowMs.coerceAtLeast(0L))
            val itemTextLength = item.text?.length ?: 0
            if (itemTextLength > limits.maxTextChars) {
                return invalidRecord(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)
            }
            textChars = checkedAdd(textChars, itemTextLength.toLong(), limits.maxTotalTextChars)
                ?: return invalidRecord(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)

            if (item.mimeTypes.isEmpty()) {
                return invalidRecord(ClipboardRestorePayloadFailure.INVALID_ITEM)
            }
            mimeTypeCount = checkedAdd(
                mimeTypeCount,
                item.mimeTypes.size.toLong(),
                limits.maxTotalMimeTypes,
            ) ?: return invalidRecord(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)
            for (mimeType in item.mimeTypes) {
                mimeTypeChars = checkedAdd(
                    mimeTypeChars,
                    mimeType.length.toLong(),
                    limits.maxTotalMimeTypeChars,
                ) ?: return invalidRecord(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)
            }

            val normalizedDisplayName: String?
            val normalizedMimeTypes: List<String>
            val mediaRef: ArchiveClipboardMediaRef?
            when (expectedType) {
                ItemType.TEXT -> {
                    if (item.text == null || item.uri != null || item.displayName != null ||
                        item.mimeTypes.size != 1 ||
                        !item.mimeTypes.single().equals(TEXT_PLAIN, ignoreCase = true) ||
                        limits.maxMimeTypesPerItem < 1 ||
                        limits.maxMimeTypeChars < TEXT_PLAIN.length
                    ) {
                        return invalidRecord(ClipboardRestorePayloadFailure.INVALID_ITEM)
                    }
                    normalizedDisplayName = null
                    normalizedMimeTypes = listOf(TEXT_PLAIN)
                    mediaRef = null
                }

                ItemType.IMAGE, ItemType.VIDEO -> {
                    normalizedDisplayName =
                        normalizeClipboardMediaDisplayName(item.displayName)
                    normalizedMimeTypes =
                        normalizeArchiveMediaMimeTypes(item.mimeTypes)
                            ?: return invalidRecord(ClipboardRestorePayloadFailure.INVALID_ITEM)
                    val requiredMimePrefix =
                        if (expectedType == ItemType.IMAGE) "image/" else "video/"
                    if (normalizedMimeTypes.size > limits.maxMimeTypesPerItem ||
                        normalizedMimeTypes.any { it.length > limits.maxMimeTypeChars } ||
                        item.uri == null ||
                        item.uri.length > limits.maxUriChars ||
                        normalizedMimeTypes.any {
                            it.isClipboardMediaMime() && !it.startsWith(requiredMimePrefix)
                        } ||
                        normalizedMimeTypes.none { it.startsWith(requiredMimePrefix) }
                    ) {
                        return invalidRecord(ClipboardRestorePayloadFailure.INVALID_ITEM)
                    }
                    val parsedRef = ArchiveClipboardMediaRef.parse(
                        raw = item.uri,
                        sourceAuthority = sourceAuthority,
                        expectedType = expectedType,
                    ) ?: return invalidRecord(
                        ClipboardRestorePayloadFailure.INVALID_MEDIA_REFERENCE,
                    )
                    val existingMedia = mediaBySourceId[parsedRef.sourceId]
                    if (existingMedia != null && existingMedia.ref.type != expectedType) {
                        return invalidRecord(
                            ClipboardRestorePayloadFailure.CONFLICTING_MEDIA_REFERENCE,
                        )
                    }
                    if (existingMedia?.displayName != null &&
                        normalizedDisplayName != null &&
                        existingMedia.displayName != normalizedDisplayName
                    ) {
                        return invalidRecord(
                            ClipboardRestorePayloadFailure.CONFLICTING_MEDIA_REFERENCE,
                        )
                    }
                    val combinedMimeTypes =
                        (existingMedia?.mimeTypes.orEmpty() + normalizedMimeTypes).distinct()
                    if (combinedMimeTypes.size > limits.maxMimeTypesPerItem) {
                        return invalidRecord(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)
                    }
                    mediaRef = existingMedia?.ref ?: parsedRef
                    mediaBySourceId[parsedRef.sourceId] = ValidatedClipboardMediaMetadata(
                        ref = mediaRef,
                        mimeTypes = combinedMimeTypes,
                        displayName = existingMedia?.displayName ?: normalizedDisplayName,
                    )
                }
            }

            return RecordValidationResult.Valid(
                ValidatedClipboardRecord(
                    type = item.type,
                    text = item.text,
                    creationTimestampMs = normalizedTimestampMs,
                    isPinned = item.isPinned,
                    mimeTypes = normalizedMimeTypes,
                    isSensitive = item.isSensitive,
                    isRemoteDevice = item.isRemoteDevice,
                    mediaRef = mediaRef,
                    displayName = normalizedDisplayName,
                ),
            )
        }

        private fun invalidRecord(failure: ClipboardRestorePayloadFailure) =
            RecordValidationResult.Invalid(failure)
    }

    private class ValidationState(
        private val limits: ClipboardRestorePayloadLimits,
    ) {
        val items = mutableListOf<PreparedClipboardItem>()
        val mediaBySourceId = linkedMapOf<Long, PreparedClipboardMedia>()

        private val records = RecordValidationState(limits)
        private var mediaBytes = 0L

        fun validateAndAdd(
            item: SerializedClipboardItem,
            expectedType: ItemType,
            sourceAuthority: String,
            stagedRoot: Path,
            nowMs: Long,
        ): ClipboardRestorePayloadFailure? {
            val record = when (
                val result = records.validate(item, expectedType, sourceAuthority, nowMs)
            ) {
                is RecordValidationResult.Valid -> result.record
                is RecordValidationResult.Invalid -> return result.failure
            }
            record.mediaRef?.let { mediaRef ->
                val existingMedia = mediaBySourceId[mediaRef.sourceId]
                if (existingMedia != null) {
                    mediaBySourceId[mediaRef.sourceId] = existingMedia.withMetadata(
                        additionalMimeTypes = record.mimeTypes,
                        displayName = record.displayName,
                    )
                } else {
                    val mediaRoot = stagedRoot.resolve(BackupArchive.CLIPBOARD_MEDIA_ROOT)
                    if (!directoryExistsNoFollow(mediaRoot)) {
                        return ClipboardRestorePayloadFailure.MEDIA_UNAVAILABLE
                    }
                    val stagedFile = mediaRoot.resolve(mediaRef.sourceId.toString()).normalize()
                    val fileSize = regularFileSizeNoFollow(stagedFile)
                        ?: return ClipboardRestorePayloadFailure.MEDIA_UNAVAILABLE
                    if (fileSize <= 0L) {
                        return ClipboardRestorePayloadFailure.MEDIA_UNAVAILABLE
                    }
                    if (fileSize > limits.maxMediaBytes) {
                        return ClipboardRestorePayloadFailure.LIMIT_EXCEEDED
                    }
                    mediaBytes = checkedAdd(mediaBytes, fileSize, limits.maxTotalMediaBytes)
                        ?: return ClipboardRestorePayloadFailure.LIMIT_EXCEEDED
                    mediaBySourceId[mediaRef.sourceId] = PreparedClipboardMedia(
                        ref = mediaRef,
                        stagedFile = stagedFile,
                        byteCount = fileSize,
                        mimeTypes = record.mimeTypes,
                        displayName = record.displayName,
                    )
                }
            }

            items += PreparedClipboardItem(
                type = record.type,
                text = record.text,
                creationTimestampMs = record.creationTimestampMs,
                isPinned = record.isPinned,
                mimeTypes = record.mimeTypes,
                isSensitive = record.isSensitive,
                isRemoteDevice = record.isRemoteDevice,
                mediaRef = record.mediaRef,
            )
            return null
        }
    }

    private sealed interface IndexInspection {
        data class Valid(val path: Path, val byteCount: Long) : IndexInspection

        data class Invalid(val failure: ClipboardRestorePayloadFailure) : IndexInspection
    }

    private fun inspectIndex(
        path: Path,
        maxBytes: Long,
    ): IndexInspection {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return IndexInspection.Invalid(ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE)
        } catch (_: SecurityException) {
            return IndexInspection.Invalid(ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE)
        } catch (_: UnsupportedOperationException) {
            return IndexInspection.Invalid(ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE)
        }
        if (!attributes.isRegularFile) {
            return IndexInspection.Invalid(ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE)
        }
        if (attributes.size() > maxBytes) {
            return IndexInspection.Invalid(ClipboardRestorePayloadFailure.INDEX_TOO_LARGE)
        }
        return IndexInspection.Valid(path, attributes.size())
    }

    private fun validateUtf8(
        index: IndexInspection.Valid,
        maxStringChars: Int,
        checkActive: () -> Unit,
    ): ClipboardRestorePayloadFailure? {
        return try {
            exactSizeInput(index).use { input ->
                val reader = Channels.newReader(
                    Channels.newChannel(input),
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT),
                    DEFAULT_READ_BUFFER_SIZE,
                )
                reader.use {
                    val buffer = CharArray(DEFAULT_READ_BUFFER_SIZE)
                    val stringGuard = JsonStringLengthGuard(maxStringChars)
                    while (true) {
                        checkActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        for (indexInBuffer in 0 until count) {
                            if (!stringGuard.accept(buffer[indexInBuffer])) {
                                return ClipboardRestorePayloadFailure.LIMIT_EXCEEDED
                            }
                        }
                    }
                    input.verifyComplete()
                }
            }
            null
        } catch (_: CharacterCodingException) {
            ClipboardRestorePayloadFailure.INVALID_UTF8
        } catch (_: IOException) {
            ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE
        } catch (_: SecurityException) {
            ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE
        } catch (_: UnsupportedOperationException) {
            ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeIndex(
        index: IndexInspection.Valid,
        checkActive: () -> Unit,
        consume: (SerializedClipboardItem) -> ClipboardRestorePayloadFailure?,
    ): ClipboardRestorePayloadFailure? {
        return try {
            exactSizeInput(index).use { input ->
                val items = JSON.decodeToSequence(
                    stream = input,
                    deserializer = SerializedClipboardItem.serializer(),
                    format = DecodeSequenceMode.ARRAY_WRAPPED,
                )
                for (item in items) {
                    checkActive()
                    consume(item)?.let { return it }
                }
                input.verifyComplete()
            }
            null
        } catch (_: ClipboardMimeLimitException) {
            ClipboardRestorePayloadFailure.INVALID_ITEM
        } catch (_: ClipboardDisplayNameLimitException) {
            ClipboardRestorePayloadFailure.INVALID_ITEM
        } catch (_: SerializationException) {
            ClipboardRestorePayloadFailure.INVALID_JSON
        } catch (_: IllegalArgumentException) {
            ClipboardRestorePayloadFailure.INVALID_JSON
        } catch (_: IOException) {
            ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE
        } catch (_: SecurityException) {
            ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE
        } catch (_: UnsupportedOperationException) {
            ClipboardRestorePayloadFailure.INDEX_UNAVAILABLE
        }
    }

    private fun exactSizeInput(index: IndexInspection.Valid): ExactSizeInputStream {
        val stream = Files.newInputStream(
            index.path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        )
        return ExactSizeInputStream(stream, index.byteCount)
    }

    private class ExactSizeInputStream(
        delegate: InputStream,
        expectedBytes: Long,
    ) : FilterInputStream(delegate) {
        private var remaining = expectedBytes

        override fun read(): Int {
            if (remaining == 0L) {
                return if (super.read() < 0) -1 else throw ExactSizeMismatchException()
            }
            val value = super.read()
            if (value < 0) throw ExactSizeMismatchException()
            remaining -= 1L
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) {
                return if (super.read() < 0) -1 else throw ExactSizeMismatchException()
            }
            val count = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw ExactSizeMismatchException()
            remaining -= count.toLong()
            return count
        }

        fun verifyComplete() {
            if (remaining != 0L || super.read() >= 0) {
                throw ExactSizeMismatchException()
            }
        }
    }

    private class ExactSizeMismatchException : IOException()

    private class JsonStringLengthGuard(
        private val maxChars: Int,
    ) {
        private var inString = false
        private var escaped = false
        private var unicodeDigitsRemaining = 0
        private var unicodeValue = 0
        private var decodedChars = 0
        private var primitiveChars = 0
        private var currentStringLimit = maxChars
        private var displayNameKeyMatch = true
        private var completedDisplayNameKey = false
        private var displayNameValuePending = false

        fun accept(char: Char): Boolean {
            if (!inString) {
                when {
                    char == '"' -> {
                        inString = true
                        decodedChars = 0
                        primitiveChars = 0
                        currentStringLimit = if (displayNameValuePending) {
                            ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH
                        } else {
                            maxChars
                        }
                        displayNameValuePending = false
                        displayNameKeyMatch = true
                    }
                    char.isWhitespace() -> {
                        primitiveChars = 0
                    }
                    completedDisplayNameKey && char == ':' -> {
                        completedDisplayNameKey = false
                        displayNameValuePending = true
                        primitiveChars = 0
                    }
                    char in JSON_STRUCTURAL_CHARS -> {
                        completedDisplayNameKey = false
                        displayNameValuePending = false
                        primitiveChars = 0
                    }
                    else -> {
                        completedDisplayNameKey = false
                        displayNameValuePending = false
                        primitiveChars++
                        if (primitiveChars > MAX_JSON_PRIMITIVE_CHARS) return false
                    }
                }
                return true
            }
            if (unicodeDigitsRemaining > 0) {
                val digit = char.digitToIntOrNull(16)
                if (digit == null) {
                    displayNameKeyMatch = false
                } else {
                    unicodeValue = (unicodeValue shl 4) or digit
                }
                unicodeDigitsRemaining--
                if (unicodeDigitsRemaining == 0) {
                    escaped = false
                    return acceptDecodedChar(unicodeValue.toChar())
                }
                return true
            }
            if (escaped) {
                if (char == 'u') {
                    unicodeDigitsRemaining = 4
                    unicodeValue = 0
                } else {
                    escaped = false
                    return acceptDecodedChar(
                        when (char) {
                            '"', '\\', '/' -> char
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> {
                                displayNameKeyMatch = false
                                char
                            }
                        },
                    )
                }
                return true
            }
            return when (char) {
                '\\' -> {
                    escaped = true
                    true
                }
                '"' -> {
                    inString = false
                    completedDisplayNameKey =
                        displayNameKeyMatch && decodedChars == DISPLAY_NAME_FIELD.length
                    true
                }
                else -> acceptDecodedChar(char)
            }
        }

        private fun acceptDecodedChar(char: Char): Boolean {
            if (decodedChars >= DISPLAY_NAME_FIELD.length ||
                char != DISPLAY_NAME_FIELD[decodedChars]
            ) {
                displayNameKeyMatch = false
            }
            decodedChars++
            return decodedChars <= currentStringLimit
        }
    }

    private fun regularFileSizeNoFollow(path: Path): Long? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .takeIf(BasicFileAttributes::isRegularFile)
            ?.size()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    private fun directoryExistsNoFollow(path: Path): Boolean = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .isDirectory
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    private fun ItemType.indexPath(): String = when (this) {
        ItemType.TEXT -> BackupArchive.CLIPBOARD_TEXT_PATH
        ItemType.IMAGE -> BackupArchive.CLIPBOARD_IMAGES_PATH
        ItemType.VIDEO -> BackupArchive.CLIPBOARD_VIDEO_PATH
    }

    private fun checkedAdd(current: Long, increment: Long, limit: Long): Long? {
        if (increment < 0L || current > limit || increment > limit - current) return null
        return current + increment
    }

    private fun String.isClipboardMediaMime(): Boolean =
        startsWith("image/") || startsWith("video/")

    private const val DEFAULT_READ_BUFFER_SIZE = 8 * 1024
    private const val MIN_JSON_STRING_LIMIT = 1_024
    private const val MAX_JSON_PRIMITIVE_CHARS = 128
    private const val DISPLAY_NAME_FIELD = "displayName"
    private val JSON_STRUCTURAL_CHARS = charArrayOf('{', '}', '[', ']', ',', ':')
    private val RESTORE_TYPES = listOf(ItemType.TEXT, ItemType.IMAGE, ItemType.VIDEO)
    private val JSON = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }
}
