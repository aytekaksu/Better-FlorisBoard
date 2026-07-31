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

import android.annotation.SuppressLint
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

internal data class RestoreStagingBudget(val maxBytes: Long, val requiredFreeBytes: Long) {
    companion object {
        val Default = RestoreStagingBudget(
            maxBytes = ArchiveLimits.Default.maxExpandedBytes,
            requiredFreeBytes = 128L shl 20,
        )
    }
}

internal enum class BackupArchiveStagingFailure {
    INVALID_BUDGET,
    PLAN_SESSION_MISMATCH,
    PLAN_INCONSISTENT,
    INSUFFICIENT_STORAGE,
    PAYLOAD_BUDGET_EXCEEDED,
    UNSAFE_STAGING_ROOT,
    DESTINATION_COLLISION,
    SYMBOLIC_LINK_DETECTED,
    ENTRY_UNAVAILABLE,
    ENTRY_DATA_INVALID,
    ENTRY_SIZE_MISMATCH,
    ENTRY_CHECKSUM_MISMATCH,
    CLIPBOARD_PAYLOAD_INVALID,
    IO_FAILURE,
    ATOMIC_PUBLISH_UNAVAILABLE,
    CLEANUP_FAILURE,
}

internal sealed interface BackupArchiveStagingResult {
    data class Valid(val stagedRestore: StagedRestore) : BackupArchiveStagingResult

    data class Invalid(
        val failure: BackupArchiveStagingFailure,
        val payloadFailure: ClipboardRestorePayloadFailure? = null,
    ) : BackupArchiveStagingResult
}

private object StagedRestoreAuthority

/**
 * Owns one fully materialized restore tree. Closing it discards that tree
 * without following links. Failed cleanup remains retryable.
 */
internal class StagedRestore internal constructor(
    authority: Any,
    internal val root: Path,
    components: List<BackupComponent>,
    val entryCount: Int,
    val stagedBytes: Long,
    private val cleanupRoot: Path,
) : Closeable {
    private val cleanupGuard = Any()
    private var cleanupComplete = false

    val components: List<BackupComponent> = Collections.unmodifiableList(components.toList())

    init {
        check(authority === StagedRestoreAuthority)
    }

    val isClosed: Boolean
        get() = synchronized(cleanupGuard) { cleanupComplete }

    override fun close() {
        synchronized(cleanupGuard) {
            if (!cleanupComplete) {
                cleanupComplete = deleteTreeNoFollow(cleanupRoot)
            }
        }
    }

    override fun toString(): String =
        "StagedRestore(components=$components, entryCount=$entryCount, stagedBytes=$stagedBytes, " +
            "closed=$isClosed)"
}

/**
 * Selectively materializes one session-owned restore plan before any live
 * state is changed. A bounded scan of the copied clipboard indexes ensures
 * only canonical media references consume staging space.
 */
internal object BackupArchiveStager {
    suspend fun stage(
        session: BackupArchiveSession,
        plan: RestorePlan,
        stagingParent: Path,
        budget: RestoreStagingBudget = RestoreStagingBudget.Default,
        stageId: UUID = UUID.randomUUID(),
        onPartialRootCreated: (Path) -> Unit = {},
    ): BackupArchiveStagingResult {
        val pendingRestore = AtomicReference<StagedRestore?>()
        try {
            return coroutineScope {
                stageWithinScope(
                    session = session,
                    plan = plan,
                    stagingParent = stagingParent,
                    budget = budget,
                    stageId = stageId,
                    onPartialRootCreated = onPartialRootCreated,
                    pendingRestore = pendingRestore,
                )
            }.also { result ->
                if (result is BackupArchiveStagingResult.Valid) {
                    pendingRestore.compareAndSet(result.stagedRestore, null)
                }
            }
        } finally {
            pendingRestore.getAndSet(null)?.let { stagedRestore ->
                withContext(NonCancellable + Dispatchers.IO) {
                    repeat(CLEANUP_ATTEMPTS) {
                        if (!stagedRestore.isClosed) stagedRestore.close()
                    }
                }
            }
        }
    }

    private suspend fun CoroutineScope.stageWithinScope(
        session: BackupArchiveSession,
        plan: RestorePlan,
        stagingParent: Path,
        budget: RestoreStagingBudget,
        stageId: UUID,
        onPartialRootCreated: (Path) -> Unit,
        pendingRestore: AtomicReference<StagedRestore?>,
    ): BackupArchiveStagingResult {
        val input = when (val validated = validateStageInput(session, plan)) {
            is StageInputResult.Invalid -> return invalid(validated.failure)
            is StageInputResult.Valid -> validated
        }

        val locations = StageLocations(stagingParent, stageId)
        val activeCopy = AtomicReference<Closeable?>()
        val operationContext = coroutineContext
        val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                closeQuietly(activeCopy.getAndSet(null))
            }
        }
        var result: BackupArchiveStagingResult? = null
        var ownershipTransferred = false
        try {
            val outcome = withContext(Dispatchers.IO) {
                stageBlocking(
                    session = session,
                    componentEntries = input.componentEntries,
                    mediaCandidates = plan.clipboardMediaCandidatesToStage,
                    selectedComponents = plan.componentsToStage,
                    sourcePackageName = session.archive.metadata.packageName,
                    declaredComponentBytes = input.declaredComponentBytes,
                    locations = locations,
                    budget = budget,
                    operationContext = operationContext,
                    activeCopy = activeCopy,
                    onPartialRootCreated = onPartialRootCreated,
                )
            }
            result = when (outcome) {
                is BlockingStageResult.Invalid -> invalid(outcome.failure, outcome.payloadFailure)

                is BlockingStageResult.Valid -> {
                    val stagedRestore = StagedRestore(
                        authority = StagedRestoreAuthority,
                        root = outcome.root,
                        components = plan.componentsToStage.map { it.component },
                        entryCount = outcome.entryCount,
                        stagedBytes = outcome.stagedBytes,
                        cleanupRoot = locations.container,
                    )
                    pendingRestore.set(stagedRestore)
                    locations.containerOwned = false
                    ownershipTransferred = true
                    BackupArchiveStagingResult.Valid(stagedRestore)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            result = invalid(BackupArchiveStagingFailure.IO_FAILURE)
        } finally {
            closeQuietly(activeCopy.getAndSet(null))
            withContext(NonCancellable) {
                cancellationWatcher.cancelAndJoin()
                if (!ownershipTransferred && !cleanupLocations(locations)) {
                    result = invalid(BackupArchiveStagingFailure.CLEANUP_FAILURE)
                }
            }
        }
        return result ?: invalid(BackupArchiveStagingFailure.IO_FAILURE)
    }

    private fun validateStageInput(session: BackupArchiveSession, plan: RestorePlan): StageInputResult {
        val componentEntries = flattenComponentEntries(plan)
            ?: return StageInputResult.Invalid(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
        if (!session.owns(plan)) {
            return StageInputResult.Invalid(BackupArchiveStagingFailure.PLAN_SESSION_MISMATCH)
        }
        val declaredComponentBytes = declaredSize(componentEntries)
            ?: return StageInputResult.Invalid(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
        return if (declaredComponentBytes == plan.declaredComponentBytes) {
            StageInputResult.Valid(componentEntries, declaredComponentBytes)
        } else {
            StageInputResult.Invalid(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
        }
    }

    private fun stageBlocking(
        session: BackupArchiveSession,
        componentEntries: List<ValidatedArchiveEntry>,
        mediaCandidates: List<ValidatedArchiveEntry>,
        selectedComponents: List<ValidatedComponent>,
        sourcePackageName: String,
        declaredComponentBytes: Long,
        locations: StageLocations,
        budget: RestoreStagingBudget,
        operationContext: CoroutineContext,
        activeCopy: AtomicReference<Closeable?>,
        onPartialRootCreated: (Path) -> Unit,
    ): BlockingStageResult = try {
        operationContext.ensureActive()
        enforceRuntimeBudget(
            stagingParent = locations.parent,
            budget = budget,
            declaredBytes = declaredComponentBytes,
        )
        val tree = SafeStageTree.create(locations)
        onPartialRootCreated(tree.root)
        operationContext.ensureActive()
        val counter = StageByteCounter(budget.maxBytes)
        stageEntries(session, componentEntries, tree, counter, operationContext, activeCopy)
        if (counter.total != declaredComponentBytes) {
            failStage(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
        }
        val referencedMedia = when (
            val result = referencedClipboardMediaEntries(
                stagedRoot = tree.root,
                selectedComponents = selectedComponents,
                mediaCandidates = mediaCandidates,
                sourcePackageName = sourcePackageName,
                operationContext = operationContext,
            )
        ) {
            is ReferencedMediaResult.Invalid -> {
                failStage(BackupArchiveStagingFailure.CLIPBOARD_PAYLOAD_INVALID, result.failure)
            }

            is ReferencedMediaResult.Valid -> result.entries
        }
        val declaredMediaBytes = declaredSize(referencedMedia)
            ?: failStage(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
        if (declaredMediaBytes > ClipboardRestorePayloadLimits.Default.maxTotalMediaBytes) {
            failStage(
                BackupArchiveStagingFailure.CLIPBOARD_PAYLOAD_INVALID,
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )
        }
        val declaredStageBytes = checkedAdd(declaredComponentBytes, declaredMediaBytes)
            ?: failStage(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
        if (declaredStageBytes > budget.maxBytes) {
            failStage(BackupArchiveStagingFailure.PAYLOAD_BUDGET_EXCEEDED)
        }
        enforceRuntimeBudget(
            stagingParent = locations.parent,
            budget = budget,
            declaredBytes = declaredMediaBytes,
        )
        stageEntries(session, referencedMedia, tree, counter, operationContext, activeCopy)
        if (counter.total != declaredStageBytes) {
            failStage(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
        }
        tree.verifyComplete(operationContext)
        operationContext.ensureActive()
        publish(locations)
        BlockingStageResult.Valid(
            root = locations.ready,
            stagedBytes = counter.total,
            entryCount = componentEntries.size + referencedMedia.size,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: StageFailureException) {
        BlockingStageResult.Invalid(error.failure, error.payloadFailure)
    } catch (_: IOException) {
        operationContext.ensureActive()
        BlockingStageResult.Invalid(BackupArchiveStagingFailure.IO_FAILURE)
    } catch (_: SecurityException) {
        BlockingStageResult.Invalid(BackupArchiveStagingFailure.IO_FAILURE)
    } catch (_: RuntimeException) {
        operationContext.ensureActive()
        BlockingStageResult.Invalid(BackupArchiveStagingFailure.IO_FAILURE)
    }

    private fun stageEntries(
        session: BackupArchiveSession,
        entries: List<ValidatedArchiveEntry>,
        tree: SafeStageTree,
        counter: StageByteCounter,
        operationContext: CoroutineContext,
        activeCopy: AtomicReference<Closeable?>,
    ) {
        entries.forEach { entry ->
            operationContext.ensureActive()
            val staged = session.withEntry(entry) { zipFile, zipEntry ->
                when (entry.kind) {
                    ArchiveEntryKind.FILE -> stageFile(
                        tree = tree,
                        entry = entry,
                        input = try {
                            zipFile.getInputStream(zipEntry)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            failStage(BackupArchiveStagingFailure.ENTRY_DATA_INVALID)
                        },
                        counter = counter,
                        operationContext = operationContext,
                        activeCopy = activeCopy,
                    )

                    ArchiveEntryKind.DIRECTORY -> tree.createDirectory(entry.archivePath)

                    ArchiveEntryKind.SYMBOLIC_LINK,
                    ArchiveEntryKind.SPECIAL,
                    -> failStage(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
                }
            }
            if (staged == null) {
                failStage(BackupArchiveStagingFailure.ENTRY_UNAVAILABLE)
            }
        }
    }

    private fun stageFile(
        tree: SafeStageTree,
        entry: ValidatedArchiveEntry,
        input: InputStream,
        counter: StageByteCounter,
        operationContext: CoroutineContext,
        activeCopy: AtomicReference<Closeable?>,
    ) {
        input.use {
            tree.openNewFile(entry.archivePath).use { output ->
                val resources = ActiveCopyResources(input, output)
                activeCopy.set(resources)
                try {
                    operationContext.ensureActive()
                    copyExact(
                        input = input,
                        output = output,
                        entry = entry,
                        counter = counter,
                        operationContext = operationContext,
                    )
                } finally {
                    activeCopy.compareAndSet(resources, null)
                }
            }
        }
    }

    private fun copyExact(
        input: InputStream,
        output: FileChannel,
        entry: ValidatedArchiveEntry,
        counter: StageByteCounter,
        operationContext: CoroutineContext,
    ) {
        val bytes = ByteArray(COPY_BUFFER_BYTES)
        val checksum = CRC32()
        var entryBytes = 0L
        while (true) {
            operationContext.ensureActive()
            val readCount = readChunk(input, bytes, operationContext)
            if (readCount < 0) break
            val readBytes = readCount.toLong()
            if (readBytes > entry.uncompressedSize - entryBytes) {
                failStage(BackupArchiveStagingFailure.ENTRY_SIZE_MISMATCH)
            }
            counter.add(readBytes)
            writeChunk(output, bytes, readCount, operationContext)
            checksum.update(bytes, 0, readCount)
            entryBytes += readBytes
        }
        verifyCopiedEntry(entry, entryBytes, checksum.value)
        forceOutput(output, operationContext)
    }

    private fun readChunk(input: InputStream, buffer: ByteArray, operationContext: CoroutineContext): Int {
        val readCount = try {
            input.read(buffer)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            operationContext.ensureActive()
            failStage(BackupArchiveStagingFailure.ENTRY_DATA_INVALID)
        }
        return readCount.takeIf { it != 0 }
            ?: failStage(BackupArchiveStagingFailure.ENTRY_DATA_INVALID)
    }

    private fun writeChunk(output: FileChannel, bytes: ByteArray, byteCount: Int, operationContext: CoroutineContext) {
        val buffer = ByteBuffer.wrap(bytes, 0, byteCount)
        while (buffer.hasRemaining()) {
            operationContext.ensureActive()
            val written = try {
                output.write(buffer)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                operationContext.ensureActive()
                failStage(BackupArchiveStagingFailure.IO_FAILURE)
            }
            if (written <= 0) {
                failStage(BackupArchiveStagingFailure.IO_FAILURE)
            }
        }
    }

    private fun verifyCopiedEntry(entry: ValidatedArchiveEntry, actualBytes: Long, actualChecksum: Long) {
        if (actualBytes != entry.uncompressedSize) {
            failStage(BackupArchiveStagingFailure.ENTRY_SIZE_MISMATCH)
        }
        if (actualChecksum != entry.crc32) {
            failStage(BackupArchiveStagingFailure.ENTRY_CHECKSUM_MISMATCH)
        }
    }

    private fun forceOutput(output: FileChannel, operationContext: CoroutineContext) {
        try {
            output.force(true)
        } catch (_: Exception) {
            operationContext.ensureActive()
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        }
    }

    private fun publish(locations: StageLocations) {
        if (!Files.notExists(locations.ready, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(locations.ready, LinkOption.NOFOLLOW_LINKS)) {
                throw existingPathFailure(locations.ready)
            }
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        }
        try {
            Files.move(
                locations.work,
                locations.ready,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            failStage(BackupArchiveStagingFailure.ATOMIC_PUBLISH_UNAVAILABLE)
        } catch (_: FileAlreadyExistsException) {
            failStage(BackupArchiveStagingFailure.DESTINATION_COLLISION)
        } catch (_: IOException) {
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        } catch (_: SecurityException) {
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        }
    }

    // Keep this check conservative instead of evicting unrelated cached data.
    @SuppressLint("UsableSpace")
    private fun enforceRuntimeBudget(stagingParent: Path, budget: RestoreStagingBudget, declaredBytes: Long) {
        if (budget.maxBytes < 0L || budget.requiredFreeBytes < 0L) {
            failStage(BackupArchiveStagingFailure.INVALID_BUDGET)
        }
        val parentAttributes = try {
            Files.readAttributes(
                stagingParent,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: Exception) {
            failStage(BackupArchiveStagingFailure.UNSAFE_STAGING_ROOT)
        }
        if (parentAttributes.isSymbolicLink || !parentAttributes.isDirectory) {
            failStage(BackupArchiveStagingFailure.UNSAFE_STAGING_ROOT)
        }
        val usableBytes = try {
            stagingParent.toFile().usableSpace
        } catch (_: Exception) {
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        }
        if (usableBytes <= 0L || usableBytes < budget.requiredFreeBytes) {
            failStage(BackupArchiveStagingFailure.INSUFFICIENT_STORAGE)
        }
        val runtimeMax = usableBytes - budget.requiredFreeBytes
        if (declaredBytes > min(budget.maxBytes, runtimeMax)) {
            failStage(BackupArchiveStagingFailure.PAYLOAD_BUDGET_EXCEEDED)
        }
    }

    private suspend fun cleanupLocations(locations: StageLocations): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            if (!locations.containerOwned) {
                true
            } else {
                deleteTreeNoFollow(locations.container).also { cleaned ->
                    if (cleaned) {
                        locations.containerOwned = false
                    }
                }
            }
        }

    private fun flattenComponentEntries(plan: RestorePlan): List<ValidatedArchiveEntry>? {
        val entries = buildList {
            plan.componentsToStage.forEach { component -> addAll(component.entries) }
        }
        val paths = HashSet<String>(entries.size)
        return entries.takeIf { list -> list.all { paths.add(it.archivePath) } }
    }

    private fun declaredSize(entries: List<ValidatedArchiveEntry>): Long? {
        var total = 0L
        entries.forEach { entry ->
            if (entry.uncompressedSize < 0L || entry.uncompressedSize > Long.MAX_VALUE - total) {
                return null
            }
            total += entry.uncompressedSize
        }
        return total
    }

    private fun checkedAdd(first: Long, second: Long): Long? =
        first.takeIf { it >= 0L && second >= 0L && it <= Long.MAX_VALUE - second }?.plus(second)

    private fun referencedClipboardMediaEntries(
        stagedRoot: Path,
        selectedComponents: List<ValidatedComponent>,
        mediaCandidates: List<ValidatedArchiveEntry>,
        sourcePackageName: String,
        operationContext: CoroutineContext,
    ): ReferencedMediaResult {
        val selectedTypes = selectedComponents
            .mapNotNullTo(linkedSetOf()) { it.component.clipboardMediaItemType() }
        if (selectedTypes.isEmpty()) return ReferencedMediaResult.Valid(emptyList())

        return when (
            val inspection = ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = stagedRoot,
                sourcePackageName = sourcePackageName,
                selectedTypes = selectedTypes,
                checkActive = operationContext::ensureActive,
            )
        ) {
            is ClipboardMediaReferenceInspectionResult.Invalid -> {
                ReferencedMediaResult.Invalid(inspection.failure)
            }

            is ClipboardMediaReferenceInspectionResult.Valid -> {
                val sourceIds = inspection.references.mapTo(linkedSetOf()) { it.sourceId }
                if (sourceIds.size != inspection.references.size) {
                    return ReferencedMediaResult.Invalid(
                        ClipboardRestorePayloadFailure.CONFLICTING_MEDIA_REFERENCE,
                    )
                }
                val entries = mediaCandidates.filter { candidate ->
                    candidate.clipboardMediaId() in sourceIds
                }
                if (entries.size != sourceIds.size) {
                    ReferencedMediaResult.Invalid(ClipboardRestorePayloadFailure.MEDIA_UNAVAILABLE)
                } else {
                    ReferencedMediaResult.Valid(entries)
                }
            }
        }
    }

    private fun BackupComponent.clipboardMediaItemType(): ItemType? = when (this) {
        BackupComponent.CLIPBOARD_IMAGES -> ItemType.IMAGE
        BackupComponent.CLIPBOARD_VIDEOS -> ItemType.VIDEO
        else -> null
    }

    private fun ValidatedArchiveEntry.clipboardMediaId(): Long? =
        archivePath.removePrefix("${BackupArchive.CLIPBOARD_MEDIA_ROOT}/").toLongOrNull()

    private fun invalid(
        failure: BackupArchiveStagingFailure,
        payloadFailure: ClipboardRestorePayloadFailure? = null,
    ): BackupArchiveStagingResult.Invalid = BackupArchiveStagingResult.Invalid(failure, payloadFailure)

    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val CLEANUP_ATTEMPTS = 2
}

private class SafeStageTree private constructor(val root: Path, private val posix: Boolean) {
    private val directories = linkedSetOf(root)
    private val files = linkedSetOf<Path>()

    fun createDirectory(archivePath: String) {
        ensureDirectory(archivePath.split('/'))
    }

    fun openNewFile(archivePath: String): FileChannel {
        val segments = archivePath.split('/')
        if (segments.isEmpty()) {
            failStage(BackupArchiveStagingFailure.PLAN_INCONSISTENT)
        }
        var parent = root
        verifyKnownDirectory(parent)
        segments.dropLast(1).forEach { segment ->
            parent = ensureChildDirectory(parent, segment)
        }
        verifyKnownDirectory(parent)
        val destination = parent.resolve(segments.last()).normalize()
        if (destination.parent != parent || destination in directories || destination in files) {
            failStage(BackupArchiveStagingFailure.DESTINATION_COLLISION)
        }
        val channel = try {
            FileChannel.open(
                destination,
                setOf<OpenOption>(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ),
                *fileAttributes(posix),
            )
        } catch (_: FileAlreadyExistsException) {
            throw existingPathFailure(destination)
        } catch (_: Exception) {
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        }
        files.add(destination)
        return channel
    }

    fun verifyComplete(operationContext: CoroutineContext) {
        val visitedDirectories = linkedSetOf<Path>()
        val visitedFiles = linkedSetOf<Path>()
        try {
            Files.walkFileTree(
                root,
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                        operationContext.ensureActive()
                        val normalized = directory.normalize()
                        if (attributes.isSymbolicLink) {
                            failStage(BackupArchiveStagingFailure.SYMBOLIC_LINK_DETECTED)
                        }
                        if (!attributes.isDirectory || normalized !in directories) {
                            failStage(BackupArchiveStagingFailure.DESTINATION_COLLISION)
                        }
                        visitedDirectories.add(normalized)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        operationContext.ensureActive()
                        val normalized = file.normalize()
                        if (attributes.isSymbolicLink) {
                            failStage(BackupArchiveStagingFailure.SYMBOLIC_LINK_DETECTED)
                        }
                        if (!attributes.isRegularFile || normalized !in files) {
                            failStage(BackupArchiveStagingFailure.DESTINATION_COLLISION)
                        }
                        visitedFiles.add(normalized)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            operationContext.ensureActive()
        } catch (error: StageFailureException) {
            throw error
        } catch (_: Exception) {
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        }
        if (visitedDirectories != directories || visitedFiles != files) {
            failStage(BackupArchiveStagingFailure.DESTINATION_COLLISION)
        }
    }

    private fun ensureDirectory(segments: List<String>) {
        var directory = root
        verifyKnownDirectory(directory)
        segments.forEach { segment ->
            directory = ensureChildDirectory(directory, segment)
        }
    }

    private fun ensureChildDirectory(parent: Path, segment: String): Path {
        verifyKnownDirectory(parent)
        val child = parent.resolve(segment).normalize()
        if (child.parent != parent || child in files) {
            failStage(BackupArchiveStagingFailure.DESTINATION_COLLISION)
        }
        if (child in directories) {
            verifyKnownDirectory(child)
            return child
        }
        try {
            Files.createDirectory(child, *directoryAttributes(posix))
        } catch (_: FileAlreadyExistsException) {
            throw existingPathFailure(child)
        } catch (_: Exception) {
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        }
        directories.add(child)
        verifyKnownDirectory(child)
        return child
    }

    private fun verifyKnownDirectory(directory: Path) {
        if (directory !in directories) {
            failStage(BackupArchiveStagingFailure.DESTINATION_COLLISION)
        }
        val attributes = try {
            Files.readAttributes(
                directory,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: Exception) {
            failStage(BackupArchiveStagingFailure.IO_FAILURE)
        }
        when {
            attributes.isSymbolicLink -> {
                failStage(BackupArchiveStagingFailure.SYMBOLIC_LINK_DETECTED)
            }

            !attributes.isDirectory -> {
                failStage(BackupArchiveStagingFailure.DESTINATION_COLLISION)
            }
        }
    }

    companion object {
        fun create(locations: StageLocations): SafeStageTree {
            val posix = Files.getFileAttributeView(
                locations.parent,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ) != null
            try {
                Files.createDirectory(locations.container, *directoryAttributes(posix))
                locations.containerOwned = true
            } catch (_: FileAlreadyExistsException) {
                throw existingPathFailure(locations.container)
            } catch (_: Exception) {
                failStage(BackupArchiveStagingFailure.IO_FAILURE)
            }
            try {
                Files.createDirectory(locations.work, *directoryAttributes(posix))
            } catch (_: FileAlreadyExistsException) {
                throw existingPathFailure(locations.work)
            } catch (_: Exception) {
                failStage(BackupArchiveStagingFailure.IO_FAILURE)
            }
            return SafeStageTree(locations.work, posix)
        }
    }
}

private class StageByteCounter(private val maxBytes: Long) {
    var total = 0L
        private set

    fun add(bytes: Long) {
        if (bytes < 0L || maxBytes < 0L || bytes > maxBytes - total) {
            failStage(BackupArchiveStagingFailure.PAYLOAD_BUDGET_EXCEEDED)
        }
        total += bytes
    }
}

private class StageLocations(parent: Path, stageId: UUID) {
    val parent: Path = parent.normalize()
    val container: Path = this.parent.resolve(".restore-stage-$stageId")
    val work: Path = container.resolve("work")
    val ready: Path = container.resolve("ready")
    var containerOwned = false
}

private sealed interface BlockingStageResult {
    data class Valid(val root: Path, val stagedBytes: Long, val entryCount: Int) : BlockingStageResult

    data class Invalid(
        val failure: BackupArchiveStagingFailure,
        val payloadFailure: ClipboardRestorePayloadFailure? = null,
    ) : BlockingStageResult
}

private sealed interface ReferencedMediaResult {
    data class Valid(val entries: List<ValidatedArchiveEntry>) : ReferencedMediaResult

    data class Invalid(val failure: ClipboardRestorePayloadFailure) : ReferencedMediaResult
}

private sealed interface StageInputResult {
    data class Valid(val componentEntries: List<ValidatedArchiveEntry>, val declaredComponentBytes: Long) :
        StageInputResult

    data class Invalid(val failure: BackupArchiveStagingFailure) : StageInputResult
}

private class StageFailureException(
    val failure: BackupArchiveStagingFailure,
    val payloadFailure: ClipboardRestorePayloadFailure? = null,
) : RuntimeException()

private fun failStage(
    failure: BackupArchiveStagingFailure,
    payloadFailure: ClipboardRestorePayloadFailure? = null,
): Nothing = throw StageFailureException(failure, payloadFailure)

private class ActiveCopyResources(private val input: InputStream, private val output: FileChannel) : Closeable {
    override fun close() {
        closeQuietly(input)
        closeQuietly(output)
    }
}

private fun existingPathFailure(path: Path): StageFailureException {
    val attributes = try {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    } catch (_: Exception) {
        null
    }
    return StageFailureException(
        if (attributes?.isSymbolicLink == true) {
            BackupArchiveStagingFailure.SYMBOLIC_LINK_DETECTED
        } else {
            BackupArchiveStagingFailure.DESTINATION_COLLISION
        },
    )
}

private fun directoryAttributes(posix: Boolean): Array<FileAttribute<*>> = if (posix) {
    arrayOf(
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        ),
    )
} else {
    emptyArray()
}

private fun fileAttributes(posix: Boolean): Array<FileAttribute<*>> = if (posix) {
    arrayOf(
        PosixFilePermissions.asFileAttribute(
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        ),
    )
} else {
    emptyArray()
}

private fun deleteTreeNoFollow(root: Path): Boolean {
    if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return true
    return try {
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        Files.notExists(root, LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }
}

private fun closeQuietly(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // The caller reports the original typed failure or cancellation.
    }
}
