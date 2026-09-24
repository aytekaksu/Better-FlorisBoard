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

package dev.patrickgold.florisboard.ime.text.composing

import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

@OptIn(ExperimentalSerializationApi::class)
class ComposerTest :
    FunSpec({
        test("an empty rule set behaves like the appender") {
            val composer = WithRules("empty", "Empty", emptyMap())

            composer.toRead shouldBe 0
            composer.getActions("before", "x") shouldBe (0 to "x")
        }

        test("the longest matching rule wins regardless of key case") {
            val composer = WithRules(
                "rules",
                "Rules",
                linkedMapOf("A" to "short", "Ba" to "long"),
            )

            composer.getActions("b", "A") shouldBe (1 to "long")
        }

        test("only an uppercase initial capitalizes the replacement") {
            val composer = WithRules(
                "case",
                "Case",
                mapOf("ab" to "value", "1b" to "value"),
            )

            composer.getActions("A", "b") shouldBe (1 to "VALUE")
            composer.getActions("a", "b") shouldBe (1 to "value")
            composer.getActions("1", "b") shouldBe (1 to "value")
        }

        test("replacement casing is independent of the device locale") {
            val composer = WithRules("case", "Case", mapOf("ab" to "i"))
            val originalLocale = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("tr"))
                composer.getActions("A", "b") shouldBe (1 to "I")
            } finally {
                Locale.setDefault(originalLocale)
            }
        }

        test("empty rule keys are rejected") {
            shouldThrow<IllegalArgumentException> {
                WithRules("invalid", "Invalid", mapOf("" to "value"))
            }
        }

        test("every Kana dakuten mapping round-trips through each mark") {
            KanaUnicode().assertRoundTrips(DAKUTEN_PAIRS, "゙゛ﾞ")
        }

        test("every Kana handakuten mapping round-trips through each mark") {
            KanaUnicode().assertRoundTrips(HANDAKUTEN_PAIRS, "゚゜ﾟ")
        }

        test("every BMP small Kana mapping round-trips") {
            KanaUnicode().assertRoundTrips(SMALL_KANA_PAIRS, SMALL_KANA_SENTINEL)
        }

        test("supplementary small Kana remain intentionally one-way") {
            val composer = KanaUnicode()
            SUPPLEMENTARY_SMALL_KANA_PAIRS.split(' ').forEach { pair ->
                val base = pair.first().toString()
                val transformed = pair.drop(1)
                composer.getActions(base, SMALL_KANA_SENTINEL) shouldBe (1 to transformed)
                composer.getActions(transformed, SMALL_KANA_SENTINEL) shouldBe (0 to "")
            }
        }

        test("Kana marks retain their combining and fallback behavior") {
            val composer = KanaUnicode()
            "゙゚".forEach { composer.getActions("", it.toString()) shouldBe (0 to "") }
            composer.getActions("゙", "゙") shouldBe (1 to "")
            composer.getActions("゙", "゚") shouldBe (1 to "゚")
            "゛ﾞ゜ﾟ".forEach { composer.getActions("", it.toString()) shouldBe (0 to it.toString()) }
            "゙゛ﾞ゚゜ﾟ".forEach { composer.getActions("A", it.toString()) shouldBe (0 to it.toString()) }
            composer.getActions("prefixか", "゙") shouldBe (1 to "が")
            composer.getActions("か", "text") shouldBe (0 to "text")
        }

        test("Kana transformations normalize across modifier families") {
            val composer = KanaUnicode()
            H_ROW_TRIPLES.chunked(3).forEach { triple ->
                val (base, voiced, semivoiced) = triple.toCharArray()
                "゙゛ﾞ".forEach {
                    composer.getActions(semivoiced.toString(), it.toString()) shouldBe
                        (1 to voiced.toString())
                }
                "゚゜ﾟ".forEach {
                    composer.getActions(voiced.toString(), it.toString()) shouldBe
                        (1 to semivoiced.toString())
                }
            }
            composer.getActions("ヵ", "゙") shouldBe (1 to "ガ")
            composer.getActions("バ", SMALL_KANA_SENTINEL) shouldBe (1 to "ㇵ")
            composer.getActions("ㇵ", "゚") shouldBe (1 to "パ")
        }

        test("Kana serialization exposes configuration rather than lookup tables") {
            val descriptor = KanaUnicode.serializer().descriptor
            (0 until descriptor.elementsCount).map(descriptor::getElementName) shouldBe
                listOf("id", "label", "toRead", "sticky")

            val encodedText = ExtensionJsonConfig.encodeToString<Composer>(KanaUnicode())
            val encoded = ExtensionJsonConfig.parseToJsonElement(encodedText).jsonObject
            encoded.keys shouldBe setOf("$")
            encoded.getValue("$").jsonPrimitive.content shouldBe "kana-unicode"
            ExtensionJsonConfig.decodeFromString<Composer>(encodedText)::class shouldBe KanaUnicode::class

            val configured = ExtensionJsonConfig.decodeFromString<Composer>(
                """{"${'$'}":"kana-unicode","sticky":true,"smallSentinel":"?"}""",
            ) as KanaUnicode
            configured.sticky shouldBe true
            configured.getActions("が", "゙") shouldBe (1 to "が")
        }
    })

private fun KanaUnicode.assertRoundTrips(pairs: String, marks: String) {
    pairs.chunked(2).forEach { pair ->
        val (base, transformed) = pair.toCharArray()
        marks.forEach { mark ->
            getActions(base.toString(), mark.toString()) shouldBe (1 to transformed.toString())
            getActions(transformed.toString(), mark.toString()) shouldBe (1 to base.toString())
        }
    }
}

private const val DAKUTEN_PAIRS =
    "うゔかがきぎくぐけげこごさざしじすずせぜそぞただちぢつづてでとどはばひびふぶへべほぼ" +
        "ウヴカガキギクグケゲコゴサザシジスズセゼソゾタダチヂツヅテデトドハバヒビフブヘベホボ" +
        "ワヷヰヸヱヹヲヺゝゞヽヾ"
private const val HANDAKUTEN_PAIRS = "はぱひぴふぷへぺほぽハパヒピフプヘペホポ"
private const val SMALL_KANA_PAIRS =
    "あぁいぃえぇうぅおぉかゕけゖつっやゃゆゅよょわゎ" +
        "アァイィエェウゥオォカヵクㇰケヶシㇱスㇲツットㇳヌㇴハㇵヒㇶフㇷヘㇸホㇹムㇺ" +
        "ヤャユュヨョラㇻリㇼルㇽレㇾロㇿワヮ"
private const val SUPPLEMENTARY_SMALL_KANA_PAIRS = "ゐ𛅐 ゑ𛅑 を𛅒 ヰ𛅤 ヱ𛅥 ヲ𛅦 ン𛅧"
private const val H_ROW_TRIPLES = "はばぱひびぴふぶぷへべぺほぼぽハバパヒビピフブプヘベペホボポ"
private const val SMALL_KANA_SENTINEL = "〓"
