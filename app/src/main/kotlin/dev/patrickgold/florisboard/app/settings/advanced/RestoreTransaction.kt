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

import dev.patrickgold.florisboard.lib.io.ZipUtils
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class RestorePreferenceTransaction(
    val stagedSource: Path,
    val snapshot: suspend (Path) -> Unit,
    val prepare: suspend (
        stagedSource: Path,
        snapshot: Path,
        canonicalDestination: Path,
    ) -> Unit,
    val apply: suspend (Path) -> Unit,
    val rollback: suspend (Path) -> Unit,
)

internal data class RestoreDirectoryTransaction(
    val stagedSource: Path,
    val liveTarget: Path,
)

internal class RestoreTransactionRollbackException(
    cause: Throwable,
    rollbackFailures: List<Throwable>,
) : IOException("Restore failed and could not be rolled back completely.", cause) {
    init {
        rollbackFailures.forEach(::addSuppressed)
    }
}

/**
 * Coordinates one restore attempt with in-process rollback.
 *
 * Incoming data and current live state are copied into private scratch space
 * before the first mutation. Once mutation starts, the final commit and any
 * required rollback run without cancellation. Scratch is not a persistent
 * journal, so this does not recover from process or power loss.
 */
internal object RestoreTransaction {
    private const val SCRATCH_CONTAINER_NAME = ".restore-transactions"
    private const val SCRATCH_PREFIX = "restore-"
    private const val ROLLBACK_ATTEMPTS = 2
    private val transactionLock = Mutex()

    suspend fun execute(
        scratchParent: Path,
        eraseExisting: Boolean,
        preferences: RestorePreferenceTransaction?,
        directories: List<RestoreDirectoryTransaction>,
        finalCommit: suspend () -> Unit,
    ) {
        require(preferences != null || directories.isNotEmpty()) {
            "A restore transaction must contain a non-clipboard component."
        }
        validatePlans(scratchParent, preferences, directories)
        transactionLock.withLock {
            executeLocked(
                scratchParent = scratchParent,
                eraseExisting = eraseExisting,
                preferences = preferences,
                directories = directories,
                finalCommit = finalCommit,
            )
        }
    }

    private suspend fun executeLocked(
        scratchParent: Path,
        eraseExisting: Boolean,
        preferences: RestorePreferenceTransaction?,
        directories: List<RestoreDirectoryTransaction>,
        finalCommit: suspend () -> Unit,
    ) {
        var scratch: RestoreScratch? = null
        var operationFailure: Throwable? = null
        try {
            scratch = RestoreScratch.create(scratchParent)
            val stagedPreference = preferences?.let { plan ->
                val destination = scratch.incoming.resolve("preferences.jetpref")
                ZipUtils.copyFileNoFollow(
                    srcFile = plan.stagedSource.toFile(),
                    dstFile = destination.toFile(),
                )
                destination
            }
            val stagedDirectories = directories.mapIndexed { index, plan ->
                val destination = scratch.incoming.resolve("tree-$index")
                ZipUtils.copyDirectoryNoFollow(
                    srcDir = plan.stagedSource.toFile(),
                    dstDir = destination.toFile(),
                )
                StagedDirectory(plan.liveTarget, destination)
            }

            val preferenceSnapshot = preferences?.let { plan ->
                val destination = scratch.snapshots.resolve("preferences.jetpref")
                plan.snapshot(destination)
                requireRegularFileNoFollow(destination)
                destination
            }
            val directorySnapshots = stagedDirectories.mapIndexed { index, staged ->
                val liveExists = Files.exists(staged.liveTarget, LinkOption.NOFOLLOW_LINKS)
                val destination = scratch.snapshots.resolve("tree-$index")
                ZipUtils.copyDirectoryNoFollow(
                    srcDir = staged.liveTarget.toFile(),
                    dstDir = destination.toFile(),
                    allowMissing = true,
                )
                DirectorySnapshot(
                    liveTarget = staged.liveTarget,
                    snapshot = destination,
                    originallyExisted = liveExists,
                )
            }
            val canonicalPreference = preferences?.let { plan ->
                val destination = scratch.incoming.resolve("canonical-preferences.jetpref")
                plan.prepare(
                    requireNotNull(stagedPreference),
                    requireNotNull(preferenceSnapshot),
                    destination,
                )
                requireRegularFileNoFollow(destination)
                destination
            }

            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                commitOrRollback(
                    eraseExisting = eraseExisting,
                    preference = preferences?.let { plan ->
                        PreparedPreference(
                            stagedSource = requireNotNull(canonicalPreference),
                            snapshot = requireNotNull(preferenceSnapshot),
                            apply = plan.apply,
                            rollback = plan.rollback,
                        )
                    },
                    directories = stagedDirectories.zip(directorySnapshots),
                    finalCommit = finalCommit,
                )
            }
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                try {
                    scratch?.close()
                } catch (cleanupFailure: Throwable) {
                    val original = operationFailure
                    if (original != null) {
                        original.addSuppressed(cleanupFailure)
                    }
                    // StagedRestore later removes the enclosing private container.
                }
            }
        }
    }

    private suspend fun commitOrRollback(
        eraseExisting: Boolean,
        preference: PreparedPreference?,
        directories: List<Pair<StagedDirectory, DirectorySnapshot>>,
        finalCommit: suspend () -> Unit,
    ) {
        var preferenceTouched = false
        val touchedDirectories = mutableListOf<DirectorySnapshot>()
        try {
            preference?.let {
                preferenceTouched = true
                it.apply(it.stagedSource)
            }
            directories.forEach { (staged, snapshot) ->
                touchedDirectories += snapshot
                applyDirectory(
                    source = staged.stagedSource,
                    target = staged.liveTarget,
                    eraseExisting = eraseExisting,
                )
            }
            finalCommit()
        } catch (failure: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            touchedDirectories.asReversed().forEach { snapshot ->
                retryRollback(rollbackFailures) {
                    restoreDirectory(snapshot)
                }
            }
            if (preferenceTouched) {
                retryRollback(rollbackFailures) {
                    requireNotNull(preference).rollback(preference.snapshot)
                }
            }
            if (rollbackFailures.isNotEmpty()) {
                throw RestoreTransactionRollbackException(failure, rollbackFailures)
            }
            throw failure
        }
    }

    private fun applyDirectory(
        source: Path,
        target: Path,
        eraseExisting: Boolean,
    ) {
        ensureTargetDirectory(target)
        if (eraseExisting) {
            deleteTreeNoFollow(target, preserveRoot = true)
        } else {
            clearMergeCollisions(source, target)
        }
        ZipUtils.copyDirectoryNoFollow(
            srcDir = source.toFile(),
            dstDir = target.toFile(),
        )
    }

    private fun restoreDirectory(snapshot: DirectorySnapshot) {
        val targetAttributes = readAttributesOrNull(snapshot.liveTarget)
        when {
            targetAttributes == null -> Unit
            targetAttributes.isSymbolicLink || !targetAttributes.isDirectory ->
                throw IOException("Restore destination changed during rollback.")
            else -> deleteTreeNoFollow(snapshot.liveTarget, preserveRoot = true)
        }
        if (snapshot.originallyExisted) {
            ensureTargetDirectory(snapshot.liveTarget)
            ZipUtils.copyDirectoryNoFollow(
                srcDir = snapshot.snapshot.toFile(),
                dstDir = snapshot.liveTarget.toFile(),
            )
        } else if (Files.exists(snapshot.liveTarget, LinkOption.NOFOLLOW_LINKS)) {
            deleteTreeNoFollow(snapshot.liveTarget, preserveRoot = false)
        }
    }

    private fun clearMergeCollisions(
        sourceRoot: Path,
        targetRoot: Path,
    ) {
        collectTreeEntries(sourceRoot).forEach { source ->
            val destination = targetRoot.resolve(source.relativePath).normalize()
            if (!destination.startsWith(targetRoot)) {
                throw IOException("Restore path escapes its destination.")
            }
            val destinationAttributes = readAttributesOrNull(destination) ?: return@forEach
            if (destinationAttributes.isSymbolicLink) {
                throw IOException("Restore destination contains a symbolic link.")
            }
            when {
                source.isDirectory && destinationAttributes.isDirectory -> Unit
                source.isDirectory && destinationAttributes.isRegularFile ->
                    Files.delete(destination)
                !source.isDirectory && destinationAttributes.isRegularFile ->
                    Files.delete(destination)
                !source.isDirectory && destinationAttributes.isDirectory ->
                    deleteTreeNoFollow(destination, preserveRoot = false)
                else -> throw IOException("Restore destination contains an unsupported entry.")
            }
        }
    }

    private fun collectTreeEntries(root: Path): List<TreeEntry> {
        val attributes = readAttributesNoFollow(root)
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw IOException("Restore source is not a regular directory.")
        }
        val entries = mutableListOf<TreeEntry>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isSymbolicLink || !attrs.isDirectory) {
                        throw IOException("Restore source contains an unsupported directory.")
                    }
                    if (dir != root) {
                        entries += TreeEntry(root.relativize(dir), isDirectory = true)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                        throw IOException("Restore source contains an unsupported file.")
                    }
                    entries += TreeEntry(root.relativize(file), isDirectory = false)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    error: IOException,
                ): FileVisitResult = throw IOException("Cannot inspect restore source.", error)
            },
        )
        return entries
    }

    private fun deleteTreeNoFollow(
        root: Path,
        preserveRoot: Boolean,
    ) {
        val attributes = readAttributesOrNull(root) ?: return
        if (attributes.isSymbolicLink || !attributes.isDirectory) {
            throw IOException("Restore destination is not a regular directory.")
        }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isSymbolicLink || !attrs.isDirectory) {
                        throw IOException("Restore destination contains an unsupported directory.")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                        throw IOException("Restore destination contains an unsupported file.")
                    }
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    error: IOException,
                ): FileVisitResult {
                    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                        return FileVisitResult.CONTINUE
                    }
                    throw IOException("Cannot inspect restore destination.", error)
                }

                override fun postVisitDirectory(
                    dir: Path,
                    error: IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    if (!preserveRoot || dir != root) {
                        Files.deleteIfExists(dir)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun ensureTargetDirectory(path: Path) {
        val attributes = readAttributesOrNull(path)
        if (attributes != null) {
            if (attributes.isSymbolicLink || !attributes.isDirectory) {
                throw IOException("Restore destination is not a regular directory.")
            }
            return
        }
        val parent = path.parent ?: throw IOException("Restore destination has no parent.")
        val parentAttributes = readAttributesNoFollow(parent)
        if (parentAttributes.isSymbolicLink || !parentAttributes.isDirectory) {
            throw IOException("Restore destination parent is unavailable.")
        }
        Files.createDirectory(path)
    }

    private fun validatePlans(
        scratchParent: Path,
        preferences: RestorePreferenceTransaction?,
        directories: List<RestoreDirectoryTransaction>,
    ) {
        val normalizedScratchParent = scratchParent.toAbsolutePath().normalize()
        val normalizedScratchContainer =
            normalizedScratchParent.resolve(SCRATCH_CONTAINER_NAME)
        val normalizedTargets = directories.map { it.liveTarget.toAbsolutePath().normalize() }
        require(normalizedTargets.toSet().size == normalizedTargets.size) {
            "Restore directory targets must be unique."
        }
        normalizedTargets.forEachIndexed { index, target ->
            require(!pathsOverlap(target, normalizedScratchContainer)) {
                "Restore scratch space overlaps a live destination."
            }
            normalizedTargets.forEachIndexed { otherIndex, other ->
                if (index != otherIndex) {
                    require(!pathsOverlap(target, other)) {
                        "Restore directory targets must not overlap."
                    }
                }
            }
        }
        preferences?.let {
            val source = it.stagedSource.toAbsolutePath().normalize()
            require(normalizedTargets.none { target -> pathsOverlap(source, target) }) {
                "Preference source overlaps a live destination."
            }
        }
        directories.forEach { plan ->
            val source = plan.stagedSource.toAbsolutePath().normalize()
            require(!pathsOverlap(source, normalizedScratchContainer)) {
                "Restore source overlaps its scratch space."
            }
            require(normalizedTargets.none { target -> pathsOverlap(source, target) }) {
                "Restore source overlaps a live destination."
            }
        }
    }

    private fun pathsOverlap(
        first: Path,
        second: Path,
    ): Boolean = first.startsWith(second) || second.startsWith(first)

    private fun requireRegularFileNoFollow(path: Path) {
        val attributes = readAttributesNoFollow(path)
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw IOException("Preference snapshot is unavailable.")
        }
    }

    private fun readAttributesOrNull(path: Path): BasicFileAttributes? {
        return try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }
    }

    private fun readAttributesNoFollow(path: Path): BasicFileAttributes =
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )

    private suspend fun retryRollback(
        failures: MutableList<Throwable>,
        block: suspend () -> Unit,
    ) {
        var firstFailure: Throwable? = null
        repeat(ROLLBACK_ATTEMPTS) { attempt ->
            try {
                block()
                return
            } catch (failure: Throwable) {
                if (attempt + 1 == ROLLBACK_ATTEMPTS) {
                    firstFailure?.let(failure::addSuppressed)
                    failures += failure
                } else {
                    firstFailure = failure
                }
            }
        }
    }

    private data class PreparedPreference(
        val stagedSource: Path,
        val snapshot: Path,
        val apply: suspend (Path) -> Unit,
        val rollback: suspend (Path) -> Unit,
    )

    private data class StagedDirectory(
        val liveTarget: Path,
        val stagedSource: Path,
    )

    private data class DirectorySnapshot(
        val liveTarget: Path,
        val snapshot: Path,
        val originallyExisted: Boolean,
    )

    private data class TreeEntry(
        val relativePath: Path,
        val isDirectory: Boolean,
    )

    private class RestoreScratch private constructor(
        val container: Path,
        val root: Path,
        val incoming: Path,
        val snapshots: Path,
    ) {
        fun close() {
            var cleanupFailure: Throwable? = null
            try {
                deleteTreeNoFollow(root, preserveRoot = false)
            } catch (failure: Throwable) {
                cleanupFailure = failure
            }
            try {
                Files.deleteIfExists(container)
            } catch (failure: Throwable) {
                val original = cleanupFailure
                if (original == null) {
                    cleanupFailure = failure
                } else {
                    original.addSuppressed(failure)
                }
            }
            cleanupFailure?.let { throw it }
        }

        companion object {
            fun create(parent: Path): RestoreScratch {
                val parentAttributes = readAttributesNoFollow(parent)
                if (parentAttributes.isSymbolicLink || !parentAttributes.isDirectory) {
                    throw IOException("Restore scratch parent is unavailable.")
                }
                val container = parent.resolve(SCRATCH_CONTAINER_NAME)
                val containerAttributes = readAttributesOrNull(container)
                if (containerAttributes == null) {
                    Files.createDirectory(container)
                } else if (containerAttributes.isSymbolicLink || !containerAttributes.isDirectory) {
                    throw IOException("Restore scratch space is unavailable.")
                }
                Files.newDirectoryStream(container).use { entries ->
                    entries.forEach { entry ->
                        if (entry.fileName.toString().startsWith(SCRATCH_PREFIX)) {
                            deleteTreeNoFollow(entry, preserveRoot = false)
                        }
                    }
                }
                val root = Files.createTempDirectory(container, SCRATCH_PREFIX)
                val incoming = Files.createDirectory(root.resolve("incoming"))
                val snapshots = Files.createDirectory(root.resolve("snapshots"))
                return RestoreScratch(container, root, incoming, snapshots)
            }
        }
    }
}
