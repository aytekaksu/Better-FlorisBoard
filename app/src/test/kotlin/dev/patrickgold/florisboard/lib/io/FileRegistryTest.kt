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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class FileRegistryTest : FunSpec({
    test("provider MIME normalization still applies only to flex files and alternatives") {
        listOf(
            Triple("package.flex", "application/zip", FileRegistry.FLEX_EXTENSION_MEDIA_TYPE),
            Triple("package.flex", "application/octet-stream", FileRegistry.FLEX_EXTENSION_MEDIA_TYPE),
            Triple("package.flex", "image/png", "image/png"),
            Triple("package.flex", null, null),
            Triple("package.FLEX", "application/zip", "application/zip"),
            Triple("package.zip", "application/zip", "application/zip"),
        ).forEach { (fileName, given, expected) ->
            FileRegistry.guessMediaType(File(fileName), given) shouldBe expected
        }
    }
})
