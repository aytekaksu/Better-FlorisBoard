/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.han

import dev.patrickgold.florisboard.ime.nlp.LanguagePackExtension
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.lib.ext.validateForImport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class BundledLanguagePackMetadataTest :
    FunSpec({
        test("bundled Chinese pack matches its public documentation") {
            val assetDir = sequenceOf("src/main/assets", "app/src/main/assets")
                .map { File(it, "ime/languagepack/org.florisboard.hanshapebasedbasicpack") }
                .first(File::isDirectory)
            val extension = ExtensionJsonConfig.decodeFromString(
                LanguagePackExtension.serializer(),
                assetDir.resolve("extension.json").readText(),
            )

            extension.validateForImport().isValid shouldBe true
            extension.items.map { it.id } shouldBe listOf(
                "zh_CN_zhengma",
                "zh_TW_boshiamy",
                "zh_TW_cangjielarge",
            )
            val expectedHomepage =
                "https://github.com/aytekaksu/Better-FlorisBoard/blob/main/" +
                "LANGUAGEPACKS-CHINESE.md#default-barebones-chinese-shape-based-pack"
            extension.meta.homepage shouldBe expectedHomepage

            val documentation = sequenceOf("LANGUAGEPACKS-CHINESE.md", "../LANGUAGEPACKS-CHINESE.md")
                .map(::File)
                .first(File::isFile)
            documentation.readText().contains("## Default barebones Chinese shape-based pack") shouldBe true
        }
    })
