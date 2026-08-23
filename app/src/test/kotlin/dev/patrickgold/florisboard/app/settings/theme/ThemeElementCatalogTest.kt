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

package dev.patrickgold.florisboard.app.settings.theme

import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.lib.snygg.SnyggAnnotationRule
import org.florisboard.lib.snygg.SnyggElementRule
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.SnyggSinglePropertySetEditor
import java.security.MessageDigest
import java.util.Base64

class ThemeElementCatalogTest :
    FunSpec({
        test("catalog preserves every persisted element name and projection") {
            val entries = FlorisImeUi.entries
            val elementNames = entries.map { it.elementName }
            // Persisted names and declaration order form one compatibility snapshot.
            val snapshot = MessageDigest.getInstance("SHA-256").digest(elementNames.joinToString("|").toByteArray())

            entries.size shouldBe 95
            Base64.getEncoder().encodeToString(snapshot) shouldBe "JwcGLVBAtTOJBE6EqQFkZSa12TyuHOptRnQ2Tvj5JpU="
            elementNames.toSet().size shouldBe entries.size
            entries.map { it.resId }.toSet().size shouldBe entries.size
            FlorisImeUi.elementNames shouldBe elementNames
            FlorisImeUi.elementNamesToOrdinals shouldBe
                elementNames.withIndex().associate { it.value to it.index }

            FlorisImeUi.elementNamesToTranslation shouldBe buildMap {
                put("defines", R.string.snygg__rule_annotation__defines)
                put("font", R.string.snygg__rule_annotation__font)
                entries.forEach { put(it.elementName, it.resId) }
            }
        }

        test("each element retains its matching string resource") {
            FlorisImeUi.entries.forEach { entry ->
                val resourceName = "snygg__rule_element__${entry.elementName.replace('-', '_')}"
                R.string::class.java.getField(resourceName).getInt(null) shouldBe entry.resId
            }
        }

        test("theme editor orders known elements before custom names and retains selectors") {
            val window = SnyggElementRule(FlorisImeUi.Window.elementName)
            val key = SnyggElementRule(FlorisImeUi.Key.elementName)
            val pressedKey = SnyggElementRule(FlorisImeUi.Key.elementName, selector = SnyggSelector.PRESSED)
            val firstCustom = SnyggElementRule("aaa-custom")
            val lastCustom = SnyggElementRule("zzz-custom")
            val editor = newEmptyThemeStylesheetEditor()

            listOf(lastCustom, pressedKey, key, firstCustom, window).forEach {
                editor.rules[it] = SnyggSinglePropertySetEditor()
            }

            editor.rules.keys.toList() shouldBe listOf(
                SnyggAnnotationRule.Defines,
                window,
                key,
                pressedKey,
                firstCustom,
                lastCustom,
            )
        }
    })
