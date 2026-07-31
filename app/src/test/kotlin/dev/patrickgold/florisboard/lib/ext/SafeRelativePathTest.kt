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

package dev.patrickgold.florisboard.lib.ext

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

class SafeRelativePathTest :
    FunSpec({
        test("accepts a bounded portable relative path") {
            val parsed = SafeRelativePath.parse("stylesheets/floris_day.json")

            parsed.getOrThrow().value shouldBe "stylesheets/floris_day.json"
            parsed.getOrThrow().toString() shouldBe "<relative-path>"
        }

        test("rejects absolute ambiguous and traversing paths") {
            val unsafePaths = listOf(
                "",
                "/stylesheets/theme.json",
                "C:/stylesheets/theme.json",
                "C:stylesheets/theme.json",
                "\\\\server\\theme.json",
                "stylesheets\\theme.json",
                "stylesheets//theme.json",
                "stylesheets/./theme.json",
                "stylesheets/../theme.json",
                "stylesheets/theme.json/",
                "stylesheets/ /theme.json",
                "stylesheets/\u0000theme.json",
            )

            for (path in unsafePaths) {
                val failure = SafeRelativePath.parse(path)
                failure.isFailure shouldBe true
                failure.exceptionOrNull().shouldBeInstanceOf<UnsafeRelativePathException>()
                failure.exceptionOrNull()?.message shouldBe "Unsafe relative path"
            }
        }

        test("rejects overlong segments and overly deep paths") {
            SafeRelativePath.parse("a".repeat(SafeRelativePath.MAX_SEGMENT_LENGTH + 1)).isFailure shouldBe true
            SafeRelativePath.parse("é".repeat(SafeRelativePath.MAX_SEGMENT_LENGTH)).isFailure shouldBe true
            SafeRelativePath.parse(
                List(5) { "é".repeat(SafeRelativePath.MAX_SEGMENT_LENGTH / 2) }.joinToString("/"),
            ).isFailure shouldBe true
            SafeRelativePath.parse(
                List(SafeRelativePath.MAX_DEPTH + 1) { "a" }.joinToString("/"),
            ).isFailure shouldBe true
            SafeRelativePath.parse(
                List(SafeRelativePath.MAX_DEPTH) {
                    "a".repeat(SafeRelativePath.MAX_SEGMENT_LENGTH)
                }.joinToString("/"),
            ).isFailure shouldBe true
        }

        test("resolves existing and missing children below the canonical root") {
            val root = Files.createTempDirectory("safe-relative-path")
            try {
                Files.createDirectories(root.resolve("stylesheets"))
                val existing = Files.createFile(root.resolve("stylesheets/existing.json"))

                SafeRelativePath.parse("stylesheets/existing.json")
                    .getOrThrow()
                    .resolveWithin(root)
                    .getOrThrow() shouldBe existing.toRealPath()
                SafeRelativePath.parse("stylesheets/new/theme.json")
                    .getOrThrow()
                    .resolveWithin(root)
                    .getOrThrow() shouldBe root.toRealPath().resolve("stylesheets/new/theme.json")
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        test("rejects a missing root or non-directory ancestor") {
            val container = Files.createTempDirectory("safe-relative-path-ancestor")
            try {
                val ordinaryFile = Files.createFile(container.resolve("ordinary"))
                val path = SafeRelativePath.parse("ordinary/child.json").getOrThrow()

                path.resolveWithin(container.resolve("missing")).isFailure shouldBe true
                path.resolveWithin(ordinaryFile).isFailure shouldBe true
                path.resolveWithin(container).isFailure shouldBe true
            } finally {
                container.toFile().deleteRecursively()
            }
        }

        test("rejects a symlink root or existing symlink child") {
            val container = Files.createTempDirectory("safe-relative-path-links")
            try {
                val root = Files.createDirectory(container.resolve("root"))
                val outside = Files.createDirectory(container.resolve("outside"))
                val rootLink = Files.createSymbolicLink(container.resolve("root-link"), root)
                Files.createSymbolicLink(root.resolve("escape"), outside)
                val path = SafeRelativePath.parse("escape/file.json").getOrThrow()

                path.resolveWithin(rootLink).isFailure shouldBe true
                path.resolveWithin(root).isFailure shouldBe true
            } finally {
                container.toFile().deleteRecursively()
            }
        }
    })
