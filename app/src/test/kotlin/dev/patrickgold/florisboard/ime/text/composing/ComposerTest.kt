/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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

        test("Hangul combines and splits syllables without changing unmatched input") {
            val composer = HangulUnicode()
            for ((before, inserted, action) in listOf(
                Triple("", "ㅏtail", 0 to "ㅏtail"),
                Triple("가", "", 0 to ""),
                Triple("prefixㄱ", "ㅏ", 1 to "가"),
                Triple("가", "ㄱ", 1 to "각"),
                Triple("각", "ㅅ", 1 to "갃"),
                Triple("각", "ㅏ", 1 to "가가"),
                Triple("갃", "ㅏ", 1 to "각사"),
                Triple("고", "ㅏ", 1 to "과"),
                Triple("구", "ㅓ", 1 to "궈"),
                Triple("ㅗ", "ㅏ", 1 to "ㅘ"),
                Triple("ㅡ", "ㅣ", 1 to "ㅢ"),
                Triple("ㄱ", "ㅅ", 1 to "ㄳ"),
                Triple("ㅂ", "ㅅ", 1 to "ㅄ"),
                Triple("가", "_tail", 0 to "_tail"),
                Triple("과", "ㅣtail", 0 to "ㅣtail"),
                Triple("\uABFF", "ㅏtail", 0 to "ㅏtail"),
                Triple("힤", "ㅏtail", 0 to "ㅏtail"),
                Triple("가", "🙂", 0 to "🙂"),
                Triple("ㄱ", "ㅏ\u0301", 1 to "가"),
            )) {
                composer.getActions(before, inserted) shouldBe action
            }
        }

        test("configured Hangul alphabets and independent reverse rules survive export") {
            val json = """{
                "${'$'}":"hangul-unicode", "id":"configured", "label":"Configured", "toRead":2,
                "initials":"ㄴㄱ", "medials":"ㅏㅐ", "finals":"_ㄱㄲㄳ",
                "medialComp":{"ㅏ":["ㅐ","ㅘ"]}, "finalComp":{"ㄱ":["ㅅ","ㄲ"]},
                "finalCompRev":{"ㄳ":["ㄱ","ㄴ"],"_":["ㄱ","ㄴ"]},
                "medialCompRev":{"ㅙ":["ㅗ","ㅐ"]}
            }"""
            val composer = ExtensionJsonConfig.decodeFromString<Composer>(json)

            composer.id shouldBe "configured"
            composer.label shouldBe "Configured"
            composer.toRead shouldBe 2
            composer.getActions("ㄱ", "ㅏ") shouldBe (1 to "까")
            composer.getActions("ㅏ", "ㅐ") shouldBe (1 to "ㅘ")
            composer.getActions("각", "ㅅ") shouldBe (1 to "갂")
            composer.getActions("갃", "ㅏ") shouldBe (1 to "각가")
            composer.getActions("가", "ㅏ") shouldBe (1 to "각가")
            ExtensionJsonConfig.parseToJsonElement(ExtensionJsonConfig.encodeToString(composer)) shouldBe
                ExtensionJsonConfig.parseToJsonElement(json)
        }

        test("configured Hangul reads malformed replacements only for matched actions") {
            val composer = ExtensionJsonConfig.decodeFromString<Composer>(
                """{"${'$'}":"hangul-unicode",
                    "finalComp":{"ㄱ":["ㅅ"]}, "finalCompRev":{},
                    "medialComp":{"ㅗ":["x"]}, "medialCompRev":{}
                }""",
            )

            composer.getActions("각", "text") shouldBe (0 to "text")
            composer.getActions("곡", "x") shouldBe (0 to "x")
            shouldThrow<IndexOutOfBoundsException> { composer.getActions("각", "ㅅ") }
            shouldThrow<IndexOutOfBoundsException> { composer.getActions("고", "x") }
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
