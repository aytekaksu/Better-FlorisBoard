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

import dev.patrickgold.florisboard.app.Routes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExtensionRouteEnumSerializationTest : FunSpec({
    test("extension routes keep their encoded enum names") {
        val importCases = listOf(
            ExtensionImportScreenType.EXT_ANY to "EXT_ANY",
            ExtensionImportScreenType.EXT_KEYBOARD to "EXT_KEYBOARD",
            ExtensionImportScreenType.EXT_THEME to "EXT_THEME",
            ExtensionImportScreenType.EXT_LANGUAGEPACK to "EXT_LANGUAGEPACK",
        )
        importCases.map { it.first }.toSet() shouldBe ExtensionImportScreenType.entries.toSet()
        importCases.forEach { (type, expected) ->
            val route = Routes.Ext.Import(type)
            val encoded = Json.encodeToString(route)
            Json.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content shouldBe expected
            Json.decodeFromString<Routes.Ext.Import>(encoded) shouldBe route
        }
        val listCases = listOf(
            ExtensionListScreenType.EXT_THEME to "EXT_THEME",
            ExtensionListScreenType.EXT_KEYBOARD to "EXT_KEYBOARD",
            ExtensionListScreenType.EXT_LANGUAGEPACK to "EXT_LANGUAGEPACK",
        )
        listCases.map { it.first }.toSet() shouldBe ExtensionListScreenType.entries.toSet()
        listCases.forEach { (type, expected) ->
            val route = Routes.Ext.List(type)
            val encoded = Json.encodeToString(route)
            Json.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content shouldBe expected
            Json.decodeFromString<Routes.Ext.List>(encoded) shouldBe route
        }
    }
})
