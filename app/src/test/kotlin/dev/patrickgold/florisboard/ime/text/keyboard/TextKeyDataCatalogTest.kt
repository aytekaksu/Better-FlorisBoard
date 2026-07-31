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

package dev.patrickgold.florisboard.ime.text.keyboard

import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.lang.reflect.Modifier

class TextKeyDataCatalogTest :
    FunSpec({
        val predefinedKeys =
            TextKeyData.Companion::class.java.declaredMethods
                .asSequence()
                .filter { method ->
                    Modifier.isPublic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        method.returnType == TextKeyData::class.java
                }
                .map { method -> method.invoke(TextKeyData.Companion) as TextKeyData }
                .toList()
        val lookupExclusions =
            setOf(
                TextKeyData.TOGGLE_FLOATING_WINDOW,
                TextKeyData.TOGGLE_RESIZE_MODE,
                TextKeyData.SHOW_SUBTYPE_PICKER,
            )

        test("predefined codes are unique and nonpositive lookup entries are canonical") {
            predefinedKeys.size shouldBe 62
            predefinedKeys.map { it.code }.distinct().size shouldBe predefinedKeys.size

            predefinedKeys
                .filter { it.code <= 0 && it !in lookupExclusions }
                .forEach { key ->
                    TextKeyData.getCodeInfoAsTextKeyData(key.code) shouldBeSameInstanceAs key
                }
        }

        test("lookup keeps its intentional exclusions and positive-code behavior") {
            lookupExclusions.forEach { key ->
                TextKeyData.getCodeInfoAsTextKeyData(key.code) shouldBe null
            }
            TextKeyData.getCodeInfoAsTextKeyData(Int.MIN_VALUE) shouldBe null

            val space = TextKeyData.getCodeInfoAsTextKeyData(KeyCode.SPACE)!!
            space shouldBe TextKeyData(KeyType.CHARACTER, KeyCode.SPACE, " ")
            (space === TextKeyData.SPACE) shouldBe false
        }

        test("representative catalog metadata remains unchanged") {
            predefinedKeys.map { it.groupId }.distinct() shouldBe listOf(KeyData.GROUP_DEFAULT)
            predefinedKeys.map { it.popup }.distinct() shouldBe listOf(null)
            TextKeyData.UNSPECIFIED shouldMatch
                TextKeyData(KeyType.UNSPECIFIED, KeyCode.UNSPECIFIED, "unspecified")
            TextKeyData.CTRL shouldMatch TextKeyData(KeyType.MODIFIER, KeyCode.CTRL, "ctrl")
            TextKeyData.DELETE shouldMatch TextKeyData(KeyType.ENTER_EDITING, KeyCode.DELETE, "delete")
            TextKeyData.ARROW_LEFT shouldMatch
                TextKeyData(KeyType.NAVIGATION, KeyCode.ARROW_LEFT, "arrow_left")
            TextKeyData.SHOW_SUBTYPE_PICKER shouldMatch
                TextKeyData(KeyType.FUNCTION, KeyCode.SHOW_SUBTYPE_PICKER, "subtype_picker")
            TextKeyData.AUTOCORRECT_PLUGIN_UI shouldMatch
                TextKeyData(KeyType.SYSTEM_GUI, KeyCode.AUTOCORRECT_PLUGIN_UI, "autocorrect_plugin_ui")
        }
    })

private infix fun TextKeyData.shouldMatch(expected: TextKeyData) {
    this shouldBe expected
}
