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
import dev.patrickgold.florisboard.app.ext.ThemeEditorAction
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponentEditor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.lib.snygg.SnyggAnnotationRule
import org.florisboard.lib.snygg.SnyggElementRule
import org.florisboard.lib.snygg.SnyggMultiplePropertySetsEditor
import org.florisboard.lib.snygg.SnyggSinglePropertySetEditor
import org.florisboard.lib.snygg.SnyggStylesheet
import org.florisboard.lib.snygg.SnyggStylesheetEditor
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.florisboard.lib.snygg.value.SnyggUndefinedValue
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

    test("property draft remains on the theme action") {
        val set = SnyggSinglePropertySetEditor(mapOf("background" to oldColor))
        val stylesheet = SnyggStylesheetEditor(SnyggStylesheet.SCHEMA_V2).also {
            it.rules[rule] = set
        }
        val action = ThemeEditorAction.EditTheme(ThemeExtensionComponentEditor())
        action.propertyEditSession = ThemePropertyEditSession(PropertyInfo(rule, "background", oldColor), set)
        action.propertyEditSession!!.value = newColor

        val reopened = action.propertyEditSession
        reopened?.value shouldBe newColor
        reopened?.isCurrentIn(stylesheet) shouldBe true
    }

    test("adding a property keeps its undefined default and resets a previously chosen encoder") {
        val set = SnyggSinglePropertySetEditor()
        val draft = ThemePropertyEditSession(SnyggEmptyPropertyInfoForAdding.copy(rule = rule), set)

        draft.name shouldBe ""
        draft.value shouldBe SnyggUndefinedValue
        draft.changeName("background")
        draft.value shouldBe SnyggUndefinedValue
        draft.selectEncoder(SnyggStaticColorValue)
        draft.value.encoder() shouldBe SnyggStaticColorValue
        draft.changeName("foreground")
        draft.value shouldBe SnyggUndefinedValue
    }

    test("a draft cannot edit a replaced or removed property set") {
        val fontRule = SnyggAnnotationRule.Font("Example")
        val first = SnyggSinglePropertySetEditor(mapOf("src" to oldColor))
        val second = SnyggSinglePropertySetEditor(mapOf("src" to newColor))
        val sets = SnyggMultiplePropertySetsEditor().also { it.sets.addAll(listOf(first, second)) }
        val stylesheet = SnyggStylesheetEditor(SnyggStylesheet.SCHEMA_V2).also {
            it.rules[fontRule] = sets
        }
        val draft = ThemePropertyEditSession(PropertyInfo(fontRule, "src", newColor), second)

        draft.isCurrentIn(stylesheet) shouldBe true
        sets.sets.reverse()
        draft.isCurrentIn(stylesheet) shouldBe true
        sets.sets.remove(second)
        draft.isCurrentIn(stylesheet) shouldBe false
        sets.sets.add(SnyggSinglePropertySetEditor(mapOf("src" to newColor)))
        draft.isCurrentIn(stylesheet) shouldBe false
        sets.sets.add(second)
        second.properties.remove("src")
        draft.isCurrentIn(stylesheet) shouldBe false
    }
})
