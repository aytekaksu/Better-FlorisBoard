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

package dev.patrickgold.florisboard.ime.core

import dev.patrickgold.florisboard.ime.keyboard.extCoreLayout
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SubtypeSelectionTest :
    FunSpec({
        val first = Subtype.DEFAULT.copy(id = 1L)
        val second = Subtype.DEFAULT.copy(id = 2L)
        val third = Subtype.DEFAULT.copy(id = 3L)

        test("empty or missing active subtype falls back to default") {
            adjacentSubtypeInOrder(emptyList(), first) shouldBe Subtype.DEFAULT
            adjacentSubtypeInOrder(listOf(first, second), third) shouldBe Subtype.DEFAULT
        }

        test("a single subtype selects itself in either direction") {
            adjacentSubtypeInOrder(listOf(first), first) shouldBe first
            adjacentSubtypeInOrder(listOf(first).asReversed(), first) shouldBe first
        }

        test("next and previous selection wrap in list order") {
            val order = listOf(first, second, third)
            val previousOrder = order.asReversed()

            adjacentSubtypeInOrder(order, first) shouldBe second
            adjacentSubtypeInOrder(order, second) shouldBe third
            adjacentSubtypeInOrder(order, third) shouldBe first
            adjacentSubtypeInOrder(previousOrder, first) shouldBe third
            adjacentSubtypeInOrder(previousOrder, second) shouldBe first
            adjacentSubtypeInOrder(previousOrder, third) shouldBe second
        }

        test("separated duplicate matches can replace an earlier selection") {
            val order = listOf(first, second, first, third)
            adjacentSubtypeInOrder(order, first) shouldBe third
            adjacentSubtypeInOrder(order.asReversed(), first) shouldBe third
            adjacentSubtypeInOrder(listOf(first, second, first), first) shouldBe first
        }

        test("adjacent equal entries can consume a pending selection") {
            adjacentSubtypeInOrder(listOf(first, first, second), first) shouldBe first
            adjacentSubtypeInOrder(listOf(first, first, second).asReversed(), first) shouldBe first
        }

        test("layout defaults keep their eight-field JSON shape") {
            val defaults = SubtypeLayoutMap()
            val encoded = SubtypeJsonConfig.encodeToString(defaults)
            val components = SubtypeJsonConfig.parseToJsonElement(encoded).jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.content }

            components shouldBe mapOf(
                "characters" to "org.florisboard.layouts:qwerty",
                "symbols" to "org.florisboard.layouts:western",
                "symbols2" to "org.florisboard.layouts:western",
                "numeric" to "org.florisboard.layouts:western_arabic",
                "numericAdvanced" to "org.florisboard.layouts:western_arabic",
                "numericRow" to "org.florisboard.layouts:western_arabic",
                "phone" to "org.florisboard.layouts:telpad",
                "phone2" to "org.florisboard.layouts:telpad",
            )
            SubtypeJsonConfig.decodeFromString<SubtypeLayoutMap>(encoded) shouldBe defaults
            SubtypeJsonConfig.decodeFromString<SubtypeLayoutMap>("{}") shouldBe defaults
        }

        test("custom layout components survive a JSON round trip") {
            val layout = SubtypeLayoutMap(symbols = extCoreLayout("custom-symbols"))
            val encoded = SubtypeJsonConfig.encodeToString(layout)
            SubtypeJsonConfig.decodeFromString<SubtypeLayoutMap>(encoded) shouldBe layout
        }
    })
