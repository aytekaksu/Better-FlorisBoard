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

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.lib.snygg.SnyggElementRule
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.florisboard.lib.snygg.value.SnyggValue

class ThemePropertyEditTest : FunSpec({
    val rule = SnyggElementRule("smartbar")
    val oldColor = SnyggStaticColorValue(Color.Red)
    val newColor = SnyggStaticColorValue(Color.Blue)

    test("adding an existing property does not overwrite it or invoke the workspace update") {
        val properties = mutableMapOf<String, SnyggValue>("background" to oldColor)
        val adding = SnyggEmptyPropertyInfoForAdding.copy(rule = rule)
        var updateCount = 0

        confirmThemePropertyEdit(adding, properties, "background") {
            updateCount++
            properties["background"] = newColor
        } shouldBe false

        properties["background"] shouldBe oldColor
        updateCount shouldBe 0
    }

    test("adding a new property applies it once") {
        val properties = mutableMapOf<String, SnyggValue>()
        val adding = SnyggEmptyPropertyInfoForAdding.copy(rule = rule)
        var updateCount = 0

        confirmThemePropertyEdit(adding, properties, "background") {
            updateCount++
            properties["background"] = newColor
        } shouldBe true

        properties["background"] shouldBe newColor
        updateCount shouldBe 1
    }

    test("editing an existing property still replaces its value") {
        val properties = mutableMapOf<String, SnyggValue>("background" to oldColor)
        val editing = PropertyInfo(rule, "background", oldColor)
        var updateCount = 0

        confirmThemePropertyEdit(editing, properties, "background") {
            updateCount++
            properties["background"] = newColor
        } shouldBe true

        properties["background"] shouldBe newColor
        updateCount shouldBe 1
    }
})
