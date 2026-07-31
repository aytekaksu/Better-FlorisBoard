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

package dev.patrickgold.florisboard.lib.io

import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/**
 * Fail-closed boundary for extension ZIPs.
 *
 * The public helpers intentionally collapse all failures to one content-free
 * exception. Archive names and filesystem paths must not escape through UI
 * error reporting.
 */
internal object BoundedExtensionArchive {
    private const val MEBIBYTE = 1_024 * 1_024

    internal class Limits(
        val maxArchiveBytes: Long,
        val maxEntries: Int,
        val maxExpandedBytes: Long,
        val maxEntryBytes: Long,
        val maxReadBytes: Long,
        val maxPathBytes: Int,
        val maxSegmentBytes: Int,
        val maxDepth: Int,
    ) {
        init {
            require(
                maxArchiveBytes >= 0L &&
                    maxEntries >= 0 &&
                    maxExpandedBytes >= 0L &&
                    maxEntryBytes >= 0L &&
                    maxReadBytes >= 0L &&
                    maxPathBytes >= 0 &&
                    maxSegmentBytes >= 0 &&
                    maxDepth >= 0,
            )
        }
    }

    internal val DefaultLimits = Limits(
        maxArchiveBytes = 64L * MEBIBYTE,
        maxEntries = 4_096,
        maxExpandedBytes = 128L * MEBIBYTE,
        maxEntryBytes = 64L * MEBIBYTE,
        maxReadBytes = 8L * MEBIBYTE,
        maxPathBytes = 512,
        maxSegmentBytes = 255,
        maxDepth = 16,
    )

    fun <T> protect(block: () -> T): T = failClosed(block)

    /** The caller owns [admission] and commits it only after the full import succeeds. */
    fun extract(
        source: Path,
        destination: Path,
        limits: Limits = DefaultLimits,
        admission: ExtensionImportBudget.Admission? = null,
    ) = failClosed {
        withArchive(source, limits) { zipFile, archive ->
            admission?.reserveArchive(
                expandedBytes = archive.expandedBytes,
                entries = archive.entries.size,
            )
            publishDirectory(destination) { staging ->
                extractValidated(zipFile, archive, staging, limits)
            }
        }
    }

    /**
     * Validates and reads one control file before publishing the extracted archive.
     *
     * [inspect] runs only after the complete ZIP structure has passed validation. If it rejects
     * the control file, no archive entry is extracted or published.
     */
    fun <T> extractAfterInspectingText(
        source: Path,
        destination: Path,
        relativePath: String,
        maxTextBytes: Long,
        limits: Limits = DefaultLimits,
        admission: ExtensionImportBudget.Admission? = null,
        inspect: (String) -> T,
    ): T = failClosed {
        rejectUnless(maxTextBytes in 0L..limits.maxReadBytes)
        val validatedPath = validatePath(relativePath, EntryKind.FILE, limits)
        withArchive(source, limits) { zipFile, archive ->
            admission?.reserveArchive(
                expandedBytes = archive.expandedBytes,
                entries = archive.entries.size,
            )
            val inspected = inspect(
                readValidatedText(
                    zipFile = zipFile,
                    archive = archive,
                    validatedPath = validatedPath,
                    maxReadBytes = maxTextBytes,
                ),
            )
            ensureNotInterrupted()
            publishDirectory(destination) { staging ->
                extractValidated(zipFile, archive, staging, limits)
            }
            inspected
        }
    }

    fun readText(source: Path, relativePath: String, limits: Limits = DefaultLimits): String = failClosed {
        val validatedPath = validatePath(relativePath, EntryKind.FILE, limits)
        withArchive(source, limits) { zipFile, archive ->
            readValidatedText(
                zipFile = zipFile,
                archive = archive,
                validatedPath = validatedPath,
                maxReadBytes = limits.maxReadBytes,
            )
        }
    }

    fun readTrustedText(relativePath: String, limits: Limits = DefaultLimits, open: (String) -> InputStream): String =
        failClosed {
            val validatedPath = validatePath(relativePath, EntryKind.FILE, limits)
            val bytes = ByteArrayOutputStream(READ_BUFFER_BYTES).use { output ->
                open(validatedPath).use { input ->
                    copyBounded(input, output, limits.maxReadBytes)
                }
                output.toByteArray()
            }
            ensureNotInterrupted()
            decodeStrictUtf8(bytes)
        }

    /**
     * APK assets are trusted input, but they still use all-or-nothing
     * publication so callers never observe a half-copied extension.
     */
    fun publishTrustedDirectory(destination: Path, populate: (Path) -> Unit) = failClosed {
        publishDirectory(destination, populate)
    }

    private fun <T> withArchive(source: Path, limits: Limits, block: (ZipFile, ValidatedArchive) -> T): T {
        val sourceAttributes = Files.readAttributes(
            source,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        rejectUnless(
            !sourceAttributes.isSymbolicLink &&
                sourceAttributes.isRegularFile &&
                sourceAttributes.size() in 1L..limits.maxArchiveBytes,
        )
        FileChannel.open(
            source,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            rejectUnless(channel.size() == sourceAttributes.size())
            rejectUnless(
                ExtensionZipContainerGate.accepts(
                    channel = channel,
                    archiveBytes = sourceAttributes.size(),
                    maxEntries = limits.maxEntries,
                    maxNameBytes = limits.maxPathBytes,
                ),
            )
            ZipFile.builder()
                .setSeekableByteChannel(channel)
                .setCharset(StandardCharsets.UTF_8)
                .setUseUnicodeExtraFields(false)
                .setIgnoreLocalFileHeader(true)
                .get()
                .use { zipFile ->
                    val archive = inspect(zipFile, sourceAttributes.size(), limits)
                    return block(zipFile, archive)
                }
        }
    }

    private fun inspect(zipFile: ZipFile, archiveBytes: Long, limits: Limits): ValidatedArchive {
        val entries = ArrayList<ValidatedEntry>()
        val byPath = HashMap<String, ValidatedEntry>()
        val requiredDirectories = HashSet<String>()
        var declaredCompressedBytes = 0L
        var declaredExpandedBytes = 0L
        val zipEntries = zipFile.entries
        while (zipEntries.hasMoreElements()) {
            ensureNotInterrupted()
            rejectUnless(entries.size < limits.maxEntries)
            val zipEntry = zipEntries.nextElement()
            val validated = validateEntry(zipFile, zipEntry, limits)
            rejectUnless(byPath.put(validated.path, validated) == null)

            val segments = validated.path.split('/')
            var parent = ""
            for (index in 0 until segments.lastIndex) {
                parent = if (parent.isEmpty()) segments[index] else "$parent/${segments[index]}"
                rejectUnless(byPath[parent]?.kind != EntryKind.FILE)
                requiredDirectories += parent
            }
            if (validated.kind == EntryKind.FILE) {
                rejectUnless(validated.path !in requiredDirectories)
            } else {
                requiredDirectories += validated.path
            }

            rejectUnless(validated.compressedSize <= archiveBytes - declaredCompressedBytes)
            rejectUnless(validated.size <= limits.maxExpandedBytes - declaredExpandedBytes)
            declaredCompressedBytes += validated.compressedSize
            declaredExpandedBytes += validated.size
            entries += validated
        }
        return ValidatedArchive(entries, byPath, declaredExpandedBytes)
    }

    private fun validateEntry(zipFile: ZipFile, zipEntry: ZipArchiveEntry, limits: Limits): ValidatedEntry {
        val kind = zipEntry.entryKind()
        rejectUnless(kind != EntryKind.UNSUPPORTED)
        val rawPath = zipEntry.name
        rejectUnless(rawPath.endsWith('/') == (kind == EntryKind.DIRECTORY))
        val path = validatePath(rawPath, kind, limits)
        val rawName = zipEntry.rawName
        rejectUnless(rawName != null && rawName.contentEquals(rawPath.toByteArray(StandardCharsets.UTF_8)))
        rejectUnless(
            zipEntry.diskNumberStart == 0L &&
                !zipEntry.generalPurposeBit.usesEncryption() &&
                !zipEntry.generalPurposeBit.usesStrongEncryption() &&
                zipEntry.method != ZipMethod.AES_ENCRYPTED.code &&
                zipEntry.method in SUPPORTED_METHODS &&
                zipFile.canReadEntryData(zipEntry),
        )

        val size = zipEntry.size
        val compressedSize = zipEntry.compressedSize
        val crc = zipEntry.crc
        rejectUnless(
            size in 0L..limits.maxEntryBytes &&
                compressedSize in 0L..limits.maxArchiveBytes &&
                crc in 0L..MAX_CRC32,
        )
        if (kind == EntryKind.DIRECTORY) {
            rejectUnless(size == 0L && crc == 0L)
        } else if (zipEntry.method == ZipMethod.STORED.code) {
            rejectUnless(compressedSize == size)
        }
        return ValidatedEntry(zipEntry, path, kind, size, compressedSize, crc)
    }

    private fun readValidatedText(
        zipFile: ZipFile,
        archive: ValidatedArchive,
        validatedPath: String,
        maxReadBytes: Long,
    ): String {
        val entry = archive.byPath[validatedPath] ?: reject()
        rejectUnless(entry.kind == EntryKind.FILE && entry.size <= maxReadBytes)
        val bytes = ByteArrayOutputStream(
            minOf(entry.size, READ_BUFFER_BYTES.toLong()).toInt(),
        ).use { output ->
            copyAndVerify(
                input = zipFile.getInputStream(entry.zipEntry),
                output = output,
                expectedBytes = entry.size,
                expectedCrc = entry.crc,
                maxBytes = maxReadBytes,
                expandedBudget = ExpandedBudget(maxReadBytes),
            )
            output.toByteArray()
        }
        ensureNotInterrupted()
        return decodeStrictUtf8(bytes)
    }

    private fun validatePath(rawPath: String, kind: EntryKind, limits: Limits): String {
        rejectUnless(
            rawPath.isNotEmpty() &&
                rawPath.length <= limits.maxPathBytes &&
                rawPath.toByteArray(StandardCharsets.UTF_8).size <= limits.maxPathBytes &&
                !rawPath.startsWith('/') &&
                !rawPath.contains('\\') &&
                !rawPath.any(Char::isISOControl) &&
                !DRIVE_PREFIX.containsMatchIn(rawPath),
        )
        val path = if (kind == EntryKind.DIRECTORY) rawPath.removeSuffix("/") else rawPath
        rejectUnless(path.isNotEmpty())
        val segments = path.split('/')
        rejectUnless(segments.size <= limits.maxDepth)
        for (segment in segments) {
            rejectUnless(
                segment.isNotEmpty() &&
                    segment != "." &&
                    segment != ".." &&
                    segment.length <= limits.maxSegmentBytes &&
                    segment.toByteArray(StandardCharsets.UTF_8).size <= limits.maxSegmentBytes,
            )
        }
        return path
    }

    private fun extractValidated(zipFile: ZipFile, archive: ValidatedArchive, staging: Path, limits: Limits) {
        val expandedBudget = ExpandedBudget(limits.maxExpandedBytes)
        for (entry in archive.entries) {
            ensureNotInterrupted()
            when (entry.kind) {
                EntryKind.DIRECTORY -> {
                    ensureDirectory(staging, entry.path)
                    copyAndVerify(
                        input = zipFile.getInputStream(entry.zipEntry),
                        output = DISCARDING_OUTPUT,
                        expectedBytes = 0L,
                        expectedCrc = entry.crc,
                        maxBytes = 0L,
                        expandedBudget = expandedBudget,
                    )
                }

                EntryKind.FILE -> {
                    val destination = resolveFile(staging, entry.path)
                    Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { output ->
                        copyAndVerify(
                            input = zipFile.getInputStream(entry.zipEntry),
                            output = output,
                            expectedBytes = entry.size,
                            expectedCrc = entry.crc,
                            maxBytes = limits.maxEntryBytes,
                            expandedBudget = expandedBudget,
                        )
                    }
                }

                EntryKind.UNSUPPORTED -> reject()
            }
        }
    }

    private fun copyAndVerify(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long,
        expectedCrc: Long,
        maxBytes: Long,
        expandedBudget: ExpandedBudget,
    ) {
        input.use {
            val crc = CRC32()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var actualBytes = 0L
            while (true) {
                ensureNotInterrupted()
                val readBytes = it.read(buffer)
                if (readBytes < 0) break
                rejectUnless(
                    readBytes > 0 &&
                        readBytes.toLong() <= expectedBytes - actualBytes &&
                        readBytes.toLong() <= maxBytes - actualBytes,
                )
                expandedBudget.add(readBytes)
                output.write(buffer, 0, readBytes)
                crc.update(buffer, 0, readBytes)
                actualBytes += readBytes
            }
            rejectUnless(actualBytes == expectedBytes && crc.value == expectedCrc)
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var copiedBytes = 0L
        while (true) {
            ensureNotInterrupted()
            val readBytes = input.read(buffer)
            if (readBytes < 0) break
            rejectUnless(readBytes > 0 && readBytes.toLong() <= maxBytes - copiedBytes)
            output.write(buffer, 0, readBytes)
            copiedBytes += readBytes
        }
    }

    private fun publishDirectory(destination: Path, populate: (Path) -> Unit) {
        ensureNotInterrupted()
        val target = destination.toAbsolutePath().normalize()
        val parent = target.parent ?: reject()
        val parentAttributes = Files.readAttributes(
            parent,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        rejectUnless(!parentAttributes.isSymbolicLink && parentAttributes.isDirectory)
        val destinationWasEmpty = inspectDestination(target)
        val staging = createPrivateStaging(parent)
        var published = false
        try {
            populate(staging)
            ensureNotInterrupted()
            if (destinationWasEmpty) {
                rejectUnless(inspectDestination(target))
            } else {
                rejectUnless(Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
            }
            try {
                if (destinationWasEmpty) {
                    Files.move(
                        staging,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } else {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                }
            } catch (_: AtomicMoveNotSupportedException) {
                reject()
            }
            published = true
        } finally {
            if (!published) {
                deleteTree(staging)
            }
        }
    }

    /**
     * Returns true for an existing empty destination and false when absent.
     * Existing files, links, or populated directories are never replaced.
     */
    private fun inspectDestination(destination: Path): Boolean {
        if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) return false
        val attributes = Files.readAttributes(
            destination,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        rejectUnless(!attributes.isSymbolicLink && attributes.isDirectory)
        Files.newDirectoryStream(destination).use { children ->
            rejectUnless(!children.iterator().hasNext())
        }
        return true
    }

    private fun createPrivateStaging(parent: Path): Path {
        val attributes = PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------"),
        )
        return try {
            Files.createTempDirectory(parent, STAGING_PREFIX, attributes)
        } catch (_: UnsupportedOperationException) {
            Files.createTempDirectory(parent, STAGING_PREFIX)
        }
    }

    private fun ensureDirectory(root: Path, relativePath: String): Path {
        var current = root
        for (segment in relativePath.split('/')) {
            current = current.resolve(segment)
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current)
            } else {
                val attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                rejectUnless(!attributes.isSymbolicLink && attributes.isDirectory)
            }
        }
        return current
    }

    private fun resolveFile(root: Path, relativePath: String): Path {
        val segments = relativePath.split('/')
        val parentPath = segments.dropLast(1).joinToString("/")
        val parent = if (parentPath.isEmpty()) root else ensureDirectory(root, parentPath)
        return parent.resolve(segments.last())
    }

    private fun deleteTree(root: Path) {
        repeat(CLEANUP_ATTEMPTS) {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return
            runCatching {
                Files.walkFileTree(
                    root,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            Files.deleteIfExists(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                            Files.deleteIfExists(dir)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }
        }
        rejectUnless(Files.notExists(root, LinkOption.NOFOLLOW_LINKS))
    }

    private inline fun <T> failClosed(block: () -> T): T = try {
        block()
    } catch (error: InterruptedException) {
        throw error
    } catch (_: ClosedByInterruptException) {
        throw InterruptedException()
    } catch (error: ExtensionImportLimitException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: BoundaryFailure) {
        throw BoundaryFailure()
    } catch (_: Exception) {
        throw BoundaryFailure()
    }

    private fun reject(): Nothing = throw BoundaryFailure()

    private fun rejectUnless(condition: Boolean) {
        if (!condition) reject()
    }

    private fun ensureNotInterrupted() {
        if (Thread.interrupted()) throw InterruptedException()
    }

    private class BoundaryFailure : IllegalStateException(FAILURE_MESSAGE)

    private class ExpandedBudget(private val maxBytes: Long) {
        private var copiedBytes = 0L

        fun add(readBytes: Int) {
            rejectUnless(readBytes > 0 && readBytes.toLong() <= maxBytes - copiedBytes)
            copiedBytes += readBytes
        }
    }

    private class ValidatedArchive(
        val entries: List<ValidatedEntry>,
        val byPath: Map<String, ValidatedEntry>,
        val expandedBytes: Long,
    )

    private class ValidatedEntry(
        val zipEntry: ZipArchiveEntry,
        val path: String,
        val kind: EntryKind,
        val size: Long,
        val compressedSize: Long,
        val crc: Long,
    ) {
        override fun toString(): String = "ValidatedEntry(kind=$kind, size=$size)"
    }

    private enum class EntryKind {
        DIRECTORY,
        FILE,
        UNSUPPORTED,
    }

    private fun ZipArchiveEntry.entryKind(): EntryKind {
        if (platform != ZipArchiveEntry.PLATFORM_UNIX) {
            return if (isDirectory) EntryKind.DIRECTORY else EntryKind.FILE
        }
        return when (unixMode and UnixStat.FILE_TYPE_FLAG) {
            UnixStat.FILE_FLAG -> EntryKind.FILE
            UnixStat.DIR_FLAG -> EntryKind.DIRECTORY
            0 -> if (isDirectory) EntryKind.DIRECTORY else EntryKind.FILE
            else -> EntryKind.UNSUPPORTED
        }
    }

    private const val READ_BUFFER_BYTES = 16 * 1_024
    private const val CLEANUP_ATTEMPTS = 2
    private const val MAX_CRC32 = 0xffff_ffffL
    private const val FAILURE_MESSAGE = "Extension data is invalid or exceeds safety limits."
    private const val STAGING_PREFIX = ".extension-stage-"
    private val DRIVE_PREFIX = Regex("""^[A-Za-z]:""")
    private val SUPPORTED_METHODS = setOf(ZipMethod.STORED.code, ZipMethod.DEFLATED.code)
    private val DISCARDING_OUTPUT = object : OutputStream() {
        override fun write(byte: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }
}

private fun decodeStrictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()
