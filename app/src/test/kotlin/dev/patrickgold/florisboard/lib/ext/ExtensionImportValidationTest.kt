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

package dev.patrickgold.florisboard.lib.ext

import dev.patrickgold.florisboard.ime.core.SubtypeLayoutMap
import dev.patrickgold.florisboard.ime.core.SubtypeNlpProviderMap
import dev.patrickgold.florisboard.ime.core.SubtypePreset
import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.keyboard.CurrencySet
import dev.patrickgold.florisboard.ime.keyboard.KeyboardExtension
import dev.patrickgold.florisboard.ime.keyboard.LayoutArrangementComponent
import dev.patrickgold.florisboard.ime.nlp.LanguagePackComponent
import dev.patrickgold.florisboard.ime.nlp.LanguagePackExtension
import dev.patrickgold.florisboard.ime.nlp.PunctuationRule
import dev.patrickgold.florisboard.ime.popup.PopupMappingComponent
import dev.patrickgold.florisboard.ime.popup.PopupSet
import dev.patrickgold.florisboard.ime.text.composing.WithRules
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponentImpl
import dev.patrickgold.florisboard.lib.FlorisLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ExtensionImportValidationTest :
    FunSpec({
        test("preflights manifest depth token and container work before decoding") {
            fun rulesJson(count: Int) = buildString {
                append("{\"rules\":{")
                repeat(count) { index ->
                    if (index > 0) append(',')
                    append("\"r").append(index).append("\":\"v\"")
                }
                append("}}")
            }

            rulesJson(4_096).hasBoundedExtensionManifestJsonShape() shouldBe true
            rulesJson(4_097).hasBoundedExtensionManifestJsonShape() shouldBe false
            "{\"items\":[${List(2_048) { "0" }.joinToString()}]}"
                .hasBoundedExtensionManifestJsonShape() shouldBe true
            "{\"items\":[${List(2_049) { "0" }.joinToString()}]}"
                .hasBoundedExtensionManifestJsonShape() shouldBe false
            (
                "{\"nested\":".repeat(33) +
                    "0" +
                    "}".repeat(33)
                ).hasBoundedExtensionManifestJsonShape() shouldBe false
            "{\"value\":\"${"x".repeat(32_769)}\"}"
                .hasBoundedExtensionManifestJsonShape() shouldBe false
        }

        test("accepts a bounded theme descriptor") {
            val extension = themeExtension(
                themes = listOf(
                    themeComponent(
                        id = "floris_day",
                        stylesheet = "stylesheets/floris_day.json",
                    ),
                ),
            )

            extension.validateForImport().errors shouldBe emptySet()
        }

        test("reports typed errors without rejected content") {
            val extension = ThemeExtension(
                meta = validMeta().copy(
                    id = "../outside",
                    title = "\u0000",
                    homepage = "file:///private/data",
                    maintainers = emptyList(),
                ),
                dependencies = listOf("../dependency"),
                themes = listOf(themeComponent()),
            )

            val result = extension.validateForImport()

            result.errors shouldContain ExtensionImportValidationError.META_ID
            result.errors shouldContain ExtensionImportValidationError.META_TITLE
            result.errors shouldContain ExtensionImportValidationError.META_HOMEPAGE
            result.errors shouldContain ExtensionImportValidationError.META_MAINTAINERS
            result.errors shouldContain ExtensionImportValidationError.DEPENDENCIES
            result.toString().contains("../") shouldBe false
        }

        test("rejects duplicate themes and unsafe stylesheet paths") {
            val extension = themeExtension(
                themes = listOf(
                    themeComponent(stylesheet = "../outside.json"),
                    themeComponent(),
                    themeComponent(id = "floris_night", stylesheet = " "),
                ),
            )

            val result = extension.validateForImport()

            result.errors shouldContain ExtensionImportValidationError.DUPLICATE_COMPONENT_ID
            result.errors shouldContain ExtensionImportValidationError.THEME_STYLESHEET_PATH
        }

        test("bounds optional metadata and dependencies") {
            val extension = ThemeExtension(
                meta = validMeta().copy(
                    version = "v".repeat(65),
                    description = "d".repeat(4_097),
                    keywords = List(33) { "keyword$it" },
                    issueTracker = "not a web link",
                    license = "l".repeat(129),
                ),
                dependencies = listOf(
                    "org.example.extension",
                    "org.example.extension",
                ),
                themes = listOf(themeComponent()),
            )

            val result = extension.validateForImport()

            result.errors shouldContain ExtensionImportValidationError.META_VERSION
            result.errors shouldContain ExtensionImportValidationError.META_DESCRIPTION
            result.errors shouldContain ExtensionImportValidationError.META_KEYWORDS
            result.errors shouldContain ExtensionImportValidationError.META_ISSUE_TRACKER
            result.errors shouldContain ExtensionImportValidationError.META_LICENSE
            result.errors shouldContain ExtensionImportValidationError.DEPENDENCIES
        }

        test("rejects unsafe maintainer action targets") {
            val extension = ThemeExtension(
                meta = validMeta().copy(
                    maintainers = listOf(
                        ExtensionMaintainer(
                            name = "maintainer",
                            email = "maintainer@example.com?body=private",
                            url = "intent://private-action",
                        ),
                    ),
                ),
                themes = listOf(themeComponent()),
            )

            extension.validateForImport().errors shouldContain
                ExtensionImportValidationError.META_MAINTAINERS
        }

        test("normalizes the documented bare maintainer URL form") {
            val extension = ThemeExtension(
                meta = validMeta().copy(
                    maintainers = listOf(
                        ExtensionMaintainer(
                            name = "maintainer",
                            url = "maintainer.example.com/profile",
                        ),
                    ),
                ),
                themes = listOf(themeComponent()),
            )

            extension.validateForImport().isValid shouldBe true
            "maintainer.example.com/profile".safeMaintainerWebUrlOrNull() shouldBe
                "https://maintainer.example.com/profile"
            "intent://private-action".safeMaintainerWebUrlOrNull() shouldBe null
        }

        test("requires bounded nonblank component fields") {
            val extension = themeExtension(
                themes = listOf(
                    ThemeExtensionComponentImpl(
                        id = "Floris-Day",
                        label = " ",
                        authors = listOf(""),
                    ),
                ),
            )

            val result = extension.validateForImport()

            result.errors shouldContain ExtensionImportValidationError.COMPONENT_ID
            result.errors shouldContain ExtensionImportValidationError.COMPONENT_LABEL
            result.errors shouldContain ExtensionImportValidationError.COMPONENT_AUTHORS
        }

        test("accepts bundled language IDs with uppercase locale segments") {
            val extension = languageExtension(
                items = listOf(
                    LanguagePackComponent(
                        id = "zh_CN_zhengma",
                        label = "Chinese (China)",
                        authors = listOf("maintainer"),
                    ),
                    LanguagePackComponent(
                        id = "zh_TW_cangjielarge",
                        label = "Chinese (Taiwan)",
                        authors = listOf("maintainer"),
                    ),
                ),
            )

            extension.validateForImport().isValid shouldBe true
        }

        test("rejects unsafe language database paths") {
            val extension = languageExtension(
                sqlitePath = "../outside.sqlite3",
                items = listOf(
                    LanguagePackComponent(
                        id = "zh_CN_zhengma",
                        label = "Chinese (China)",
                        authors = listOf("maintainer"),
                    ),
                ),
            )

            extension.validateForImport().errors shouldContain
                ExtensionImportValidationError.LANGUAGE_SQLITE_PATH
        }

        test("rejects an explicitly overridden unsafe SQL table identifier") {
            val extension = Json.decodeFromString(
                LanguagePackExtension.serializer(),
                """
                {
                  "meta": {
                    "id": "org.example.languagepack",
                    "version": "1.0",
                    "title": "Language pack",
                    "maintainers": [ "maintainer" ],
                    "license": "apache-2.0"
                  },
                  "items": [
                    {
                      "id": "zh_CN_example",
                      "label": "Example",
                      "authors": [ "maintainer" ],
                      "hanShapeBasedTable": "words;drop"
                    }
                  ]
                }
                """.trimIndent(),
            )

            extension.validateForImport().errors shouldContain
                ExtensionImportValidationError.LANGUAGE_SQL_TABLE
        }

        test("keyboard component uniqueness is scoped by component type") {
            val valid = KeyboardExtension(
                meta = validMeta(),
                punctuationRules = listOf(
                    PunctuationRule(
                        id = "pt-BR",
                        symbolsPrecedingAutoSpace = ".",
                        symbolsFollowingAutoSpace = "",
                        symbolsPrecedingPhantomSpace = ".",
                        symbolsFollowingPhantomSpace = "",
                        symbolsTerminatingSentence = ".",
                    ),
                ),
                popupMappings = listOf(
                    PopupMappingComponent("pt-BR", authors = listOf("maintainer")),
                ),
            )
            val duplicate = valid.copy(
                popupMappings = listOf(
                    PopupMappingComponent("pt-BR", authors = listOf("maintainer")),
                    PopupMappingComponent("pt-BR", authors = listOf("maintainer")),
                ),
            )

            valid.validateForImport().isValid shouldBe true
            duplicate.validateForImport().errors shouldContain
                ExtensionImportValidationError.DUPLICATE_COMPONENT_ID
        }

        test("bounds composer rule count and aggregate work") {
            val boundaryRules = (0 until 4_096).associate { index ->
                "k${index.toString(36)}" to "v"
            }
            val boundary = KeyboardExtension(
                meta = validMeta(),
                composers = listOf(
                    WithRules("rules-a", "Rules A", boundaryRules),
                    WithRules("rules-b", "Rules B", boundaryRules),
                ),
            )
            val tooMany = boundary.copy(
                composers = boundary.composers +
                    WithRules("rules-c", "Rules C", mapOf("trigger" to "replacement")),
            )

            boundary.validateForImport().isValid shouldBe true
            tooMany.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_COMPOSER_RULES
        }

        test("bounds composer triggers replacements and total text") {
            val oversizedField = KeyboardExtension(
                meta = validMeta(),
                composers = listOf(
                    WithRules(
                        "oversized",
                        "Oversized",
                        mapOf(
                            "k".repeat(33) to "value",
                            "trigger" to "v".repeat(65),
                            "control" to "\u0000",
                        ),
                    ),
                ),
            )
            val denseRules = (0 until 4_096).associate { index ->
                "k$index".padEnd(32, 'x') to "v".repeat(64)
            }
            val oversizedAggregate = KeyboardExtension(
                meta = validMeta(),
                composers = listOf(WithRules("dense", "Dense", denseRules)),
            )

            oversizedField.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_COMPOSER_RULES
            oversizedAggregate.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_COMPOSER_RULES
        }

        test("accepts six simple currency slots and rejects nested or excessive slots") {
            val slots = List(6) { TextKeyData(code = '$'.code, label = "$") }
            val valid = KeyboardExtension(
                meta = validMeta(),
                currencySets = listOf(CurrencySet("dollar", "Dollar", slots)),
            )
            val nestedPopup = PopupSet<AbstractKeyData>(
                relevant = List(256) { TextKeyData(code = '€'.code, label = "€") },
            )
            val invalid = KeyboardExtension(
                meta = validMeta(),
                currencySets = listOf(
                    CurrencySet("too-many", "Too many", slots + slots.first()),
                    CurrencySet(
                        "nested",
                        "Nested",
                        listOf(TextKeyData(code = '$'.code, label = "$", popup = nestedPopup)),
                    ),
                ),
            )

            valid.validateForImport().isValid shouldBe true
            valid.currencySets.single().toString() shouldBe "CurrencySet { slotCount=6 }"
            invalid.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_CURRENCY_SLOTS
        }

        test("bounds punctuation matching text per field and in aggregate") {
            val boundarySymbols = (0 until 128).joinToString("") { index ->
                (0x0100 + index).toChar().toString()
            }
            fun punctuation(id: String, symbols: String) = PunctuationRule(
                id = id,
                symbolsPrecedingAutoSpace = symbols,
                symbolsFollowingAutoSpace = symbols,
                symbolsPrecedingPhantomSpace = symbols,
                symbolsFollowingPhantomSpace = symbols,
                symbolsTerminatingSentence = symbols,
            )

            val boundary = KeyboardExtension(
                meta = validMeta(),
                punctuationRules = listOf(punctuation("boundary", boundarySymbols)),
            )
            val oversizedField = boundary.copy(
                punctuationRules = listOf(punctuation("oversized", boundarySymbols + '\u0200')),
            )
            val oversizedAggregate = boundary.copy(
                punctuationRules = List(52) { punctuation("p$it", boundarySymbols) },
            )

            boundary.validateForImport().isValid shouldBe true
            oversizedField.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_PUNCTUATION
            oversizedAggregate.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_PUNCTUATION
        }

        test("bounds popup mapping resource overrides") {
            val valid = KeyboardExtension(
                meta = validMeta(),
                popupMappings = listOf(
                    PopupMappingComponent(
                        id = "custom",
                        authors = listOf("maintainer"),
                        mappingFile = "popupMappings/custom.json",
                    ),
                ),
            )
            val invalid = valid.copy(
                popupMappings = listOf(
                    PopupMappingComponent(
                        id = "custom",
                        authors = listOf("maintainer"),
                        mappingFile = "../outside.json",
                    ),
                ),
            )

            valid.validateForImport().isValid shouldBe true
            invalid.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_POPUP_MAPPING
        }

        test("accepts 1024 layouts and rejects unknown groups directions and references") {
            fun layouts(prefix: String, count: Int) = List(count) { index ->
                LayoutArrangementComponent(
                    id = "$prefix$index",
                    label = "Layout $index",
                    authors = listOf("maintainer"),
                    direction = "ltr",
                )
            }

            val boundary = KeyboardExtension(
                meta = validMeta(),
                layouts = mapOf(
                    "characters" to layouts("c", 512),
                    "symbols" to layouts("s", 512),
                ),
            )
            val excessive = boundary.copy(
                layouts = boundary.layouts + ("phone" to layouts("p", 1)),
            )
            val malformed = KeyboardExtension(
                meta = validMeta(),
                layouts = mapOf(
                    "unknown" to listOf(
                        LayoutArrangementComponent(
                            id = "broken",
                            label = "Broken",
                            authors = listOf("maintainer"),
                            direction = "sideways",
                            modifier = ExtensionComponentName("../outside", "modifier"),
                            arrangementFile = "layouts/custom/broken.json",
                        ),
                    ),
                ),
            )

            boundary.validateForImport().isValid shouldBe true
            excessive.validateForImport().errors shouldContain
                ExtensionImportValidationError.COMPONENT_LIMIT
            malformed.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_LAYOUT
            malformed.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_REFERENCE
        }

        test("bounds subtype locale provider and component references") {
            val validPreset = SubtypePreset(
                locale = FlorisLocale.fromTag("en-US"),
                composer = ExtensionComponentName("org.example.extension", "composer"),
                currencySet = ExtensionComponentName("org.example.extension", "currency"),
                preferred = SubtypeLayoutMap(),
            )
            val valid = KeyboardExtension(
                meta = validMeta(),
                subtypePresets = listOf(validPreset),
            )
            val invalid = valid.copy(
                subtypePresets = listOf(
                    validPreset.copy(
                        locale = FlorisLocale.fromTag("x".repeat(65)),
                        nlpProviders = SubtypeNlpProviderMap(spelling = "invalid provider"),
                        composer = ExtensionComponentName("../outside", "composer"),
                    ),
                ),
            )

            valid.validateForImport().isValid shouldBe true
            invalid.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_SUBTYPE_PRESET
            invalid.validateForImport().errors shouldContain
                ExtensionImportValidationError.KEYBOARD_REFERENCE
        }

        test("bounds language key-code alphabets and explicit locales") {
            val boundaryAlphabet = (0 until 128).joinToString("") { index ->
                (0x0100 + index).toChar().toString()
            }
            val boundary = languageExtension(
                items = listOf(
                    LanguagePackComponent(
                        id = "zh_CN_boundary",
                        label = "Boundary",
                        authors = listOf("maintainer"),
                        hanShapeBasedKeyCode = boundaryAlphabet,
                    ),
                ),
            )
            val invalid = languageExtension(
                items = listOf(
                    LanguagePackComponent(
                        id = "zh_CN_oversized",
                        label = "Oversized",
                        authors = listOf("maintainer"),
                        hanShapeBasedKeyCode = boundaryAlphabet + '\u0200',
                    ),
                    LanguagePackComponent(
                        id = "zh_CN_duplicate",
                        label = "Duplicate",
                        authors = listOf("maintainer"),
                        hanShapeBasedKeyCode = "aab",
                    ),
                    LanguagePackComponent(
                        id = "zh_CN_whitespace",
                        label = "Whitespace",
                        authors = listOf("maintainer"),
                        hanShapeBasedKeyCode = "ab c",
                    ),
                    LanguagePackComponent(
                        id = "zh_CN_locale",
                        label = "Locale",
                        authors = listOf("maintainer"),
                        locale = FlorisLocale.fromTag("x".repeat(65)),
                    ),
                ),
            )

            boundary.validateForImport().isValid shouldBe true
            invalid.validateForImport().errors shouldContain
                ExtensionImportValidationError.LANGUAGE_KEY_CODE
            invalid.validateForImport().errors shouldContain
                ExtensionImportValidationError.LANGUAGE_LOCALE
        }
    })

private fun validMeta() = ExtensionMeta(
    id = "org.example.extension",
    version = "1.0.0",
    title = "Example extension",
    description = "A compact description.",
    maintainers = listOf(ExtensionMaintainer("maintainer")),
    license = "apache-2.0",
)

private fun themeComponent(id: String = "floris_day", stylesheet: String? = null) = ThemeExtensionComponentImpl(
    id = id,
    label = "Floris Day",
    authors = listOf("maintainer"),
    stylesheetPath = stylesheet,
)

private fun themeExtension(themes: List<ThemeExtensionComponentImpl>) = ThemeExtension(
    meta = validMeta(),
    themes = themes,
)

private fun languageExtension(sqlitePath: String = "han.sqlite3", items: List<LanguagePackComponent>) =
    LanguagePackExtension(
        meta = validMeta(),
        items = items,
        hanShapeBasedSQLite = sqlitePath,
    )
