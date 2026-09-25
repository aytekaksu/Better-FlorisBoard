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
