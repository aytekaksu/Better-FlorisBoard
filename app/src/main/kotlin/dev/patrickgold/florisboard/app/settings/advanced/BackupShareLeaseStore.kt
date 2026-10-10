/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.Context
import android.net.Uri
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Private storage for pending and launched backup shares. */
internal class BackupShareLeaseStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = appContext.noBackupFilesDir.toPath().resolve(ROOT_DIR)

    data class Lease(val uri: Uri, val id: String)
    data class Resolved(val path: Path, val displayName: String, val size: Long)

    /** Moves one validated ZIP; the caller still owns and must close its staging workspace. */
    fun publish(validatedZip: File): Lease = synchronized(lock) {
        val source = validatedZip.toPath()
        val name = validatedZip.name
        if (!isSafeArchiveName(name) || !Files.isRegularFile(source, NOFOLLOW_LINKS)) {
            throw IOException("Backup share source is unavailable.")
        }
        val size = Files.size(source)
        if (size <= 0L || size > ArchiveLimits.Default.maxArchiveBytes) {
            throw IOException("Backup share source is unavailable.")
        }
        ensureRoot()
        val now = System.currentTimeMillis()
        pruneExpiredLocked(now)
        val active = ownedDirectories().toList()
        val usedBytes = active.sumOf { dir ->
            Files.newDirectoryStream(dir).use { children ->
                children.sumOf { child ->
                    if (Files.isRegularFile(child, NOFOLLOW_LINKS)) Files.size(child) else 0L
                }
            }
        }
        if (active.size >= MAX_ACTIVE_LEASES || size > MAX_ACTIVE_BYTES - usedBytes) {
            throw BackupShareCapacityException()
        }

        val id = UUID.randomUUID().toString()
        val dir = root.resolve(id)
        Files.createDirectory(dir)
        val expiry = now + LEASE_DURATION_MS
        try {
            writeExpiry(dir, expiry)
            // Persist the expiry wake-up before the sensitive ZIP enters this directory.
            BackupShareCleanupScheduler.scheduleExpiry(appContext, id, expiry)
            Files.move(source, dir.resolve(name), ATOMIC_MOVE)
            Lease(buildUri(id, name), id)
        } catch (error: Exception) {
            deleteDirectory(dir)
            throw error
        }
    }

    /** Extends a pending share just before chooser launch; its URI stays unchanged. */
    fun renewPending(lease: Lease): Uri = synchronized(lock) {
        val parsed = parseUri(lease.uri)
        if (parsed?.first != lease.id || !Files.isDirectory(root.resolve(lease.id), NOFOLLOW_LINKS)) {
            throw IOException("Backup share source is unavailable.")
        }
        val dir = root.resolve(lease.id)
        if (!Files.isRegularFile(dir.resolve(parsed.second), NOFOLLOW_LINKS)) {
            throw IOException("Backup share source is unavailable.")
        }
        val expiry = System.currentTimeMillis() + LEASE_DURATION_MS
        try {
            // Replacing the old wake-up first ensures a process crash cannot leave
            // a newly extended lease with no future cleanup job.
            BackupShareCleanupScheduler.scheduleExpiry(appContext, lease.id, expiry)
            writeExpiry(dir, expiry)
        } catch (error: Exception) {
            // This is still route-owned. If its durable wake-up cannot be renewed,
            // fail the chooser launch and leave no private ZIP waiting for startup.
            deleteDirectory(dir)
            throw error
        }
        lease.uri
    }

    /** An expired URI never resolves, even if background deletion is delayed. */
    fun resolve(uri: Uri): Resolved? = synchronized(lock) {
        val (id, name) = parseUri(uri) ?: return null
        val dir = root.resolve(id)
        if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) return null
        val expiry = readExpiry(dir) ?: return null
        if (expiry <= System.currentTimeMillis()) return null
        val archive = dir.resolve(name)
        if (!Files.isRegularFile(archive, NOFOLLOW_LINKS)) return null
        try {
            Resolved(archive, name, Files.size(archive))
        } catch (_: IOException) {
            null
        }
    }

    fun release(lease: Lease) = synchronized(lock) {
        if (parseUri(lease.uri)?.first == lease.id) deleteDirectory(root.resolve(lease.id))
    }

    /** The route can request cleanup after its ViewModel scope has been cancelled. */
    fun requestRelease(lease: Lease) {
        cleanupScope.launch {
            try {
                release(lease)
            } catch (error: Exception) {
                flogError { "Backup share cleanup failed: failureClass=${error.javaClass.simpleName}" }
            }
        }
    }

    fun pruneExpired(): Int = synchronized(lock) {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return 0
        pruneExpiredLocked(System.currentTimeMillis())
    }

    private fun pruneExpiredLocked(now: Long): Int {
        var removed = 0
        for (dir in ownedDirectories()) {
            if ((readExpiry(dir) ?: Long.MIN_VALUE) <= now || !hasArchive(dir)) {
                deleteDirectory(dir)
                removed++
            }
        }
        return removed
    }

    private fun ensureRoot() {
        if (Files.exists(root, NOFOLLOW_LINKS) && !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw IOException("Backup share storage is unavailable.")
        }
        Files.createDirectories(root)
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Backup share storage is unavailable.")
    }

    private fun ownedDirectories(): Sequence<Path> = sequence {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@sequence
        Files.newDirectoryStream(root).use { children ->
            for (dir in children) {
                if (isCanonicalUuid(dir.fileName.toString()) && Files.isDirectory(dir, NOFOLLOW_LINKS)) {
                    yield(dir)
                }
            }
        }
    }

    private fun hasArchive(dir: Path): Boolean = Files.newDirectoryStream(dir).use { children ->
        children.any { file ->
            isSafeArchiveName(file.fileName.toString()) && Files.isRegularFile(file, NOFOLLOW_LINKS)
        }
    }

    private fun writeExpiry(dir: Path, expiry: Long) {
        val pending = dir.resolve("$EXPIRY_FILE.pending")
        Files.write(pending, ByteBuffer.allocate(Long.SIZE_BYTES).putLong(expiry).array())
        Files.move(pending, dir.resolve(EXPIRY_FILE), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun readExpiry(dir: Path): Long? = try {
        val file = dir.resolve(EXPIRY_FILE)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) null else {
            val bytes = Files.readAllBytes(file)
            if (bytes.size == Long.SIZE_BYTES) ByteBuffer.wrap(bytes).long else null
        }
    } catch (_: IOException) {
        null
    }

    private fun deleteDirectory(dir: Path) {
        if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) return
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file) // Deleting a link does not follow it.
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun parseUri(uri: Uri): Pair<String, String>? {
        val parts = uri.pathSegments
        if (parts.size != 3 || parts[0] != "backups") return null
        val id = parts[1]
        val name = parts[2]
        if (!isCanonicalUuid(id) || !isSafeArchiveName(name)) return null
        return (id to name).takeIf { uri.toString() == buildUri(id, name).toString() }
    }

    private fun buildUri(id: String, name: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath("backups")
        .appendPath(id)
        .appendPath(name)
        .build()

    private fun isCanonicalUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun isSafeArchiveName(value: String): Boolean =
        value.length in 5..160 && value.matches(Regex("[A-Za-z0-9._-]+\\.zip"))

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider.backup-share"
        const val LEASE_DURATION_MS = 60L * 60L * 1000L
        private const val ROOT_DIR = "backup-share-leases"
        private const val EXPIRY_FILE = ".expires"
        private const val MAX_ACTIVE_LEASES = 4
        private const val MAX_ACTIVE_BYTES = 8L * 1024L * 1024L * 1024L
        private val lock = Any()
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

internal class BackupShareCapacityException : IOException()
