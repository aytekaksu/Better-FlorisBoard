/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.theme

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.ApplicationBootstrapState
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.ext.ExtensionEditFilesScreen
import dev.patrickgold.florisboard.app.ext.ThemeEditorAction
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaTestSource
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
import org.florisboard.lib.snygg.SnyggStylesheetEditor
import org.florisboard.lib.snygg.value.SnyggDpSizeValue
import org.florisboard.lib.snygg.value.SnyggPaddingValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

@RunWith(AndroidJUnit4::class)
class ThemeEditorScreenAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun committedRevisionsRefreshRulesAndPropertiesWithoutReopening(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<FlorisApplication>()
        val stylesheet = newEmptyThemeStylesheetEditor()
        val root = SnyggElementRule("root")
        val window = SnyggElementRule("window")
        val properties = SnyggSinglePropertySetEditor()
        val noRules = context.getString(R.string.settings__theme_editor__no_rules_defined)
        val rootLabel = context.getString(R.string.snygg__rule_element__root)
        val windowLabel = context.getString(R.string.snygg__rule_element__window)
        val borderWidthLabel = context.getString(R.string.snygg__property_name__border_width)
        withThemeEditor(stylesheet) { workspace, _ ->
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
        }
    }

    @Test
    fun resettingPaddingEncoderRefreshesTheVisibleDraftAndCommit(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<FlorisApplication>()
        val stylesheet = newEmptyThemeStylesheetEditor()
        val properties = SnyggSinglePropertySetEditor(
            mapOf(
                Snygg.Padding to SnyggPaddingValue(PaddingValues(3.dp, 5.dp, 7.dp, 9.dp)),
            ),
        )
        stylesheet.rules[SnyggElementRule("root")] = properties
        val paddingLabel = context.getString(R.string.snygg__property_name__padding)
        val encoderLabel = context.getString(R.string.snygg__property_value__padding)
        withThemeEditor(stylesheet) { _, _ ->
            composeRule.onNode(hasText(paddingLabel) and hasClickAction()).performClick()
            listOf("3 dp", "5 dp", "7 dp", "9 dp").forEach {
                composeRule.onNodeWithText(it).assertIsDisplayed()
            }
            composeRule.onNode(hasText(encoderLabel) and hasClickAction()).performClick()
            composeRule.onNode(hasText(encoderLabel) and hasAnyAncestor(isPopup())).performClick()

            composeRule.onAllNodesWithText("0 dp").assertCountEquals(4)
            repeat(4) { composeRule.onAllNodesWithText("0 dp")[it].assertIsDisplayed() }
            composeRule.onNodeWithText(context.getString(R.string.action__apply)).performClick()
            composeRule.runOnIdle {
                assertEquals(SnyggPaddingValue(PaddingValues(0.dp)), properties.properties[Snygg.Padding])
            }
        }
    }

    @Test
    fun selectedAssetsInstallOrRetireWithoutDeletingOtherFiles(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<FlorisApplication>()
        ClipboardExternalMediaTestSource.reset()
        val source = ClipboardExternalMediaTestSource.svgUri
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                val request = contract.createIntent(context, input)
                check(
                    request.action == Intent.ACTION_GET_CONTENT && request.type == "*/*" &&
                        request.hasCategory(Intent.CATEGORY_OPENABLE),
                ) { "EDITOR_PICKER_REQUEST" }
                check(dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(source))) {
                    "EDITOR_PICKER_DISPATCH"
                }
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val images = context.getString(R.string.ext__editor__files__type_images)
        val importFile = context.getString(R.string.action__import_file)
        val add = context.getString(R.string.action__add)
        val cancel = context.getString(R.string.action__cancel)
        val imageAdd = hasClickAction() and hasAnyAncestor(hasText(images))
        val dialogField = hasSetTextAction() and hasAnyAncestor(isDialog())
        fun clickDialog(label: String) {
            val matcher = hasText(label) and hasClickAction() and hasAnyAncestor(isDialog())
            composeRule.onAllNodes(matcher).assertCountEquals(1)
            composeRule.onNode(matcher).performClick()
        }
        val capturedStages = mutableListOf<Path>()
        var stageDirectory: Path? = null
        var stageSentinel: Path? = null
        var primaryFailure: Throwable? = null
        try {
            ClipboardExternalMediaTestSource.grantReadAccess(source)
            withThemeEditor(newEmptyThemeStylesheetEditor(), manageFiles = true, registryOwner = registryOwner) {
                    workspace,
                    hideScreen,
                ->
                val stages = context.cacheDir.toPath().resolve("extension-editor-${workspace.uuid}")
                stageDirectory = Files.createDirectories(stages)
                val sentinelBytes = byteArrayOf(2, 4)
                val sentinel = Files.write(stages.resolve("unrelated.marker"), sentinelBytes, CREATE_NEW)
                stageSentinel = sentinel
                val imagesDirectory = Files.createDirectories(workspace.extDir.toPath().resolve("images"))
                val keptAsset = Files.write(imagesDirectory.resolve("keep.svg"), sentinelBytes, CREATE_NEW)
                val destination = imagesDirectory.resolve("installed.svg")

                fun selectReadyStage(): Path {
                    composeRule.waitUntil(20_000L) {
                        composeRule.onAllNodes(imageAdd and isEnabled()).fetchSemanticsNodes().size == 1
                    }
                    composeRule.onAllNodes(imageAdd).assertCountEquals(1)
                    composeRule.onNode(imageAdd).performClick()
                    composeRule.waitUntil(20_000L) {
                        composeRule.onAllNodesWithText(importFile).fetchSemanticsNodes().size == 1
                    }
                    val generated = editorStages(stages, sentinel)
                    check(generated.size == 1) { "EDITOR_STAGE_COUNT" }
                    val staged = generated.single()
                    check(
                        Files.size(staged) == ClipboardExternalMediaTestSource.svgBytes.size.toLong() &&
                            Files.readAllBytes(staged).contentEquals(ClipboardExternalMediaTestSource.svgBytes),
                    ) {
                        "EDITOR_STAGE_PAYLOAD"
                    }
                    capturedStages.add(staged)
                    return staged
                }

                val installedStage = selectReadyStage()
                val previousVersion = composeRule.runOnIdle { workspace.version }
                composeRule.onNode(dialogField).performTextReplacement("../rejected.svg")
                clickDialog(add)
                composeRule.waitUntil(20_000L) {
                    composeRule.onAllNodes(imageAdd and isEnabled()).fetchSemanticsNodes().size == 1
                }
                check(
                    Files.isRegularFile(installedStage, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.exists(destination, LinkOption.NOFOLLOW_LINKS),
                ) { "EDITOR_RETRY_OWNERSHIP" }
                composeRule.runOnIdle { assertEquals(previousVersion, workspace.version) }
                composeRule.onNodeWithText(importFile).assertIsDisplayed()
                composeRule.onNode(dialogField).performTextReplacement("installed.svg")
                clickDialog(add)
                awaitEditorCondition {
                    !Files.exists(installedStage, LinkOption.NOFOLLOW_LINKS) &&
                        Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                }
                composeRule.waitUntil(20_000L) {
                    composeRule.onAllNodesWithText(importFile).fetchSemanticsNodes().isEmpty()
                }
                check(Files.readAllBytes(destination).contentEquals(ClipboardExternalMediaTestSource.svgBytes)) {
                    "EDITOR_INSTALL_PAYLOAD"
                }
                composeRule.runOnIdle { assertEquals(previousVersion + 1, workspace.version) }
                composeRule.onNodeWithText("installed.svg").assertIsDisplayed()

                listOf("cancel", "forgotten").forEach { row ->
                    val staged = selectReadyStage()
                    val version = composeRule.runOnIdle { workspace.version }
                    if (row == "cancel") {
                        clickDialog(cancel)
                    } else {
                        composeRule.runOnIdle { hideScreen() }
                    }
                    composeRule.waitForIdle()
                    awaitEditorCondition { !Files.exists(staged, LinkOption.NOFOLLOW_LINKS) }
                    composeRule.onNodeWithText(importFile).assertDoesNotExist()
                    composeRule.runOnIdle { assertEquals(version, workspace.version) }
                    check(
                        Files.readAllBytes(keptAsset).contentEquals(sentinelBytes) &&
                            Files.readAllBytes(sentinel).contentEquals(sentinelBytes) &&
                            Files.readAllBytes(destination).contentEquals(ClipboardExternalMediaTestSource.svgBytes),
                    ) {
                        "EDITOR_UNRELATED_ASSET_CHANGED"
                    }
                    check(editorStages(stages, sentinel).isEmpty()) { "EDITOR_STAGE_RETAINED" }
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupEditorProof(
                primaryFailure,
                { ClipboardExternalMediaTestSource.releaseBlockingOpen() },
                { ClipboardExternalMediaTestSource.revokeReadAccess(source) },
                { awaitEditorCondition { capturedStages.none { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } } },
                { stageSentinel?.let { Files.deleteIfExists(it) } },
                { stageDirectory?.let { Files.delete(it) } },
            )
        }
    }

    private fun editorStages(directory: Path, sentinel: Path): List<Path> =
        Files.newDirectoryStream(directory).use { children ->
            children.asSequence().filter {
                it != sentinel && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
            }.take(2).toList()
        }

    private fun awaitEditorCondition(condition: () -> Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "EDITOR_FILE_POLL_ON_MAIN" }
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "EDITOR_FILE_CONDITION_TIMEOUT" }
            Thread.sleep(10L)
        }
    }

    private suspend fun cleanupEditorProof(primary: Throwable?, vararg actions: suspend () -> Unit) {
        var failure = primary
        actions.forEach { action ->
            try {
                action()
            } catch (next: Throwable) {
                if (failure == null) failure = next else failure!!.addSuppressed(next)
            }
        }
        if (primary == null) failure?.let { throw it }
    }

    private suspend fun withThemeEditor(
        stylesheet: SnyggStylesheetEditor,
        manageFiles: Boolean = false,
        registryOwner: ActivityResultRegistryOwner? = null,
        block: suspend (CacheManager.ThemeEditorWorkspace, () -> Unit) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<FlorisApplication>()
        assertEquals(
            ApplicationBootstrapState.READY,
            withTimeout(20_000L) {
                context.applicationBootstrapState.first { it != ApplicationBootstrapState.LOADING }
            },
        )
        val prefs by FlorisPreferenceStore
        val previousLevel = prefs.theme.editorLevel.get()
        val previousKeyboardDisplay = prefs.theme.editorDisplayKbdAfterDialogs.get()
        val themeManager by context.themeManager()
        val previousPreview = themeManager.previewThemeInfo.value
        val component = ThemeExtensionComponentEditor("revision-test", "Revision test", listOf("Test")).also {
            it.stylesheetEditor = stylesheet
        }
        val action = ThemeEditorAction.EditTheme(component)
        val workspace = CacheManager(context).themeEditor.new().also {
            it.setEditor(
                ThemeExtensionEditor(
                    meta = ExtensionMeta(
                        "test.theme.revisions",
                        "1.0",
                        "Revision test",
                        maintainers = emptyList(),
                        license = "Apache-2.0",
                    ),
                    dependencies = mutableListOf(),
                    themes = mutableStateListOf(component),
                ),
            )
            it.currentAction = if (manageFiles) ThemeEditorAction.ManageFiles else action
        }
        var showScreen by mutableStateOf(true)
        var primaryFailure: Throwable? = null
        try {
            prefs.theme.editorLevel.set(SnyggLevel.BASIC)
            prefs.theme.editorDisplayKbdAfterDialogs.set(DisplayKbdAfterDialogs.NEVER)
            composeRule.setContent {
                ProvideLocalizedResources(context, R.string.app_name) {
                    MaterialTheme {
                        if (showScreen) {
                            if (manageFiles) {
                                CompositionLocalProvider(
                                    LocalActivityResultRegistryOwner provides checkNotNull(registryOwner),
                                ) {
                                    ExtensionEditFilesScreen(workspace)
                                }
                            } else {
                                ThemeEditorScreen(workspace, action)
                            }
                        }
                    }
                }
            }
            block(workspace) { showScreen = false }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                composeRule.runOnIdle { showScreen = false }
                composeRule.waitForIdle()
                themeManager.updateActiveTheme { themeManager.previewThemeInfo.value = previousPreview }
                composeRule.waitForIdle()
                withTimeout(20_000L) { workspace.previewMaterialization.retireAndAwaitRelease() }
                workspace.close()
            } catch (failure: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = failure
                    throw failure
                }
                primaryFailure.addSuppressed(failure)
            } finally {
                cleanupEditorProof(
                    primaryFailure,
                    { prefs.theme.editorLevel.set(previousLevel) },
                    { prefs.theme.editorDisplayKbdAfterDialogs.set(previousKeyboardDisplay) },
                )
            }
        }
    }
}
