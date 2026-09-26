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

package dev.patrickgold.florisboard.app.ext

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class EditorAssetFilesTest : FunSpec({
    val root = Files.createTempDirectory("editor-asset-files")

    afterSpec { root.toFile().deleteRecursively() }

    fun workspace(): Path = Files.createTempDirectory(root, "workspace-").also {
        Files.createDirectories(it.resolve("ext/fonts"))
        Files.createDirectories(it.resolve("ext/images"))
    }

    fun write(path: Path, text: String) = Files.write(path, text.encodeToByteArray())
    fun read(path: Path) = Files.readAllBytes(path).decodeToString()

    test("lists only regular assets in each folder with picker-relative paths") {
        val directory = workspace()
        write(directory.resolve("ext/fonts/face.ttf"), "font")
        write(directory.resolve("ext/images/icon.png"), "image")
        Files.createDirectories(directory.resolve("ext/fonts/nested"))
        Files.createSymbolicLink(directory.resolve("ext/fonts/linked.ttf"), directory.resolve("ext/images/icon.png"))

        val listed = EditorAssetStore(directory).list()

        listed.fonts.map { it.name } shouldBe listOf("face.ttf")
        listed.images.map { it.name } shouldBe listOf("icon.png")
        listed.fonts.single().relativePath shouldBe "/fonts/face.ttf"
        listed.images.single().relativePath shouldBe "/images/icon.png"
    }

    test("rename refuses traversal, existing names, and symbolic-link destinations") {
        val directory = workspace()
        val original = directory.resolve("ext/fonts/face.ttf")
        val collision = directory.resolve("ext/fonts/other.ttf")
        val outside = directory.resolve("outside.ttf")
        write(original, "font")
        write(collision, "keep")
        write(outside, "outside")
        Files.createSymbolicLink(directory.resolve("ext/fonts/linked.ttf"), outside)
        val store = EditorAssetStore(directory)
        val asset = store.list().fonts.single { it.name == "face.ttf" }

        store.rename(asset, "../outside.ttf") shouldBe EditorAssetMutationResult.INVALID_NAME
        store.rename(asset, "nested/name.ttf") shouldBe EditorAssetMutationResult.INVALID_NAME
        store.rename(asset, "other.ttf") shouldBe EditorAssetMutationResult.ALREADY_EXISTS
        store.rename(asset, "linked.ttf") shouldBe EditorAssetMutationResult.ALREADY_EXISTS
        store.rename(asset, "face.ttf") shouldBe EditorAssetMutationResult.ALREADY_EXISTS
        read(original) shouldBe "font"
        read(collision) shouldBe "keep"
        read(outside) shouldBe "outside"

        store.rename(asset, "renamed.ttf") shouldBe EditorAssetMutationResult.SUCCESS
        Files.exists(original) shouldBe false
        read(directory.resolve("ext/fonts/renamed.ttf")) shouldBe "font"
    }

    test("delete removes a listed file but never follows a link or accepts a stale entry") {
        val directory = workspace()
        val original = directory.resolve("ext/images/icon.png")
        val outside = directory.resolve("outside.png")
        write(original, "image")
        write(outside, "outside")
        Files.createSymbolicLink(directory.resolve("ext/images/linked.png"), outside)
        val store = EditorAssetStore(directory)
        val asset = store.list().images.single()

        store.delete(asset.copy(name = "linked.png")) shouldBe
            EditorAssetMutationResult.FAILURE
        store.delete(asset.copy(name = "../outside.png")) shouldBe
            EditorAssetMutationResult.FAILURE
        store.delete(asset) shouldBe
            EditorAssetMutationResult.SUCCESS
        store.delete(asset) shouldBe
            EditorAssetMutationResult.FAILURE
        read(outside) shouldBe "outside"
    }

    test("a dialog entry cannot mutate a replacement at the same name") {
        val directory = workspace()
        val original = directory.resolve("ext/fonts/face.ttf")
        write(original, "old")
        val store = EditorAssetStore(directory)
        val displayed = store.list().fonts.single()
        Files.move(original, directory.resolve("ext/fonts/old-face.ttf"))
        write(original, "replacement")

        store.rename(displayed, "renamed.ttf") shouldBe EditorAssetMutationResult.FAILURE
        store.delete(displayed) shouldBe EditorAssetMutationResult.FAILURE
        read(original) shouldBe "replacement"
        Files.exists(directory.resolve("ext/fonts/renamed.ttf")) shouldBe false
    }

    test("closed or linked workspaces return no files and reject mutations") {
        val directory = workspace()
        write(directory.resolve("ext/fonts/face.ttf"), "font")
        val store = EditorAssetStore(directory)
        val file = store.list().fonts.single()
        directory.toFile().deleteRecursively()

        store.list() shouldBe EditorAssetFiles()
        store.rename(file, "new.ttf") shouldBe EditorAssetMutationResult.FAILURE
        store.delete(file) shouldBe EditorAssetMutationResult.FAILURE
        Files.exists(directory, LinkOption.NOFOLLOW_LINKS) shouldBe false

        val linkedWorkspace = root.resolve("linked-workspace")
        val outside = workspace()
        write(outside.resolve("ext/fonts/face.ttf"), "outside")
        Files.createSymbolicLink(linkedWorkspace, outside)
        val linkedStore = EditorAssetStore(linkedWorkspace)
        linkedStore.list() shouldBe EditorAssetFiles()
        linkedStore.delete(file) shouldBe EditorAssetMutationResult.FAILURE
        linkedStore.rename(file, "new.ttf") shouldBe EditorAssetMutationResult.FAILURE
        read(outside.resolve("ext/fonts/face.ttf")) shouldBe "outside"
    }

    test("linked asset directory stays outside the editor") {
        val directory = workspace()
        val outside = Files.createTempDirectory(root, "external-")
        write(outside.resolve("secret.ttf"), "outside")
        Files.delete(directory.resolve("ext/fonts"))
        Files.createSymbolicLink(directory.resolve("ext/fonts"), outside)
        val store = EditorAssetStore(directory)
        write(directory.resolve("ext/images/marker.png"), "marker")
        val file = store.list().images.single().copy(kind = EditorAssetKind.FONT, name = "secret.ttf")

        store.list().fonts shouldBe emptyList()
        store.delete(file) shouldBe
            EditorAssetMutationResult.FAILURE
        store.rename(file, "new.ttf") shouldBe
            EditorAssetMutationResult.FAILURE
        read(outside.resolve("secret.ttf")) shouldBe "outside"
    }
})
