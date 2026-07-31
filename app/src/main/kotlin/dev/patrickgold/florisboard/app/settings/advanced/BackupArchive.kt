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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Collections

private const val MAX_CRC32 = 0xffff_ffffL

private object ArchiveValidationAuthority

private fun Any.requireArchiveValidationAuthority() {
    check(this === ArchiveValidationAuthority)
}

/**
 * Stable backup paths and the pure structural boundary shared by backup and restore.
 *
 * Archive entries are untrusted. Callers must inspect a complete, immutable archive
 * before staging payloads or changing live app data.
 */
internal object BackupArchive {
    const val METADATA_JSON_NAME = "backup_metadata.json"
    const val MANIFEST_JSON_NAME = "backup_manifest.json"
    const val CLIPBOARD_TEXT_ITEMS_JSON_NAME = "clipboard_text_items.json"
    const val CLIPBOARD_IMAGES_JSON_NAME = "clipboard_images.json"
    const val CLIPBOARD_VIDEO_JSON_NAME = "clipboard_video.json"
    const val MIN_SUPPORTED_VERSION_CODE = 64

    internal const val PREFERENCES_PATH = "jetpref_datastore/florisboard-app-prefs.jetpref"
    internal const val KEYBOARD_ROOT = "files/ime/keyboard"
    internal const val THEME_ROOT = "files/ime/theme"
    internal const val RETIRED_SPELLING_ROOT = "files/ime/spelling"
    internal const val CLIPBOARD_ROOT = "clipboard"
    internal const val CLIPBOARD_MEDIA_ROOT = "$CLIPBOARD_ROOT/clipboard_files"
    internal const val CLIPBOARD_TEXT_PATH = "$CLIPBOARD_ROOT/$CLIPBOARD_TEXT_ITEMS_JSON_NAME"
    internal const val CLIPBOARD_IMAGES_PATH = "$CLIPBOARD_ROOT/$CLIPBOARD_IMAGES_JSON_NAME"
    internal const val CLIPBOARD_VIDEO_PATH = "$CLIPBOARD_ROOT/$CLIPBOARD_VIDEO_JSON_NAME"

    fun defaultFileName(metadata: Metadata): String =
        "backup_${metadata.packageName}_${metadata.versionCode}_${metadata.timestamp}.zip"

    internal fun isValidPackageName(packageName: String): Boolean = packageName.length in 1..MAX_PACKAGE_NAME_LENGTH &&
        PACKAGE_NAME.matches(packageName)

    /**
     * Checks facts from one immutable local snapshot. [archiveSize] must be that
     * snapshot's actual byte length, not a document-provider hint.
     */
    internal fun preflight(
        entries: Sequence<ArchiveEntryFact>,
        archiveSize: Long,
        limits: ArchiveLimits = ArchiveLimits.Default,
    ): ArchiveValidation<ArchivePreflight> = ArchivePreflightInspector(entries, archiveSize, limits).preflight()

    /**
     * Finalizes a preflight with bounded decodes of the exact control entries
     * exposed by that same preflight result.
     */
    internal fun inspect(
        preflight: ArchivePreflight,
        descriptor: ArchiveDescriptor,
    ): ArchiveValidation<ValidatedArchive> = ArchiveDescriptorInspector(preflight, descriptor).inspect()

    internal val controlFileJson = Json {
        ignoreUnknownKeys = true
    }

    @Serializable
    data class Metadata(
        @SerialName("package")
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        val timestamp: Long,
    ) {
        override fun toString(): String =
            "Metadata(versionCode=$versionCode, hasPackage=${packageName.isNotBlank()}, " +
                "hasVersionName=${versionName.isNotBlank()}, hasTimestamp=${timestamp >= 0L})"
    }

    /**
     * Optional versioned declaration for new archives. Metadata stays separate so
     * older FlorisBoard versions can keep reading the original four-field object.
     */
    @Serializable
    internal data class Manifest(val formatVersion: Int, val components: List<String>) {
        override fun toString(): String = "Manifest(formatVersion=$formatVersion, componentCount=${components.size})"
    }

    const val CURRENT_MANIFEST_VERSION = 1

    private const val MAX_PACKAGE_NAME_LENGTH = 255
    private val PACKAGE_NAME = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")
}

internal enum class BackupComponent(val wireId: String) {
    KEYBOARD_EXTENSIONS("keyboard_extensions"),
    THEME_EXTENSIONS("theme_extensions"),
    CLIPBOARD_TEXT("clipboard_text"),
    CLIPBOARD_IMAGES("clipboard_images"),
    CLIPBOARD_VIDEOS("clipboard_videos"),
    PREFERENCES("preferences"),
    ;

    companion object {
        fun fromWireId(id: String): BackupComponent? = entries.firstOrNull { it.wireId == id }
    }
}

internal sealed interface DecodedArchiveFile<out T> {
    data object Absent : DecodedArchiveFile<Nothing>

    data object Invalid : DecodedArchiveFile<Nothing>

    data class Parsed<T>(val value: T) : DecodedArchiveFile<T> {
        override fun toString(): String = "Parsed(value=<redacted>)"
    }
}

internal data class ArchiveDescriptor(
    val metadata: DecodedArchiveFile<BackupArchive.Metadata>,
    val manifest: DecodedArchiveFile<BackupArchive.Manifest> = DecodedArchiveFile.Absent,
)

internal enum class ArchiveEntryKind {
    FILE,
    DIRECTORY,
    SYMBOLIC_LINK,
    SPECIAL,
}

internal enum class ArchiveCompression {
    STORED,
    DEFLATED,
    UNSUPPORTED,
}

/**
 * Central-directory facts supplied by the later ZIP adapter. Its string form
 * deliberately omits the untrusted path.
 */
internal class ArchiveEntryFact(
    internal val path: String,
    val kind: ArchiveEntryKind,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val compression: ArchiveCompression,
    val encrypted: Boolean = false,
) {
    override fun toString(): String = "ArchiveEntryFact(kind=$kind, compressedSize=$compressedSize, " +
        "uncompressedSize=$uncompressedSize, compression=$compression, encrypted=$encrypted)"
}

internal data class ArchiveLimits(
    val maxArchiveBytes: Long,
    val maxEntries: Int,
    val maxExpandedBytes: Long,
    val maxEntryBytes: Long,
    val maxMetadataBytes: Long,
    val maxManifestBytes: Long,
    val maxPreferencesOrJsonBytes: Long,
    val maxPathBytes: Int,
    val maxPathSegmentBytes: Int,
) {
    internal fun maxEntryBytesFor(path: String): Long = when (path) {
        BackupArchive.METADATA_JSON_NAME -> maxMetadataBytes

        BackupArchive.MANIFEST_JSON_NAME -> maxManifestBytes

        BackupArchive.PREFERENCES_PATH,
        BackupArchive.CLIPBOARD_TEXT_PATH,
        BackupArchive.CLIPBOARD_IMAGES_PATH,
        BackupArchive.CLIPBOARD_VIDEO_PATH,
        -> maxPreferencesOrJsonBytes

        else -> maxEntryBytes
    }

    companion object {
        val Default = ArchiveLimits(
            maxArchiveBytes = 4L shl 30,
            maxEntries = 10_000,
            maxExpandedBytes = 8L shl 30,
            maxEntryBytes = 100_000_000L,
            maxMetadataBytes = 16L shl 10,
            maxManifestBytes = 16L shl 10,
            maxPreferencesOrJsonBytes = 32L shl 20,
            maxPathBytes = 1_024,
            maxPathSegmentBytes = 255,
        )
    }
}

internal enum class ArchiveFailure {
    INVALID_ARCHIVE_SIZE,
    TOO_MANY_ENTRIES,
    UNSAFE_ENTRY,
    DUPLICATE_ENTRY,
    CONFLICTING_ENTRY,
    UNSUPPORTED_ENTRY,
    INVALID_ENTRY,
    ENTRY_TOO_LARGE,
    ARCHIVE_TOO_LARGE,
    INVALID_METADATA,
    INVALID_MANIFEST,
    UNSUPPORTED_FORMAT,
    MANIFEST_MISMATCH,
    NOTHING_TO_RESTORE,
}

internal enum class ArchiveWarning {
    RETIRED_COMPONENT_IGNORED,
    UNKNOWN_ENTRIES_IGNORED,
    UNKNOWN_COMPONENTS_IGNORED,
    UNUSED_CLIPBOARD_MEDIA_IGNORED,
}

internal enum class ArchiveSource {
    LEGACY,
    DECLARED,
}

internal sealed interface ArchiveValidation<out T> {
    data class Valid<T>(val value: T) : ArchiveValidation<T>

    data class Invalid(val failure: ArchiveFailure) : ArchiveValidation<Nothing>
}

internal class ValidatedComponent private constructor(
    val component: BackupComponent,
    internal val entries: List<ValidatedArchiveEntry>,
) {
    override fun toString(): String = "ValidatedComponent(component=$component, entryCount=${entries.size})"

    companion object {
        fun create(
            authority: Any,
            component: BackupComponent,
            entries: List<ValidatedArchiveEntry>,
        ): ValidatedComponent {
            authority.requireArchiveValidationAuthority()
            return ValidatedComponent(component, entries.immutableList())
        }
    }
}

internal class ArchivePreflight private constructor(
    val components: List<ValidatedComponent>,
    internal val clipboardMediaEntries: List<ValidatedArchiveEntry>,
    val ignoredEntryCount: Int,
    val warnings: Set<ArchiveWarning>,
    internal val metadataEntry: ValidatedArchiveEntry,
    internal val manifestEntry: ValidatedArchiveEntry?,
) {
    internal val hasManifest: Boolean
        get() = manifestEntry != null

    val availableComponents = this.components.map { it.component }.immutableSet()

    override fun toString(): String =
        "ArchivePreflight(componentCount=${components.size}, mediaEntryCount=${clipboardMediaEntries.size}, " +
            "ignoredEntryCount=$ignoredEntryCount, hasManifest=$hasManifest, warnings=$warnings)"

    companion object {
        fun create(
            authority: Any,
            components: List<ValidatedComponent>,
            clipboardMediaEntries: List<ValidatedArchiveEntry>,
            ignoredEntryCount: Int,
            warnings: Set<ArchiveWarning>,
            metadataEntry: ValidatedArchiveEntry,
            manifestEntry: ValidatedArchiveEntry?,
        ): ArchivePreflight {
            authority.requireArchiveValidationAuthority()
            return ArchivePreflight(
                components = components.immutableList(),
                clipboardMediaEntries = clipboardMediaEntries.immutableList(),
                ignoredEntryCount = ignoredEntryCount,
                warnings = warnings.immutableSet(),
                metadataEntry = metadataEntry,
                manifestEntry = manifestEntry,
            )
        }
    }
}

internal class ValidatedArchive private constructor(
    val metadata: BackupArchive.Metadata,
    val source: ArchiveSource,
    val components: List<ValidatedComponent>,
    internal val clipboardMediaEntries: List<ValidatedArchiveEntry>,
    val ignoredEntryCount: Int,
    val warnings: Set<ArchiveWarning>,
) {
    val availableComponents = components.map { it.component }.immutableSet()

    internal fun component(id: BackupComponent): ValidatedComponent? = components.firstOrNull { it.component == id }

    override fun toString(): String =
        "ValidatedArchive(metadata=$metadata, source=$source, componentCount=${components.size}, " +
            "mediaEntryCount=${clipboardMediaEntries.size}, ignoredEntryCount=$ignoredEntryCount, warnings=$warnings)"

    companion object {
        fun create(
            authority: Any,
            preflight: ArchivePreflight,
            metadata: BackupArchive.Metadata,
            source: ArchiveSource,
            extraWarnings: Set<ArchiveWarning>,
        ): ValidatedArchive {
            authority.requireArchiveValidationAuthority()
            return ValidatedArchive(
                metadata = metadata,
                source = source,
                components = preflight.components,
                clipboardMediaEntries = preflight.clipboardMediaEntries,
                ignoredEntryCount = preflight.ignoredEntryCount,
                warnings = (preflight.warnings + extraWarnings).immutableSet(),
            )
        }
    }
}

internal enum class RestoreMode {
    MERGE,
    REPLACE_SELECTED,
}

internal class RestoreRequest(val mode: RestoreMode, selectedComponents: Set<BackupComponent>) {
    val selectedComponents: Set<BackupComponent> = selectedComponents.immutableSet()
}

internal enum class RestorePlanFailure {
    EMPTY_SELECTION,
    COMPONENT_UNAVAILABLE,
    PAYLOAD_SIZE_OVERFLOW,
}

internal sealed interface RestorePlanResult {
    data class Valid(val plan: RestorePlan) : RestorePlanResult

    data class Invalid(val failure: RestorePlanFailure) : RestorePlanResult
}

internal enum class ClipboardMediaPolicy {
    NONE,

    /**
     * Add media referenced by semantically validated selected indexes without
     * deleting existing shared media.
     */
    COPY_SELECTED_REFERENCES,

    /**
     * Reconcile media only after the executor semantically validates selected
     * indexes and accounts for references retained by unselected indexes. This
     * never authorizes deleting every shared media file.
     */
    RECONCILE_SELECTED_REFERENCES,
}

internal class RestorePlan private constructor(
    val mode: RestoreMode,
    val resetComponentsOnCommit: List<BackupComponent>,
    val componentsToStage: List<ValidatedComponent>,
    internal val clipboardMediaCandidatesToStage: List<ValidatedArchiveEntry>,
    val clipboardMediaPolicy: ClipboardMediaPolicy,
    val declaredComponentBytes: Long,
) {
    override fun toString(): String = "RestorePlan(mode=$mode, resetComponentsOnCommit=$resetComponentsOnCommit, " +
        "componentsToStage=${componentsToStage.map { it.component }}, " +
        "mediaCandidateCount=${clipboardMediaCandidatesToStage.size}, " +
        "clipboardMediaPolicy=$clipboardMediaPolicy, declaredComponentBytes=$declaredComponentBytes)"

    companion object {
        fun create(
            authority: Any,
            mode: RestoreMode,
            resetComponentsOnCommit: List<BackupComponent>,
            componentsToStage: List<ValidatedComponent>,
            clipboardMediaCandidatesToStage: List<ValidatedArchiveEntry>,
            clipboardMediaPolicy: ClipboardMediaPolicy,
            declaredComponentBytes: Long,
        ): RestorePlan {
            authority.requireArchiveValidationAuthority()
            return RestorePlan(
                mode = mode,
                resetComponentsOnCommit = resetComponentsOnCommit.immutableList(),
                componentsToStage = componentsToStage.immutableList(),
                clipboardMediaCandidatesToStage = clipboardMediaCandidatesToStage.immutableList(),
                clipboardMediaPolicy = clipboardMediaPolicy,
                declaredComponentBytes = declaredComponentBytes,
            )
        }
    }
}

internal object RestorePlanner {
    fun create(archive: ValidatedArchive, request: RestoreRequest): RestorePlanResult {
        if (request.selectedComponents.isEmpty()) {
            return RestorePlanResult.Invalid(RestorePlanFailure.EMPTY_SELECTION)
        }
        val components = RESTORE_APPLY_ORDER.mapNotNull { component ->
            archive.component(component).takeIf { component in request.selectedComponents }
        }
        if (components.size != request.selectedComponents.size) {
            return RestorePlanResult.Invalid(RestorePlanFailure.COMPONENT_UNAVAILABLE)
        }

        val needsClipboardMedia = request.selectedComponents.any {
            it == BackupComponent.CLIPBOARD_IMAGES || it == BackupComponent.CLIPBOARD_VIDEOS
        }
        val mediaEntries = if (needsClipboardMedia) archive.clipboardMediaEntries else emptyList()
        val declaredComponentBytes = checkedSizeSum(
            components.asSequence().flatMap { it.entries.asSequence() },
        ) ?: return RestorePlanResult.Invalid(RestorePlanFailure.PAYLOAD_SIZE_OVERFLOW)
        val resetComponentsOnCommit = if (request.mode == RestoreMode.REPLACE_SELECTED) {
            components.map { it.component }
        } else {
            emptyList()
        }

        return RestorePlanResult.Valid(
            RestorePlan.create(
                authority = ArchiveValidationAuthority,
                mode = request.mode,
                resetComponentsOnCommit = resetComponentsOnCommit,
                componentsToStage = components,
                clipboardMediaCandidatesToStage = mediaEntries,
                clipboardMediaPolicy = when {
                    !needsClipboardMedia -> ClipboardMediaPolicy.NONE
                    request.mode == RestoreMode.MERGE -> ClipboardMediaPolicy.COPY_SELECTED_REFERENCES
                    else -> ClipboardMediaPolicy.RECONCILE_SELECTED_REFERENCES
                },
                declaredComponentBytes = declaredComponentBytes,
            ),
        )
    }

    private fun checkedSizeSum(entries: Sequence<ValidatedArchiveEntry>): Long? {
        var total = 0L
        for (entry in entries) {
            if (entry.uncompressedSize < 0L || entry.uncompressedSize > Long.MAX_VALUE - total) return null
            total += entry.uncompressedSize
        }
        return total
    }

    private val RESTORE_APPLY_ORDER = listOf(
        BackupComponent.PREFERENCES,
        BackupComponent.KEYBOARD_EXTENSIONS,
        BackupComponent.THEME_EXTENSIONS,
        BackupComponent.CLIPBOARD_TEXT,
        BackupComponent.CLIPBOARD_IMAGES,
        BackupComponent.CLIPBOARD_VIDEOS,
    )
}

internal class SafeArchivePath private constructor(internal val value: String) {
    override fun equals(other: Any?): Boolean = other is SafeArchivePath && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "<archive-entry>"

    companion object {
        fun parse(rawPath: String, kind: ArchiveEntryKind, limits: ArchiveLimits): SafeArchivePath? {
            val isDirectory = kind == ArchiveEntryKind.DIRECTORY
            if (hasInvalidRawShape(rawPath, isDirectory, limits)) return null
            val path = if (isDirectory) rawPath.dropLast(1) else rawPath
            return if (hasInvalidSegments(path, limits)) null else SafeArchivePath(path)
        }

        private fun hasInvalidRawShape(rawPath: String, isDirectory: Boolean, limits: ArchiveLimits): Boolean =
            rawPath.isEmpty() ||
                rawPath.length > limits.maxPathBytes ||
                rawPath.startsWith('/') ||
                rawPath.contains('\\') ||
                rawPath.any(Char::isISOControl) ||
                rawPath.toByteArray(StandardCharsets.UTF_8).size > limits.maxPathBytes ||
                isDirectory != rawPath.endsWith('/')

        private fun hasInvalidSegments(path: String, limits: ArchiveLimits): Boolean {
            val segments = path.split('/')
            return path.isEmpty() ||
                DRIVE_PREFIX.containsMatchIn(path) ||
                segments.any { it.isEmpty() || it == "." || it == ".." } ||
                segments.any {
                    it.length > limits.maxPathSegmentBytes ||
                        it.toByteArray(StandardCharsets.UTF_8).size > limits.maxPathSegmentBytes
                }
        }

        private val DRIVE_PREFIX = Regex("""^[A-Za-z]:""")
    }
}

internal class ValidatedArchiveEntry private constructor(
    private val path: SafeArchivePath,
    val kind: ArchiveEntryKind,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val compression: ArchiveCompression,
) {
    internal val archivePath: String
        get() = path.value

    override fun toString(): String = "ValidatedArchiveEntry(path=$path, kind=$kind, compressedSize=$compressedSize, " +
        "uncompressedSize=$uncompressedSize, compression=$compression)"

    companion object {
        fun create(authority: Any, path: SafeArchivePath, fact: ArchiveEntryFact): ValidatedArchiveEntry {
            authority.requireArchiveValidationAuthority()
            return ValidatedArchiveEntry(
                path = path,
                kind = fact.kind,
                compressedSize = fact.compressedSize,
                uncompressedSize = fact.uncompressedSize,
                crc32 = fact.crc32,
                compression = fact.compression,
            )
        }
    }
}

private fun ArchiveEntryFact.isUnsupported(): Boolean = encrypted ||
    kind == ArchiveEntryKind.SYMBOLIC_LINK ||
    kind == ArchiveEntryKind.SPECIAL ||
    compression == ArchiveCompression.UNSUPPORTED

private fun ValidatedArchiveEntry.hasInvalidHeader(): Boolean {
    if (compressedSize < 0L || uncompressedSize < 0L) return true
    if (crc32 !in 0L..MAX_CRC32) return true
    if (compression == ArchiveCompression.STORED && compressedSize != uncompressedSize) return true
    return when (kind) {
        ArchiveEntryKind.FILE -> uncompressedSize > 0L && compressedSize == 0L

        ArchiveEntryKind.DIRECTORY -> uncompressedSize != 0L || crc32 != 0L

        ArchiveEntryKind.SYMBOLIC_LINK,
        ArchiveEntryKind.SPECIAL,
        -> false
    }
}

private class ArchivePreflightInspector(
    private val factSequence: Sequence<ArchiveEntryFact>,
    private val archiveSize: Long,
    private val limits: ArchiveLimits,
) {
    fun preflight(): ArchiveValidation<ArchivePreflight> {
        validateArchiveSize()?.let { return ArchiveValidation.Invalid(it) }
        val scan = scanEntries() ?: return ArchiveValidation.Invalid(ArchiveFailure.TOO_MANY_ENTRIES)
        return preflight(scan)
    }

    private fun preflight(scan: EntryScan): ArchiveValidation<ArchivePreflight> {
        val entryFailure = validateEntries(scan)
        if (entryFailure != null) return ArchiveValidation.Invalid(entryFailure)
        return finishPreflight(scan.entries)
    }

    private fun finishPreflight(entries: List<ValidatedArchiveEntry>): ArchiveValidation<ArchivePreflight> {
        val controlFiles = validateControlFiles(entries)
        return when (controlFiles) {
            is ControlFiles.Invalid -> ArchiveValidation.Invalid(controlFiles.failure)

            is ControlFiles.Valid -> ArchiveValidation.Valid(
                buildInventory(entries).toPreflight(
                    metadataEntry = controlFiles.metadata,
                    manifestEntry = controlFiles.manifest,
                ),
            )
        }
    }

    private fun scanEntries(): EntryScan? {
        val entries = ArrayList<ValidatedArchiveEntry>(
            minOf(limits.maxEntries.coerceAtLeast(0), INITIAL_ENTRY_CAPACITY),
        )
        val failures = linkedSetOf<ArchiveFailure>()
        var entryCount = 0
        var compressedTotal = 0L
        var expandedTotal = 0L
        val iterator = factSequence.iterator()
        while (iterator.hasNext()) {
            if (entryCount >= limits.maxEntries) return null
            entryCount++
            val fact = iterator.next()
            val path = SafeArchivePath.parse(fact.path, fact.kind, limits)
            if (path == null) {
                failures += ArchiveFailure.UNSAFE_ENTRY
                continue
            }
            val entry = ValidatedArchiveEntry.create(ArchiveValidationAuthority, path, fact)
            entries += entry
            when {
                fact.isUnsupported() -> failures += ArchiveFailure.UNSUPPORTED_ENTRY
                entry.hasInvalidHeader() -> failures += ArchiveFailure.INVALID_ENTRY
            }
            if (entry.uncompressedSize > limits.maxEntryBytesFor(entry.archivePath)) {
                failures += ArchiveFailure.ENTRY_TOO_LARGE
            }
            if (entry.compressedSize >= 0L &&
                entry.uncompressedSize >= 0L &&
                ArchiveFailure.ARCHIVE_TOO_LARGE !in failures
            ) {
                val exceedsLimit = entry.compressedSize > limits.maxArchiveBytes - compressedTotal ||
                    entry.uncompressedSize > limits.maxExpandedBytes - expandedTotal
                if (exceedsLimit) {
                    failures += ArchiveFailure.ARCHIVE_TOO_LARGE
                } else {
                    compressedTotal += entry.compressedSize
                    expandedTotal += entry.uncompressedSize
                }
            }
        }
        return EntryScan(entries, failures, compressedTotal)
    }

    private fun validateArchiveSize(): ArchiveFailure? = when {
        archiveSize < 0L -> ArchiveFailure.INVALID_ARCHIVE_SIZE
        archiveSize > limits.maxArchiveBytes -> ArchiveFailure.ARCHIVE_TOO_LARGE
        else -> null
    }

    private fun validateStructure(entries: List<ValidatedArchiveEntry>): ArchiveFailure? {
        val groups = entries.groupBy { it.archivePath }.values.filter { it.size > 1 }
        if (groups.any { group -> group.map { it.kind }.distinct().size == 1 }) {
            return ArchiveFailure.DUPLICATE_ENTRY
        }
        if (groups.isNotEmpty()) return ArchiveFailure.CONFLICTING_ENTRY

        val files = entries.filter { it.kind == ArchiveEntryKind.FILE }.mapTo(hashSetOf()) { it.archivePath }
        val fileUsedAsParent = entries.any { it.archivePath.hasAncestorIn(files) }
        return ArchiveFailure.CONFLICTING_ENTRY.takeIf { fileUsedAsParent }
    }

    private fun validateEntries(scan: EntryScan): ArchiveFailure? =
        ArchiveFailure.UNSAFE_ENTRY.takeIf { it in scan.failures }
            ?: validateStructure(scan.entries)
            ?: ENTRY_FAILURE_ORDER.firstOrNull { it in scan.failures }
            ?: ArchiveFailure.INVALID_ARCHIVE_SIZE.takeIf { scan.compressedBytes > archiveSize }

    private fun validateControlFiles(entries: List<ValidatedArchiveEntry>): ControlFiles {
        val metadataEntries = entries.filter { it.archivePath == BackupArchive.METADATA_JSON_NAME }
        val metadata = metadataEntries.singleOrNull()?.takeIf { it.kind == ArchiveEntryKind.FILE }
            ?: return ControlFiles.Invalid(ArchiveFailure.INVALID_METADATA)
        val manifestEntries = entries.filter { it.archivePath == BackupArchive.MANIFEST_JSON_NAME }
        val manifest = manifestEntries.singleOrNull()
        if (manifest != null && manifest.kind != ArchiveEntryKind.FILE) {
            return ControlFiles.Invalid(ArchiveFailure.INVALID_MANIFEST)
        }
        return ControlFiles.Valid(metadata, manifest)
    }

    private fun buildInventory(entries: List<ValidatedArchiveEntry>): ArchiveInventory {
        val inventory = ArchiveInventory()
        entries.forEach { entry ->
            val component = entry.component()
            when {
                entry.isControlFile() || entry.isInfrastructureDirectory() -> Unit

                component != null -> inventory.add(component, entry)

                entry.isClipboardMedia() -> inventory.clipboardMediaEntries += entry

                entry.isAtOrBelow(BackupArchive.RETIRED_SPELLING_ROOT) -> {
                    inventory.ignore(ArchiveWarning.RETIRED_COMPONENT_IGNORED)
                }

                else -> inventory.ignore(ArchiveWarning.UNKNOWN_ENTRIES_IGNORED)
            }
        }
        if (inventory.clipboardMediaEntries.isNotEmpty() &&
            BackupComponent.CLIPBOARD_IMAGES !in inventory.components &&
            BackupComponent.CLIPBOARD_VIDEOS !in inventory.components
        ) {
            inventory.ignoredEntryCount += inventory.clipboardMediaEntries.size
            inventory.warnings += ArchiveWarning.UNUSED_CLIPBOARD_MEDIA_IGNORED
            inventory.clipboardMediaEntries.clear()
        }
        return inventory
    }

    private sealed interface ControlFiles {
        data class Valid(val metadata: ValidatedArchiveEntry, val manifest: ValidatedArchiveEntry?) : ControlFiles

        data class Invalid(val failure: ArchiveFailure) : ControlFiles
    }

    companion object {
        private const val INITIAL_ENTRY_CAPACITY = 256

        private val ENTRY_FAILURE_ORDER = listOf(
            ArchiveFailure.UNSUPPORTED_ENTRY,
            ArchiveFailure.INVALID_ENTRY,
            ArchiveFailure.ENTRY_TOO_LARGE,
            ArchiveFailure.ARCHIVE_TOO_LARGE,
        )
    }

    private class EntryScan(
        val entries: List<ValidatedArchiveEntry>,
        val failures: Set<ArchiveFailure>,
        val compressedBytes: Long,
    )
}

private class ArchiveDescriptorInspector(
    private val preflight: ArchivePreflight,
    private val descriptor: ArchiveDescriptor,
) {
    fun inspect(): ArchiveValidation<ValidatedArchive> {
        val metadata = validateMetadata()
            ?: return ArchiveValidation.Invalid(ArchiveFailure.INVALID_METADATA)
        val manifest = validateManifest()
        if (manifest is ManifestResult.Invalid) return ArchiveValidation.Invalid(manifest.failure)
        if (preflight.components.isEmpty()) {
            return ArchiveValidation.Invalid(ArchiveFailure.NOTHING_TO_RESTORE)
        }
        manifest as ManifestResult.Valid
        return ArchiveValidation.Valid(
            ValidatedArchive.create(
                authority = ArchiveValidationAuthority,
                preflight = preflight,
                metadata = metadata,
                source = manifest.source,
                extraWarnings = manifest.warnings,
            ),
        )
    }

    private fun validateMetadata(): BackupArchive.Metadata? {
        val metadata = (descriptor.metadata as? DecodedArchiveFile.Parsed)?.value ?: return null
        return metadata.takeIf {
            BackupArchive.isValidPackageName(it.packageName) &&
                it.versionCode >= BackupArchive.MIN_SUPPORTED_VERSION_CODE &&
                it.versionName.length <= MAX_VERSION_NAME_LENGTH &&
                !it.versionName.containsUnsafeDisplayCharacter() &&
                it.timestamp >= 0L
        }
    }

    private fun validateManifest(): ManifestResult = when (val decoded = descriptor.manifest) {
        DecodedArchiveFile.Absent -> {
            if (preflight.hasManifest) {
                ManifestResult.Invalid(ArchiveFailure.INVALID_MANIFEST)
            } else {
                ManifestResult.Valid(ArchiveSource.LEGACY)
            }
        }

        DecodedArchiveFile.Invalid -> ManifestResult.Invalid(ArchiveFailure.INVALID_MANIFEST)

        is DecodedArchiveFile.Parsed -> validateDeclaredManifest(decoded.value)
    }

    private fun validateDeclaredManifest(manifest: BackupArchive.Manifest): ManifestResult {
        val failure = when {
            !preflight.hasManifest -> ArchiveFailure.INVALID_MANIFEST
            manifest.formatVersion != BackupArchive.CURRENT_MANIFEST_VERSION -> ArchiveFailure.UNSUPPORTED_FORMAT
            !validComponentIds(manifest.components) -> ArchiveFailure.INVALID_MANIFEST
            else -> null
        }
        if (failure != null) return ManifestResult.Invalid(failure)
        val declared = manifest.components.mapNotNull(BackupComponent::fromWireId).toSet()
        if (declared != preflight.availableComponents) {
            return ManifestResult.Invalid(ArchiveFailure.MANIFEST_MISMATCH)
        }
        val warnings = if (manifest.components.size > declared.size) {
            setOf(ArchiveWarning.UNKNOWN_COMPONENTS_IGNORED)
        } else {
            emptySet()
        }
        return ManifestResult.Valid(ArchiveSource.DECLARED, warnings)
    }

    private fun validComponentIds(ids: List<String>): Boolean {
        if (ids.size > MAX_COMPONENT_RECORDS || ids.distinct().size != ids.size) return false
        return ids.all { id -> id.length <= MAX_COMPONENT_ID_LENGTH && COMPONENT_ID.matches(id) }
    }

    private sealed interface ManifestResult {
        data class Valid(val source: ArchiveSource, val warnings: Set<ArchiveWarning> = emptySet()) : ManifestResult

        data class Invalid(val failure: ArchiveFailure) : ManifestResult
    }

    companion object {
        private const val MAX_COMPONENT_RECORDS = 64
        private const val MAX_COMPONENT_ID_LENGTH = 64
        private const val MAX_VERSION_NAME_LENGTH = 128
        private val COMPONENT_ID = Regex("""[a-z][a-z0-9_.-]*""")

        private fun String.containsUnsafeDisplayCharacter(): Boolean {
            var index = 0
            while (index < length) {
                val codePoint = codePointAt(index)
                val type = Character.getType(codePoint)
                if (Character.isISOControl(codePoint) || isUnsafeDisplayType(type)) {
                    return true
                }
                index += Character.charCount(codePoint)
            }
            return false
        }

        private fun isUnsafeDisplayType(type: Int): Boolean = when (type) {
            Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SURROGATE.toInt(),
            -> true

            else -> false
        }
    }
}

private class ArchiveInventory {
    val components = linkedMapOf<BackupComponent, MutableList<ValidatedArchiveEntry>>()
    val clipboardMediaEntries = mutableListOf<ValidatedArchiveEntry>()
    val warnings = linkedSetOf<ArchiveWarning>()
    var ignoredEntryCount = 0

    fun add(component: BackupComponent, entry: ValidatedArchiveEntry) {
        components.getOrPut(component) { mutableListOf() } += entry
    }

    fun ignore(warning: ArchiveWarning) {
        ignoredEntryCount++
        warnings += warning
    }

    fun toPreflight(metadataEntry: ValidatedArchiveEntry, manifestEntry: ValidatedArchiveEntry?): ArchivePreflight {
        val payloads = BackupComponent.entries.mapNotNull { component ->
            components[component]?.let { entries ->
                ValidatedComponent.create(
                    ArchiveValidationAuthority,
                    component,
                    entries.sortedBy { it.archivePath },
                )
            }
        }
        return ArchivePreflight.create(
            authority = ArchiveValidationAuthority,
            components = payloads,
            clipboardMediaEntries = clipboardMediaEntries.sortedBy { it.archivePath },
            ignoredEntryCount = ignoredEntryCount,
            warnings = ArchiveWarning.entries.filterTo(linkedSetOf()) { it in warnings },
            metadataEntry = metadataEntry,
            manifestEntry = manifestEntry,
        )
    }
}

private fun ValidatedArchiveEntry.component(): BackupComponent? = when {
    archivePath == BackupArchive.PREFERENCES_PATH && kind == ArchiveEntryKind.FILE -> {
        BackupComponent.PREFERENCES
    }

    isComponentTree(BackupArchive.KEYBOARD_ROOT) -> BackupComponent.KEYBOARD_EXTENSIONS

    isComponentTree(BackupArchive.THEME_ROOT) -> BackupComponent.THEME_EXTENSIONS

    archivePath == BackupArchive.CLIPBOARD_TEXT_PATH && kind == ArchiveEntryKind.FILE -> {
        BackupComponent.CLIPBOARD_TEXT
    }

    archivePath == BackupArchive.CLIPBOARD_IMAGES_PATH && kind == ArchiveEntryKind.FILE -> {
        BackupComponent.CLIPBOARD_IMAGES
    }

    archivePath == BackupArchive.CLIPBOARD_VIDEO_PATH && kind == ArchiveEntryKind.FILE -> {
        BackupComponent.CLIPBOARD_VIDEOS
    }

    else -> null
}

private fun ValidatedArchiveEntry.isComponentTree(root: String): Boolean =
    (archivePath == root && kind == ArchiveEntryKind.DIRECTORY) ||
        archivePath.startsWith("$root/")

private fun ValidatedArchiveEntry.isAtOrBelow(root: String): Boolean =
    archivePath == root || archivePath.startsWith("$root/")

private fun ValidatedArchiveEntry.isControlFile(): Boolean = archivePath == BackupArchive.METADATA_JSON_NAME ||
    archivePath == BackupArchive.MANIFEST_JSON_NAME

private fun ValidatedArchiveEntry.isClipboardMedia(): Boolean {
    if (kind != ArchiveEntryKind.FILE || !archivePath.startsWith("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/")) {
        return false
    }
    val relativePath = archivePath.removePrefix("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/")
    val mediaId = relativePath.toLongOrNull()
    return '/' !in relativePath &&
        relativePath.all(Char::isDigit) &&
        mediaId?.toString() == relativePath
}

private fun ValidatedArchiveEntry.isInfrastructureDirectory(): Boolean {
    if (kind != ArchiveEntryKind.DIRECTORY) return false
    return archivePath in INFRASTRUCTURE_DIRECTORIES
}

private fun String.hasAncestorIn(paths: Set<String>): Boolean {
    var slash = indexOf('/')
    while (slash >= 0) {
        if (substring(0, slash) in paths) return true
        slash = indexOf('/', startIndex = slash + 1)
    }
    return false
}

private val INFRASTRUCTURE_DIRECTORIES = setOf(
    "jetpref_datastore",
    "files",
    "files/ime",
    BackupArchive.CLIPBOARD_ROOT,
    BackupArchive.CLIPBOARD_MEDIA_ROOT,
)

private fun <T> Iterable<T>.immutableList(): List<T> = Collections.unmodifiableList(toList())

private fun <T> Iterable<T>.immutableSet(): Set<T> = Collections.unmodifiableSet(toCollection(linkedSetOf()))
