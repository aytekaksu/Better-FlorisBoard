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

package dev.patrickgold.florisboard.ime.theme

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class FlorisAssetResolverTest :
    FunSpec({
        val rootDir = Files.createTempDirectory("floris-asset-resolver").toFile()
        val themeDir = rootDir.resolve("theme").apply { mkdirs() }
        val resolver = FlorisAssetResolver(themeDir)

        afterSpec {
            rootDir.deleteRecursively()
        }

        test("resolves an existing file inside the theme directory") {
            val asset = themeDir.resolve("images/icon.png").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("asset")
            }

            resolver.resolveAbsolutePath("flex:/images/icon.png").getOrThrow() shouldBe asset.canonicalPath
        }

        test("rejects traversal into a sibling whose name shares the theme prefix") {
            rootDir.resolve("theme-private/secret.txt").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("secret")
            }

            resolver.resolveAbsolutePath("flex:/../theme-private/secret.txt").isFailure shouldBe true
        }

        test("rejects a missing file") {
            resolver.resolveAbsolutePath("flex:/missing.png").isFailure shouldBe true
        }

        test("rejects malformed, unsupported, and non-local URIs") {
            listOf(
                "flex:[malformed",
                "flex:opaque",
                "flex://host/images/icon.png",
                "file:/images/icon.png",
            ).forEach { uri ->
                withClue(uri) {
                    resolver.resolveAbsolutePath(uri).isFailure shouldBe true
                }
            }
        }
    })
