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

import dev.patrickgold.florisboard.ime.core.SubtypeLayoutMap
import dev.patrickgold.florisboard.ime.core.SubtypePreset
import dev.patrickgold.florisboard.ime.nlp.PunctuationRule
import dev.patrickgold.florisboard.ime.popup.PopupMappingComponent
import dev.patrickgold.florisboard.ime.text.composing.WithRules
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardExtensionRepositoryTest :
    FunSpec({
        test("indexes known layouts and keeps the later component when IDs collide") {
            val first = extension("org.example.keyboard", "first")
            val second = extension("org.example.keyboard", "second")
            val snapshot = indexKeyboardExtensions(listOf(first, second), generation = 7L)
            val component = ExtensionComponentName("org.example.keyboard", "shared")

            snapshot.generation shouldBe 7L
            snapshot.composers[component] shouldBe second.composers.single()
            snapshot.currencySets[component] shouldBe second.currencySets.single()
            snapshot.layouts[LayoutType.CHARACTERS]?.get(component) shouldBe
                second.layouts.getValue("characters").single()
            snapshot.popupMappings[component] shouldBe second.popupMappings.single()
            snapshot.punctuationRules[component] shouldBe second.punctuationRules.single()
            snapshot.layouts.keys shouldBe LayoutType.entries.toSet()
            snapshot.layouts.values.flatMap { it.keys } shouldBe listOf(component)
        }

        test("preserves the preset priority and keeps duplicate presets") {
            val tags = listOf("fr-FR", "en-CA", "en-AU", "de-DE", "en-US", "en-UK")
            val presets = tags.map(::preset)
            val first = extension("org.example.first", "first").copy(subtypePresets = presets)
            val second = extension("org.example.second", "second").copy(subtypePresets = listOf(preset("en-US")))

            val indexed = indexKeyboardExtensions(listOf(first, second), 1L).subtypePresets

            indexed.map { it.locale.languageTag() }.take(4) shouldBe
                listOf("en-US", "en-UK", "en-AU", "en-CA")
            indexed.size shouldBe presets.size + 1
            indexed.count { it.locale.languageTag() == "en-US" } shouldBe 2
            indexed.drop(4).map { it.locale.displayName() } shouldBe
                indexed.drop(4).map { it.locale.displayName() }.sorted()
        }

        test("publishes each refresh as one new snapshot with no stale entries") {
            runTest {
                val source = MutableStateFlow<List<KeyboardExtension>>(emptyList())
                val repository = KeyboardExtensionRepository(source, backgroundScope)
                runCurrent()
                val observed = mutableListOf<KeyboardExtensionSnapshot>()
                backgroundScope.launch { repository.snapshot.collect { observed.add(it) } }
                runCurrent()

                source.value = listOf(extension("org.example.first", "first"))
                runCurrent()
                source.value = listOf(extension("org.example.second", "second"))
                runCurrent()

                observed.map { it.generation } shouldBe listOf(1L, 2L, 3L)
                observed[1].composers.keys shouldBe setOf(ExtensionComponentName("org.example.first", "shared"))
                observed[2].composers.keys shouldBe setOf(ExtensionComponentName("org.example.second", "shared"))
                observed[2].layouts[LayoutType.CHARACTERS]?.keys shouldBe
                    setOf(ExtensionComponentName("org.example.second", "shared"))
            }
        }
    })

private fun extension(id: String, label: String) = KeyboardExtension(
    meta = ExtensionMeta(
        id = id,
        version = "1.0.0",
        title = label,
        maintainers = emptyList(),
        license = "Apache-2.0",
    ),
    composers = listOf(WithRules("shared", label, emptyMap())),
    currencySets = listOf(CurrencySet("shared", label, emptyList())),
    layouts = mapOf(
        "characters" to listOf(LayoutArrangementComponent("shared", label, emptyList(), "ltr")),
        "future-layout" to listOf(LayoutArrangementComponent("ignored", label, emptyList(), "ltr")),
    ),
    popupMappings = listOf(PopupMappingComponent("shared", label, emptyList())),
    punctuationRules = listOf(PunctuationRule.Fallback.copy(id = "shared", label = label)),
)

private fun preset(tag: String) = SubtypePreset(
    locale = FlorisLocale.fromTag(tag),
    composer = ExtensionComponentName("org.example.keyboard", "shared"),
    currencySet = ExtensionComponentName("org.example.keyboard", "shared"),
    preferred = SubtypeLayoutMap(),
)
