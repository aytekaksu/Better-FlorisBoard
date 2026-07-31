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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipFile

class BackupArchiveZipWriterTest :
    FunSpec({
        val testRoot = Files.createTempDirectory("backup-zip-writer-test")

        afterSpec {
            Files.walk(testRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }

        test("a descendant symlink fails before the destination is touched") {
            val source = Files.createDirectory(testRoot.resolve("source"))
            val outside = Files.createDirectory(testRoot.resolve("outside"))
            val secretMarker = "outside-secret-marker"
            val secret = Files.write(
                outside.resolve("secret.txt"),
                secretMarker.encodeToByteArray(),
            )
            Files.createSymbolicLink(source.resolve("linked-secret"), secret)
            val destinationBytes = "existing-destination".encodeToByteArray()
            val destination = Files.write(testRoot.resolve("descendant.zip"), destinationBytes)

            val failure = shouldThrow<IllegalStateException> {
                ZipUtils.zip(source.toFile(), destination.toFile())
            }

            failure.message shouldBe "Cannot archive symbolic links."
            failure.toString() shouldNotContain secretMarker
            failure.toString() shouldNotContain secret.toString()
            Files.readAllBytes(destination).contentEquals(destinationBytes) shouldBe true
        }

        test("a symlink source root fails before the destination is touched") {
            val actualSource = Files.createDirectory(testRoot.resolve("actual-source"))
            Files.write(
                actualSource.resolve("data.txt"),
                "private-source-marker".encodeToByteArray(),
            )
            val sourceLink = Files.createSymbolicLink(
                testRoot.resolve("source-link"),
                actualSource,
            )
            val destinationBytes = "existing-root-destination".encodeToByteArray()
            val destination = Files.write(testRoot.resolve("root.zip"), destinationBytes)

            val failure = shouldThrow<IllegalStateException> {
                ZipUtils.zip(sourceLink.toFile(), destination.toFile())
            }

            failure.message shouldBe "Cannot archive symbolic links."
            failure.toString() shouldNotContain sourceLink.toString()
            Files.readAllBytes(destination).contentEquals(destinationBytes) shouldBe true
        }

        test("an allowed missing directory creates an empty destination") {
            val source = testRoot.resolve("allowed-missing-source")
            val destination = testRoot.resolve("allowed-missing-destination")

            ZipUtils.copyDirectoryNoFollow(
                source.toFile(),
                destination.toFile(),
                allowMissing = true,
            )

            Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
            Files.list(destination).use { children ->
                children.iterator().hasNext() shouldBe false
            }
        }

        test("copying a file symlink preserves the destination") {
            val outsideMarker = "outside-file-marker"
            val outsideFile = Files.write(
                testRoot.resolve("outside-file.txt"),
                outsideMarker.encodeToByteArray(),
            )
            val source = Files.createSymbolicLink(
                testRoot.resolve("copy-file-source"),
                outsideFile,
            )
            val destinationBytes = "existing-copy-file".encodeToByteArray()
            val destination = Files.write(
                testRoot.resolve("copy-file-destination"),
                destinationBytes,
            )

            val failure = shouldThrow<IllegalStateException> {
                ZipUtils.copyFileNoFollow(source.toFile(), destination.toFile())
            }

            failure.message shouldBe "Cannot archive symbolic links."
            failure.toString() shouldNotContain outsideMarker
            failure.toString() shouldNotContain outsideFile.toString()
            Files.readAllBytes(destination).contentEquals(destinationBytes) shouldBe true
        }

        test("copying a tree with a directory symlink preserves the destination") {
            val source = Files.createDirectory(testRoot.resolve("copy-tree-source"))
            Files.write(source.resolve("ordinary.txt"), "ordinary".encodeToByteArray())
            val outside = Files.createDirectory(testRoot.resolve("copy-tree-outside"))
            val outsideMarker = "outside-directory-marker"
            val outsideFile = Files.write(
                outside.resolve("private.txt"),
                outsideMarker.encodeToByteArray(),
            )
            Files.createSymbolicLink(source.resolve("linked-directory"), outside)
            val destination = Files.createDirectory(testRoot.resolve("copy-tree-destination"))
            val destinationMarker = "existing-copy-tree"
            val guard = Files.write(
                destination.resolve("guard.txt"),
                destinationMarker.encodeToByteArray(),
            )

            val failure = shouldThrow<IllegalStateException> {
                ZipUtils.copyDirectoryNoFollow(source.toFile(), destination.toFile())
            }

            failure.message shouldBe "Cannot archive symbolic links."
            failure.toString() shouldNotContain outsideMarker
            failure.toString() shouldNotContain outsideFile.toString()
            Files.readAllBytes(guard).decodeToString() shouldBe destinationMarker
            Files.exists(destination.resolve("ordinary.txt"), LinkOption.NOFOLLOW_LINKS) shouldBe false
            Files.exists(
                destination.resolve("linked-directory/private.txt"),
                LinkOption.NOFOLLOW_LINKS,
            ) shouldBe false
        }

        test("source timestamps are normalized and unchanged sources produce identical bytes") {
            val source = Files.createDirectory(testRoot.resolve("deterministic-source"))
            Files.createDirectory(source.resolve("empty"))
            Files.write(source.resolve("data.txt"), "stable-data".encodeToByteArray())
            val first = testRoot.resolve("deterministic-first.zip")
            val second = testRoot.resolve("deterministic-second.zip")

            ZipUtils.zip(source.toFile(), first.toFile())
            ZipUtils.zip(source.toFile(), second.toFile())

            ZipFile(first.toFile()).use { archive ->
                archive.entries().asSequence().forEach { it.time shouldBe 0L }
            }
            Files.readAllBytes(first).contentEquals(Files.readAllBytes(second)) shouldBe true
        }

        test("successful publication replaces the destination without a partial sibling") {
            val source = Files.createDirectory(testRoot.resolve("replacement-source"))
            Files.write(source.resolve("data.txt"), "replacement-data".encodeToByteArray())
            val destination = Files.write(
                testRoot.resolve("replacement.zip"),
                "old-destination".encodeToByteArray(),
            )

            ZipUtils.zip(source.toFile(), destination.toFile())

            ZipFile(destination.toFile()).use { archive ->
                archive.entries().asSequence().map { it.name }.toList() shouldContainExactly listOf(
                    "data.txt",
                )
                archive.readText("data.txt") shouldBe "replacement-data"
            }
            partialSiblings(destination) shouldBe emptyList()
        }

        test("failed publication preserves the destination and removes its partial sibling") {
            val source = Files.createDirectory(testRoot.resolve("failed-publication-source"))
            Files.write(source.resolve("data.txt"), "replacement-data".encodeToByteArray())
            val destination = Files.createDirectory(testRoot.resolve("failed-publication.zip"))
            val destinationMarker = "existing-publication-target"
            val guard = Files.write(
                destination.resolve("guard.txt"),
                destinationMarker.encodeToByteArray(),
            )

            shouldThrow<IOException> {
                ZipUtils.zip(source.toFile(), destination.toFile())
            }

            Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) shouldBe true
            Files.readAllBytes(guard).decodeToString() shouldBe destinationMarker
            partialSiblings(destination) shouldBe emptyList()
        }

        test("bounded writing rejects oversized sources before touching the destination") {
            val source = Files.createDirectory(testRoot.resolve("bounded-source"))
            Files.write(source.resolve("data.txt"), "oversized".encodeToByteArray())
            val destinationBytes = "bounded-destination".encodeToByteArray()
            val destination = Files.write(testRoot.resolve("bounded.zip"), destinationBytes)

            listOf(
                writerLimits(maxEntries = 0),
                writerLimits(maxSourceBytes = 1),
                writerLimits(maxFileBytes = 1),
                writerLimits(maxPathBytes = 1),
            ).forEach { limits ->
                shouldThrow<IllegalStateException> {
                    ZipUtils.zip(source.toFile(), destination.toFile(), limits)
                }
                Files.readAllBytes(destination).contentEquals(destinationBytes) shouldBe true
                partialSiblings(destination) shouldBe emptyList()
            }
        }

        test("compressed output limits and cancellation preserve the destination") {
            val source = Files.createDirectory(testRoot.resolve("bounded-output-source"))
            Files.write(source.resolve("data.txt"), ByteArray(128 * 1024) { it.toByte() })
            val destinationBytes = "bounded-output-destination".encodeToByteArray()
            val destination = Files.write(testRoot.resolve("bounded-output.zip"), destinationBytes)

            shouldThrow<IllegalStateException> {
                ZipUtils.zip(
                    source.toFile(),
                    destination.toFile(),
                    writerLimits(maxOutputBytes = 1),
                )
            }
            Files.readAllBytes(destination).contentEquals(destinationBytes) shouldBe true
            partialSiblings(destination) shouldBe emptyList()

            shouldThrow<CancellationException> {
                ZipUtils.zip(
                    source.toFile(),
                    destination.toFile(),
                    writerLimits(
                        checkCancelled = {
                            if (partialSiblings(destination).isNotEmpty()) {
                                throw CancellationException("test cancellation")
                            }
                        },
                    ),
                )
            }
            Files.readAllBytes(destination).contentEquals(destinationBytes) shouldBe true
            partialSiblings(destination) shouldBe emptyList()
        }

        test("normal nested and empty directories keep stable entry names") {
            val source = Files.createDirectory(testRoot.resolve("normal-source"))
            Files.createDirectories(source.resolve("alpha"))
            Files.createDirectory(source.resolve("empty"))
            Files.createDirectories(source.resolve("nested/empty"))
            Files.write(source.resolve("root.txt"), "root".encodeToByteArray())
            Files.write(source.resolve("alpha/data.txt"), "nested".encodeToByteArray())
            val destination = testRoot.resolve("normal.zip")

            ZipUtils.zip(source.toFile(), destination.toFile())

            ZipFile(destination.toFile()).use { archive ->
                archive.entries().asSequence().map { it.name }.toList() shouldContainExactly listOf(
                    "alpha/",
                    "alpha/data.txt",
                    "empty/",
                    "nested/",
                    "nested/empty/",
                    "root.txt",
                )
                archive.readText("alpha/data.txt") shouldBe "nested"
                archive.readText("root.txt") shouldBe "root"
            }
        }

        test("new archive publication cannot replace an existing file") {
            val source = Files.createDirectory(testRoot.resolve("new-archive-source"))
            Files.write(source.resolve("data.txt"), "new archive".encodeToByteArray())
            val originalBytes = "keep existing".encodeToByteArray()
            val existing = Files.write(testRoot.resolve("existing-new-archive.zip"), originalBytes)

            shouldThrow<IOException> {
                ZipUtils.zipNew(
                    source.toFile(),
                    existing.toFile(),
                    ZipUtils.WriteLimits.Unbounded,
                )
            }

            Files.readAllBytes(existing).contentEquals(originalBytes) shouldBe true
            partialSiblings(existing) shouldBe emptyList()

            val fresh = testRoot.resolve("fresh-new-archive.zip")
            ZipUtils.zipNew(source.toFile(), fresh.toFile(), ZipUtils.WriteLimits.Unbounded)
            ZipFile(fresh.toFile()).use { archive ->
                archive.readText("data.txt") shouldBe "new archive"
            }
        }
    })

private fun ZipFile.readText(path: String): String = getInputStream(getEntry(path)).bufferedReader().use {
    it.readText()
}

private fun partialSiblings(destination: Path): List<String> {
    val prefix = ".${destination.fileName}."
    return Files.list(destination.parent).use { siblings ->
        siblings.iterator().asSequence()
            .map { it.fileName.toString() }
            .filter { it.startsWith(prefix) && it.endsWith(".partial") }
            .sorted()
            .toList()
    }
}

private fun writerLimits(
    maxEntries: Int = 100,
    maxSourceBytes: Long = 1L shl 20,
    maxFileBytes: Long = 1L shl 20,
    maxPathBytes: Int = 1_024,
    maxPathSegmentBytes: Int = 255,
    maxOutputBytes: Long = 1L shl 20,
    checkCancelled: () -> Unit = {},
): ZipUtils.WriteLimits = ZipUtils.WriteLimits(
    maxEntries = maxEntries,
    maxSourceBytes = maxSourceBytes,
    maxFileBytes = maxFileBytes,
    maxPathBytes = maxPathBytes,
    maxPathSegmentBytes = maxPathSegmentBytes,
    maxOutputBytes = maxOutputBytes,
    checkCancelled = checkCancelled,
)
