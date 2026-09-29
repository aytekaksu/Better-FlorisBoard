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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.ApplicationBootstrapState
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.ext.ThemeEditorAction
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponentEditor
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionEditor
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import dev.patrickgold.florisboard.themeManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.florisboard.lib.snygg.Snygg
import org.florisboard.lib.snygg.SnyggElementRule
import org.florisboard.lib.snygg.SnyggSinglePropertySetEditor
import org.florisboard.lib.snygg.value.SnyggDpSizeValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeEditorScreenAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun committedRevisionsRefreshRulesAndPropertiesWithoutReopening(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<FlorisApplication>()
        assertEquals(ApplicationBootstrapState.READY, withTimeout(20_000L) {
            context.applicationBootstrapState.first { it != ApplicationBootstrapState.LOADING }
        })
        val prefs by FlorisPreferenceStore
        val previousLevel = prefs.theme.editorLevel.get()
        val previousKeyboardDisplay = prefs.theme.editorDisplayKbdAfterDialogs.get()
        val themeManager by context.themeManager()
        val previousPreview = themeManager.previewThemeInfo.value
        val stylesheet = newEmptyThemeStylesheetEditor()
        val component = ThemeExtensionComponentEditor("revision-test", "Revision test", listOf("Test")).also {
            it.stylesheetEditor = stylesheet
        }
        val action = ThemeEditorAction.EditTheme(component)
        val workspace = CacheManager(context).themeEditor.new().also {
            it.setEditor(
                ThemeExtensionEditor(
                    meta = ExtensionMeta(
                        "test.theme.revisions", "1.0", "Revision test",
                        maintainers = emptyList(), license = "Apache-2.0",
                    ),
                    dependencies = mutableListOf(),
                    themes = mutableStateListOf(component),
                ),
            )
            it.currentAction = action
        }
        var showScreen by mutableStateOf(true)
        val root = SnyggElementRule("root")
        val window = SnyggElementRule("window")
        val properties = SnyggSinglePropertySetEditor()
        val noRules = context.getString(R.string.settings__theme_editor__no_rules_defined)
        val rootLabel = context.getString(R.string.snygg__rule_element__root)
        val windowLabel = context.getString(R.string.snygg__rule_element__window)
        val borderWidthLabel = context.getString(R.string.snygg__property_name__border_width)
        try {
            prefs.theme.editorLevel.set(SnyggLevel.BASIC)
            prefs.theme.editorDisplayKbdAfterDialogs.set(DisplayKbdAfterDialogs.NEVER)
            composeRule.setContent {
                ProvideLocalizedResources(context, R.string.app_name) {
                    MaterialTheme {
                        if (showScreen) ThemeEditorScreen(workspace, action)
                    }
                }
            }
            val list = composeRule.onNode(hasScrollToIndexAction())
            composeRule.onNodeWithText(noRules).assertIsDisplayed()

            composeRule.runOnIdle { workspace.update { stylesheet.rules[root] = properties } }
            list.performScrollToIndex(0)
            composeRule.onNodeWithText(noRules).assertDoesNotExist()
            list.performScrollToNode(hasText(rootLabel))
            composeRule.onNodeWithText(rootLabel).assertIsDisplayed()

            composeRule.runOnIdle {
                workspace.update { properties.properties[Snygg.BorderWidth] = SnyggDpSizeValue(7.dp) }
            }
            list.performScrollToNode(hasText(borderWidthLabel))
            composeRule.onNodeWithText(borderWidthLabel).assertIsDisplayed()
            composeRule.onNodeWithText("7dp", substring = true).assertIsDisplayed()

            composeRule.runOnIdle {
                workspace.update { stylesheet.rules[window] = checkNotNull(stylesheet.rules.remove(root)) }
            }
            list.performScrollToNode(hasText(windowLabel))
            composeRule.onNodeWithText(rootLabel).assertDoesNotExist()
            composeRule.onNodeWithText(windowLabel).assertIsDisplayed()
            composeRule.onNodeWithText("7dp", substring = true).assertIsDisplayed()

            composeRule.runOnIdle { workspace.update { stylesheet.rules.remove(window) } }
            list.performScrollToIndex(0)
            composeRule.onNodeWithText(noRules).assertIsDisplayed()
            composeRule.onNodeWithText(windowLabel).assertDoesNotExist()
        } finally {
            try {
                composeRule.runOnIdle { showScreen = false }
                composeRule.waitForIdle()
                themeManager.updateActiveTheme { themeManager.previewThemeInfo.value = previousPreview }
                withTimeout(20_000L) { workspace.previewMaterialization.retireAndAwaitRelease() }
                workspace.close()
            } finally {
                prefs.theme.editorLevel.set(previousLevel)
                prefs.theme.editorDisplayKbdAfterDialogs.set(previousKeyboardDisplay)
            }
        }
    }
}
