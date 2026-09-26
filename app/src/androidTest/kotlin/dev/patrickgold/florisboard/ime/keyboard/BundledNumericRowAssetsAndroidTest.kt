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

package dev.patrickgold.florisboard.ime.keyboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.lib.io.DefaultJsonConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BundledNumericRowAssetsAndroidTest {
    @Test
    fun packagedNumericRowsMatchTheOriginalLayouts() {
        val expected = mapOf(
            // Canonical JSON hashes of the original 17 rows; change only for an intentional layout edit.
            "bengali" to "1e35c502f45f7da0eda2267395c7a9dbc20be046b42da45e865010f7e177c75f",
            "cjk" to "1710b8b2e274525c22d9ba8e1c3190e78e0a2ffc4cba48acbec77092f0e75845",
            "czech" to "1567a60c90b64186acc8bb9722d224ecd0e1592b1f3b7a79f4eee89a4f9c6e11",
            "devanagari" to "a85bbad608000fb3eb29c2f137bb6a1722a3846698245305d4cb6cb511bec6e9",
            "eastern_arabic" to "d16d3edcbdbbb057dd2a8cfe9dbf4eb91f9f501f83cb3c5b6edcc368e3d0efd0",
            "gujarati" to "d9807eb0cd15641d2d5f5172b8c50c18dd046d1d41db48585e327d1bcfeea4b8",
            "gurmukhi" to "00bfe4cfdb9d56c667d9d98a17781d8d588216b4cdfa8d32f65182b15090c1a9",
            "kannada" to "49de557e4d0ad02507ea1f536d730663891b3e493058feccd8ed7b5a57e6c10e",
            "malayalam" to "48de80622b730625b1de7c284445f0b0d62de772e52ea99b3e831dda4e3a7905",
            "neo2" to "db43af2373994a1f3755ac07bf18698e4966a1058793f6ed866a77c6beb11e99",
            "oriya" to "bc9e222851e49fcbf21be97f1f63cfff4e5025d105e03a1f27c1985b4436b3f0",
            "persian" to "3e50c5e2262ca619a85933942d225e0918d74339031ef7b09cbb84f1677c7755",
            "tamil" to "2c9f2492c7c7e1c48df8038aacf03022df4c8d0cd6add341a8ba42f175c7263f",
            "telugu" to "9ec50bb07161693146f902b1851afd32f1ebf55219adbdc22a69495d3f3a8f0d",
            "thai" to "7d3fc4817ba189ece4f3ea569f99f7f8ed1ca8b648c60efffb16a9adc74d4205",
            "warang_citi" to "f4ff207085ae9a76bb8a78645e5591eacf31df62779dcedcc6004294b3d18708",
            "western_arabic" to "fe94f336869877ab7f7e907d86eb2a32649578443bb86694e767e7876a653b04",
        )
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val root = "ime/keyboard/org.florisboard.layouts"
        val directory = "$root/layouts/numericRow"
        val names = assets.list(directory).orEmpty().map { it.removeSuffix(".json") }.toSet()
        assertEquals(expected.keys, names)

        val extension = assets.open("$root/extension.json").bufferedReader().use { reader ->
            ExtensionJsonConfig.decodeFromString(KeyboardExtension.serializer(), reader.readText())
        }
        val components = extension.layouts.getValue(LayoutTypeId.NUMERIC_ROW)
        assertEquals(expected.keys, components.map { it.id }.toSet())
        assertEquals(
            expected.keys.mapTo(mutableSetOf()) { "layouts/numericRow/$it.json" },
            components.mapTo(mutableSetOf()) { it.arrangementFile(LayoutType.NUMERIC_ROW) },
        )

        for ((name, digest) in expected) {
            val layout = assets.open("$directory/$name.json").bufferedReader().use { it.readText() }
            assertEquals(name, digest, sha256(canonical(Json.parseToJsonElement(layout)) + "\n"))
            DefaultJsonConfig.decodeFromString<LayoutArrangement>(layout)
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
