/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.lib.io

import android.content.Context
import android.net.Uri
import org.florisboard.lib.android.copyRecursively
import org.florisboard.lib.android.write
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.FsFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtils {
    private const val SOURCE_INSPECTION_ERROR = "Cannot inspect archive source."
    private const val SYMBOLIC_LINK_ERROR = "Cannot archive symbolic links."
    private const val UNSUPPORTED_ENTRY_ERROR = "Cannot archive unsupported source entries."
    private const val DESTINATION_ERROR = "Cannot create archive destination."
    private const val ARCHIVE_LIMIT_ERROR = "Archive source exceeds backup limits."
    private val newArchivePublicationLock = Any()

    internal class TransferBudget(
        private val maxEntries: Int,
        private val maxBytes: Long,
        private val maxFileBytes: Long,
        private val checkCancelled: () -> Unit,
    ) {
        private var entryCount = 0
        private var copiedBytes = 0L

        init {
            require(maxEntries >= 0 && maxBytes >= 0L && maxFileBytes >= 0L)
        }

        internal fun inspect(attributes: BasicFileAttributes) {
            checkCancelled()
            check(entryCount < maxEntries) { ARCHIVE_LIMIT_ERROR }
            entryCount++
            if (attributes.isRegularFile) {
                check(attributes.size() in 0L..maxFileBytes) { ARCHIVE_LIMIT_ERROR }
            }
        }

        internal fun add(readBytes: Int, fileBytes: Long): Long {
            checkCancelled()
            check(readBytes > 0) { SOURCE_INSPECTION_ERROR }
            check(readBytes.toLong() <= maxFileBytes - fileBytes) { ARCHIVE_LIMIT_ERROR }
            check(readBytes.toLong() <= maxBytes - copiedBytes) { ARCHIVE_LIMIT_ERROR }
            copiedBytes += readBytes
            return fileBytes + readBytes
        }
    }

    internal class WriteLimits(
        val maxEntries: Int,
        val maxSourceBytes: Long,
        val maxFileBytes: Long,
        val maxPathBytes: Int,
        val maxPathSegmentBytes: Int,
        val maxOutputBytes: Long,
        private val maxFileBytesForPath: (String) -> Long = { maxFileBytes },
        private val checkCancelled: () -> Unit,
    ) {
        init {
            require(
                maxEntries >= 0 &&
                    maxSourceBytes >= 0L &&
                    maxFileBytes >= 0L &&
                    maxPathBytes >= 0 &&
                    maxPathSegmentBytes >= 0 &&
                    maxOutputBytes >= 0L,
            )
        }

        internal fun ensureActive() = checkCancelled()

        internal fun fileLimit(path: String): Long = maxFileBytesForPath(path).also {
            check(it in 0L..maxFileBytes) { ARCHIVE_LIMIT_ERROR }
        }

        override fun toString(): String =
            "WriteLimits(maxEntries=$maxEntries, maxSourceBytes=$maxSourceBytes, " +
                "maxFileBytes=$maxFileBytes, maxPathBytes=$maxPathBytes, " +
                "maxPathSegmentBytes=$maxPathSegmentBytes, maxOutputBytes=$maxOutputBytes)"

        companion object {
            val Unbounded = WriteLimits(
                maxEntries = Int.MAX_VALUE,
                maxSourceBytes = Long.MAX_VALUE,
                maxFileBytes = Long.MAX_VALUE,
                maxPathBytes = Int.MAX_VALUE,
                maxPathSegmentBytes = Int.MAX_VALUE,
                maxOutputBytes = Long.MAX_VALUE,
                maxFileBytesForPath = { Long.MAX_VALUE },
                checkCancelled = {},
            )
        }
    }

    fun readFileFromArchive(context: Context, zipRef: FlorisRef, relPath: String) = runCatching<String> {
        BoundedExtensionArchive.protect {
            when {
                zipRef.isAssets -> {
                    val assetRoot = zipRef.relativePath.removeSuffix("/")
                    BoundedExtensionArchive.readTrustedText(relPath) { validatedPath ->
                        val assetPath = if (assetRoot.isEmpty()) validatedPath else "$assetRoot/$validatedPath"
                        context.assets.open(assetPath)
                    }
                }
                zipRef.isCache || zipRef.isInternal -> {
                    BoundedExtensionArchive.readText(
                        source = FsFile(zipRef.absolutePath(context)).toPath(),
                        relativePath = relPath,
                    )
                }
                else -> error("Unsupported extension source.")
            }
        }
    }

    fun zip(context: Context, srcRef: FlorisRef, dstRef: FlorisRef) =
        zip(context, FsDir(srcRef.absolutePath(context)), dstRef)

    fun zip(context: Context, srcDir: FsDir, dstRef: FlorisRef) = runCatching {
        val limits = extensionWriteLimits()
        val entries = collectZipEntries(srcDir, limits)
        when {
            dstRef.isCache || dstRef.isInternal -> {
                val flexFile = FsFile(dstRef.absolutePath(context))
                writeZipFile(entries, flexFile.toPath(), limits)
            }
            else -> error("Unsupported destination!")
        }
    }

    fun zipNew(context: Context, srcDir: FsDir, dstRef: FlorisRef) = runCatching {
        check(dstRef.isCache || dstRef.isInternal) { "Unsupported destination!" }
        val limits = extensionWriteLimits()
        val entries = collectZipEntries(srcDir, limits)
        writeZipFile(
            entries = entries,
            destination = FsFile(dstRef.absolutePath(context)).toPath(),
            limits = limits,
            replaceExisting = false,
        )
    }

    fun zip(srcDir: FsDir, dstFile: FsFile) {
        zip(srcDir, dstFile, WriteLimits.Unbounded)
    }

    internal fun zip(srcDir: FsDir, dstFile: FsFile, limits: WriteLimits) {
        val entries = collectZipEntries(srcDir, limits)
        writeZipFile(entries, dstFile.toPath(), limits)
    }

    internal fun zipNew(srcDir: FsDir, dstFile: FsFile, limits: WriteLimits) {
        val entries = collectZipEntries(srcDir, limits)
        writeZipFile(entries, dstFile.toPath(), limits, replaceExisting = false)
    }

    fun zip(context: Context, srcDir: FsDir, uri: Uri) = runCatching {
        val limits = extensionWriteLimits()
        val entries = collectZipEntries(srcDir, limits)
        context.contentResolver.write(uri) { fileOut ->
            ZipOutputStream(fileOut).use { zipOut ->
                writeZipEntries(entries, zipOut, limits)
            }
        }
    }

    internal fun copyDirectoryNoFollow(
        srcDir: FsDir,
        dstDir: FsDir,
        allowMissing: Boolean = false,
        budget: TransferBudget? = null,
    ) {
        val source = srcDir.toPath()
        if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) {
            check(allowMissing) { SOURCE_INSPECTION_ERROR }
            createDestinationDirectory(dstDir.toPath())
            return
        }
        val entries = collectZipEntries(
            srcDir = srcDir,
            limits = WriteLimits.Unbounded,
            transferBudget = budget,
        )
        val destinationRoot = dstDir.toPath()
        createDestinationDirectory(destinationRoot)
        entries.forEach { entry ->
            val destination = destinationRoot.resolve(entry.archivePath).normalize()
            check(destination.startsWith(destinationRoot)) { DESTINATION_ERROR }
            when (entry.kind) {
                ZipSourceKind.DIRECTORY -> createDestinationDirectory(destination)
                ZipSourceKind.FILE -> copySourceFile(entry.path, destination, budget)
            }
        }
    }

    internal fun copyFileNoFollow(
        srcFile: FsFile,
        dstFile: FsFile,
        budget: TransferBudget? = null,
    ) {
        val attributes = readSourceAttributes(srcFile.toPath())
        check(!attributes.isSymbolicLink) { SYMBOLIC_LINK_ERROR }
        check(attributes.isRegularFile) { UNSUPPORTED_ENTRY_ERROR }
        budget?.inspect(attributes)
        dstFile.parentFile?.toPath()?.let(::createDestinationDirectory)
        copySourceFile(srcFile.toPath(), dstFile.toPath(), budget)
    }

    internal fun extensionTransferBudget(checkCancelled: () -> Unit): TransferBudget {
        val limits = BoundedExtensionArchive.DefaultLimits
        return TransferBudget(
            maxEntries = limits.maxEntries,
            maxBytes = limits.maxExpandedBytes,
            maxFileBytes = limits.maxEntryBytes,
            checkCancelled = checkCancelled,
        )
    }

    private fun collectZipEntries(
        srcDir: FsDir,
        limits: WriteLimits,
        transferBudget: TransferBudget? = null,
    ): List<ZipSourceEntry> {
        val root = srcDir.toPath()
        val rootAttributes = readSourceAttributes(root)
        check(!rootAttributes.isSymbolicLink) { SYMBOLIC_LINK_ERROR }
        check(rootAttributes.isDirectory) { "Cannot zip standalone file." }

        val collector = ZipSourceCollector(root, limits, transferBudget)
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        check(!attrs.isSymbolicLink) { SYMBOLIC_LINK_ERROR }
                        if (dir != root) {
                            collector.add(dir, ZipSourceKind.DIRECTORY, attrs)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        check(!attrs.isSymbolicLink) { SYMBOLIC_LINK_ERROR }
                        check(attrs.isRegularFile) { UNSUPPORTED_ENTRY_ERROR }
                        collector.add(file, ZipSourceKind.FILE, attrs)
                        openSourceFile(file).use { }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        throw IOException(SOURCE_INSPECTION_ERROR)
                    }
                },
            )
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: IOException) {
            error(SOURCE_INSPECTION_ERROR)
        } catch (_: SecurityException) {
            error(SOURCE_INSPECTION_ERROR)
        }
        return collector.result()
    }

    private fun archivePath(root: Path, path: Path): String =
        root.relativize(path).joinToString("/") { it.toString() }

    private fun writeZipEntries(
        entries: List<ZipSourceEntry>,
        zipOut: ZipOutputStream,
        limits: WriteLimits,
    ) {
        for (entry in entries) {
            limits.ensureActive()
            val attributes = readSourceAttributes(entry.path)
            check(!attributes.isSymbolicLink) { SYMBOLIC_LINK_ERROR }
            when (entry.kind) {
                ZipSourceKind.DIRECTORY -> {
                    check(attributes.isDirectory) { UNSUPPORTED_ENTRY_ERROR }
                    zipOut.putNextEntry(newZipEntry("${entry.archivePath}/"))
                    zipOut.closeEntry()
                }
                ZipSourceKind.FILE -> {
                    check(attributes.isRegularFile) { UNSUPPORTED_ENTRY_ERROR }
                    check(attributes.size() == entry.sourceBytes) { SOURCE_INSPECTION_ERROR }
                    zipOut.putNextEntry(newZipEntry(entry.archivePath))
                    openSourceFile(entry.path).use { input ->
                        copyExactSource(input, zipOut, entry.sourceBytes, limits)
                    }
                    zipOut.closeEntry()
                }
            }
        }
    }

    private fun newZipEntry(path: String): ZipEntry = ZipEntry(path).apply {
        // Backups should not expose source times or change when their contents do not.
        time = 0L
    }

    private fun writeZipFile(
        entries: List<ZipSourceEntry>,
        destination: Path,
        limits: WriteLimits,
        replaceExisting: Boolean = true,
    ) {
        val parent = destination.parent ?: error(DESTINATION_ERROR)
        createDestinationDirectory(parent)
        if (!replaceExisting) {
            requireNewDestination(destination)
        }
        val partial = parent.resolve(".${destination.fileName}.${UUID.randomUUID()}.partial")
        var ownsPartial = false
        try {
            FileChannel.open(
                partial,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                ownsPartial = true
                val output = BoundedOutputStream(
                    delegate = Channels.newOutputStream(channel),
                    maxBytes = limits.maxOutputBytes,
                    checkCancelled = limits::ensureActive,
                )
                ZipOutputStream(output).use { zipOut ->
                    writeZipEntries(entries, zipOut, limits)
                    zipOut.finish()
                    zipOut.flush()
                    channel.force(true)
                }
            }
            if (replaceExisting) {
                Files.move(
                    partial,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                synchronized(newArchivePublicationLock) {
                    requireNewDestination(destination)
                    Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE)
                }
            }
            ownsPartial = false
        } finally {
            if (ownsPartial) {
                Files.deleteIfExists(partial)
            }
        }
    }

    private fun requireNewDestination(destination: Path) {
        if (!Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException(DESTINATION_ERROR)
        }
    }

    private fun openSourceFile(path: Path): InputStream {
        return try {
            Files.newInputStream(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            error(SOURCE_INSPECTION_ERROR)
        } catch (_: SecurityException) {
            error(SOURCE_INSPECTION_ERROR)
        }
    }

    private fun copySourceFile(
        source: Path,
        destination: Path,
        budget: TransferBudget?,
    ) {
        val attributes = readSourceAttributes(source)
        check(!attributes.isSymbolicLink) { SYMBOLIC_LINK_ERROR }
        check(attributes.isRegularFile) { UNSUPPORTED_ENTRY_ERROR }
        try {
            openSourceFile(source).use { input ->
                Files.newOutputStream(
                    destination,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var fileBytes = 0L
                    while (true) {
                        val readBytes = input.read(buffer)
                        if (readBytes < 0) break
                        fileBytes = if (budget == null) {
                            check(readBytes > 0) { SOURCE_INSPECTION_ERROR }
                            fileBytes + readBytes
                        } else {
                            budget.add(readBytes, fileBytes)
                        }
                        output.write(buffer, 0, readBytes)
                    }
                }
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: IOException) {
            error(DESTINATION_ERROR)
        } catch (_: SecurityException) {
            error(DESTINATION_ERROR)
        }
    }

    private fun createDestinationDirectory(path: Path) {
        try {
            Files.createDirectories(path)
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            check(!attributes.isSymbolicLink && attributes.isDirectory) { DESTINATION_ERROR }
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: IOException) {
            error(DESTINATION_ERROR)
        } catch (_: SecurityException) {
            error(DESTINATION_ERROR)
        }
    }

    private fun readSourceAttributes(path: Path): BasicFileAttributes {
        return try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: IOException) {
            error(SOURCE_INSPECTION_ERROR)
        } catch (_: SecurityException) {
            error(SOURCE_INSPECTION_ERROR)
        }
    }

    private fun copyExactSource(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long,
        limits: WriteLimits,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copiedBytes = 0L
        while (true) {
            limits.ensureActive()
            val readBytes = input.read(buffer)
            if (readBytes < 0) break
            check(readBytes > 0) { SOURCE_INSPECTION_ERROR }
            check(readBytes.toLong() <= expectedBytes - copiedBytes) { SOURCE_INSPECTION_ERROR }
            output.write(buffer, 0, readBytes)
            copiedBytes += readBytes
        }
        check(copiedBytes == expectedBytes) { SOURCE_INSPECTION_ERROR }
    }

    private class ZipSourceCollector(
        private val root: Path,
        private val limits: WriteLimits,
        private val transferBudget: TransferBudget?,
    ) {
        private val entries = ArrayList<ZipSourceEntry>()
        private var sourceBytes = 0L

        fun add(path: Path, kind: ZipSourceKind, attributes: BasicFileAttributes) {
            limits.ensureActive()
            transferBudget?.inspect(attributes)
            check(entries.size < limits.maxEntries) { ARCHIVE_LIMIT_ERROR }
            val archivePath = archivePath(root, path)
            check(isSafeArchivePath(archivePath, kind, limits)) { ARCHIVE_LIMIT_ERROR }
            val entryBytes = if (kind == ZipSourceKind.FILE) attributes.size() else 0L
            check(entryBytes in 0L..limits.fileLimit(archivePath)) { ARCHIVE_LIMIT_ERROR }
            check(entryBytes <= limits.maxSourceBytes - sourceBytes) { ARCHIVE_LIMIT_ERROR }
            sourceBytes += entryBytes
            entries += ZipSourceEntry(
                path = path,
                archivePath = archivePath,
                kind = kind,
                sourceBytes = entryBytes,
            )
        }

        fun result(): List<ZipSourceEntry> = entries.sortedBy(ZipSourceEntry::archivePath)
    }

    private fun isSafeArchivePath(
        path: String,
        kind: ZipSourceKind,
        limits: WriteLimits,
    ): Boolean {
        val rawPath = if (kind == ZipSourceKind.DIRECTORY) "$path/" else path
        if (rawPath.isEmpty() ||
            rawPath.length > limits.maxPathBytes ||
            rawPath.startsWith('/') ||
            rawPath.contains('\\') ||
            rawPath.any(Char::isISOControl) ||
            rawPath.encodeToByteArray().size > limits.maxPathBytes ||
            DRIVE_PREFIX.containsMatchIn(rawPath)
        ) {
            return false
        }
        return path.split('/').all { segment ->
            segment.isNotEmpty() &&
                segment != "." &&
                segment != ".." &&
                segment.length <= limits.maxPathSegmentBytes &&
                segment.encodeToByteArray().size <= limits.maxPathSegmentBytes
        }
    }

    private class BoundedOutputStream(
        private val delegate: OutputStream,
        private val maxBytes: Long,
        private val checkCancelled: () -> Unit,
    ) : OutputStream() {
        private var writtenBytes = 0L

        override fun write(byte: Int) {
            reserve(1)
            delegate.write(byte)
            writtenBytes++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            delegate.write(bytes, offset, length)
            writtenBytes += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        private fun reserve(byteCount: Int) {
            checkCancelled()
            check(byteCount >= 0 && byteCount.toLong() <= maxBytes - writtenBytes) {
                ARCHIVE_LIMIT_ERROR
            }
        }
    }

    private enum class ZipSourceKind {
        DIRECTORY,
        FILE,
    }

    private data class ZipSourceEntry(
        val path: Path,
        val archivePath: String,
        val kind: ZipSourceKind,
        val sourceBytes: Long,
    )

    private const val COPY_BUFFER_BYTES = 64 * 1024
    private val DRIVE_PREFIX = Regex("""^[A-Za-z]:""")

    private fun extensionWriteLimits(): WriteLimits {
        val limits = BoundedExtensionArchive.DefaultLimits
        return WriteLimits(
            maxEntries = limits.maxEntries,
            maxSourceBytes = limits.maxExpandedBytes,
            maxFileBytes = limits.maxEntryBytes,
            maxPathBytes = limits.maxPathBytes,
            maxPathSegmentBytes = limits.maxSegmentBytes,
            maxOutputBytes = limits.maxArchiveBytes,
            checkCancelled = {
                if (Thread.interrupted()) throw InterruptedException()
            },
        )
    }

    fun unzip(context: Context, srcRef: FlorisRef, dstRef: FlorisRef) =
        unzip(context, srcRef, FsDir(dstRef.absolutePath(context)))

    fun unzip(context: Context, srcRef: FlorisRef, dstDir: FsFile) = runCatching {
        BoundedExtensionArchive.protect {
            when {
                srcRef.isAssets -> {
                    BoundedExtensionArchive.publishTrustedDirectory(dstDir.toPath()) { staging ->
                        context.assets.copyRecursively(
                            srcRef.relativePath.removeSuffix("/"),
                            staging.toFile(),
                        )
                    }
                }
                srcRef.isCache || srcRef.isInternal -> {
                    val flexHandle = FsFile(srcRef.absolutePath(context))
                    unzip(srcFile = flexHandle, dstDir = dstDir)
                }
                else -> error("Unsupported extension source.")
            }
        }
    }

    /** Materializes an extension archive without exposing partial output. */
    fun unzip(srcFile: FsFile, dstDir: FsDir) {
        BoundedExtensionArchive.protect {
            BoundedExtensionArchive.extract(srcFile.toPath(), dstDir.toPath())
        }
    }
}
