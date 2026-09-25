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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.florisboard.lib.snygg.SnyggStylesheet
import org.florisboard.lib.snygg.SnyggTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BundledThemeAssetsAndroidTest {
    @Test
    fun packagedThemesMatchTheOriginalStylesheets() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val directory = "ime/theme/org.florisboard.themes"
        // Hashes of the original stylesheets; change them only for an intentional theme edit.
        val expected = mapOf(
            "floris_day" to "18c4e49610caeca2ac8ff952f187f0ceba493db6fc43875a2b5fb122bbeed0b7",
            "floris_day_borderless" to "ae7776744caff285768826dff8e391a707990f55b62843c8d4a9cd9b2408814d",
            "floris_night" to "7f362e51076e007e060529dbb464115c5cbfa9d3d6f6aaf9bd9e7839185ae1ff",
            "floris_night_borderless" to "f6d6cdceca441f279b22e7b5a467e49903ea743c56bcc3420e29dde880f8f8f1",
            "floris_pure_night" to "a704f1e9e86f825ed2eeabd592925b621636d9df131a0cebf100f6023dc0d20a",
            "floris_pure_night_borderless" to "bffd21059c5bf3a2ea8e818a8a41bfe02360a37aa1c3a03ac8b3143ae298ed12",
        )
        val stylesheetDirectory = "$directory/stylesheets"
        val names = assets.list(stylesheetDirectory).orEmpty().map { it.removeSuffix(".json") }.toSet()
        assertEquals(expected.keys, names)

        val manifest = assets.open("$directory/extension.json").bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
        }
        val declaredNames = manifest.getValue("themes").jsonArray.map { theme ->
            theme.jsonObject.getValue("id").jsonPrimitive.content
        }.toSet()
        assertEquals(expected.keys, declaredNames)

        for ((name, digest) in expected) {
            val stylesheetJson = assets.open("$stylesheetDirectory/$name.json").bufferedReader().use { it.readText() }
            val parsed = Json.parseToJsonElement(stylesheetJson)
            assertEquals(name, digest, sha256(canonical(parsed) + "\n"))
            SnyggTheme.compileFrom(SnyggStylesheet.fromJson(stylesheetJson).getOrThrow())
        }
    }

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
            "${JsonPrimitive(key)}:${canonical(value)}"
        }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        else -> element.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
