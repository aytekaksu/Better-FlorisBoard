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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

class BackupArchiveSnapshotTest :
    FunSpec({
        val root = Files.createTempDirectory("backup-snapshot-test")

        afterEach {
            root.toFile().deleteRecursively()
            Files.createDirectories(root)
        }

        afterSpec {
            root.toFile().deleteRecursively()
        }

        test("publishes an exact-limit snapshot only after EOF") {
            val payload = byteArrayOf(1, 2, 3, 4)
            val partial = root.resolve("backup.zip.partial")
            val destination = root.resolve("backup.zip")

            val result = BackupArchiveSnapshot.copyToSnapshot(
                input = ByteArrayInputStream(payload),
                partial = partial,
                destination = destination,
                maxBytes = payload.size.toLong(),
            )

            result as ArchiveSnapshotResult.Valid
            result.snapshot.path shouldBe destination
            result.snapshot.size shouldBe payload.size.toLong()
            Files.readAllBytes(destination).toList() shouldBe payload.toList()
            Files.exists(partial) shouldBe false
        }

        test("rejects one byte beyond the limit without publishing") {
            val partial = root.resolve("backup.zip.partial")
            val destination = root.resolve("backup.zip")

            BackupArchiveSnapshot.copyToSnapshot(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
                partial = partial,
                destination = destination,
                maxBytes = 4,
            ) shouldBe ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.ARCHIVE_TOO_LARGE)

            Files.exists(partial) shouldBe false
            Files.exists(destination) shouldBe false
        }

        test("source failure and zero-progress streams leave no output") {
            listOf(
                object : InputStream() {
                    override fun read(): Int = error("source failed")
                },
                object : InputStream() {
                    override fun read(): Int = 0

                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
                },
            ).forEachIndexed { index, input ->
                val partial = root.resolve("backup-$index.zip.partial")
                val destination = root.resolve("backup-$index.zip")

                BackupArchiveSnapshot.copyToSnapshot(
                    input = input,
                    partial = partial,
                    destination = destination,
                    maxBytes = 16,
                ) shouldBe ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.IO_FAILURE)

                Files.exists(partial) shouldBe false
                Files.exists(destination) shouldBe false
            }
        }

        test("an existing destination is preserved and partial output is removed") {
            val partial = root.resolve("backup.zip.partial")
            val destination = root.resolve("backup.zip")
            val original = byteArrayOf(9, 8, 7)
            Files.write(destination, original)

            BackupArchiveSnapshot.copyToSnapshot(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                partial = partial,
                destination = destination,
                maxBytes = 3,
            ) shouldBe ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.IO_FAILURE)

            Files.readAllBytes(destination).toList() shouldBe original.toList()
            Files.exists(partial) shouldBe false
        }

        test("an existing partial file is never treated as owned cleanup") {
            val partial = root.resolve("backup.zip.partial")
            val destination = root.resolve("backup.zip")
            val original = byteArrayOf(9, 8, 7)
            Files.write(partial, original)

            BackupArchiveSnapshot.copyToSnapshot(
                input = ByteArrayInputStream(byteArrayOf(1)),
                partial = partial,
                destination = destination,
                maxBytes = 1,
            ) shouldBe ArchiveSnapshotResult.Invalid(ArchiveSnapshotFailure.IO_FAILURE)

            Files.readAllBytes(partial).toList() shouldBe original.toList()
            Files.exists(destination) shouldBe false
        }

        test("snapshot diagnostics do not expose its path") {
            val marker = "private-marker"
            ArchiveSnapshot(root.resolve(marker), 3).toString() shouldNotContain marker
        }
    })
