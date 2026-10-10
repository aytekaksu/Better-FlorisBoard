/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.patrickgold.florisboard.app.ext

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import dev.patrickgold.florisboard.ApplicationBootstrapState
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.cacheManager
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponentImpl
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionDefaults
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.lib.ext.ExtensionMaintainer
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import dev.patrickgold.florisboard.lib.io.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.florisboard.lib.color.MaterialYouFlags
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExtensionEditScreenAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun copyingLocalThemePreservesMaterialYouSettingsWhenSaved(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<FlorisApplication>()
        assertEquals(
            ApplicationBootstrapState.READY,
            withTimeout(20_000L) {
                context.applicationBootstrapState.first { it != ApplicationBootstrapState.LOADING }
            },
        )
        val manager = context.extensionManager().value
        val cache = context.cacheManager().value
        val id = "local.themes.copy_${UUID.randomUUID().toString().replace("-", "")}"
        val flags = MaterialYouFlags(PaletteStyle.Vibrant, Contrast.High, ColorSpec.SpecVersion.SPEC_2025)
        val source = ThemeExtensionComponentImpl(
            id = "source",
            label = "Copy fixture",
            authors = listOf("Fixture author"),
            isNightTheme = false,
            materialYouFlags = flags,
            stylesheetPath = "custom/source.json",
        )
        val extension = ThemeExtension(
            meta = ExtensionMeta(
                id = id,
                version = "1.0.0",
                title = "Copy fixture",
                maintainers = listOf(ExtensionMaintainer("Test")),
                license = "apache-2.0",
            ),
            themes = listOf(source, source.copy(id = "source_1", label = "Collision fixture", stylesheetPath = null)),
        )
        val stylesheet = """{"keyboard":{"background":"#112233"}}"""
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "theme-copy-").toFile()
        val previousWorkspaces = cache.themeEditor.dir.list()?.toSet().orEmpty()
        var workspace: CacheManager.ThemeEditorWorkspace? = null
        lateinit var navigation: NavHostController
        var showUi by mutableStateOf(true)
        var primaryFailure: Throwable? = null

        try {
            File(staging, ExtensionDefaults.MANIFEST_FILE_NAME).writeText(
                ExtensionJsonConfig.encodeToString<Extension>(extension),
            )
            for (component in extension.themes) {
                File(staging, component.stylesheetPath()).apply {
                    assertTrue(parentFile!!.mkdirs() || parentFile!!.isDirectory)
                    writeText(stylesheet)
                }
            }
            manager.installNew(extension, staging)
            manager.themes.init()
            composeRule.setContent {
                if (showUi) {
                    val navController = rememberNavController()
                    navigation = navController
                    ProvideLocalizedResources(context, R.string.app_name) {
                        MaterialTheme {
                            CompositionLocalProvider(LocalNavController provides navController) {
                                Routes.AppNavHost(Modifier, navController, Routes.Ext.Home::class)
                                LaunchedEffect(id) { navController.navigate(Routes.Ext.Edit(id)) }
                            }
                        }
                    }
                }
            }
            composeRule.waitUntil(20_000L) {
                val newNames = cache.themeEditor.dir.list().orEmpty().filter { it !in previousWorkspaces }
                composeRule.runOnIdle {
                    workspace = newNames.mapNotNull { cache.themeEditor.getWorkspaceByUuid(it) }
                        .filter { it.hasEditor && it.editor.meta.id == id }
                        .singleOrNull()
                    workspace != null
                }
            }
            val opened = checkNotNull(workspace)
            val themesTitle = context.getString(R.string.ext__meta__components_theme)
            val addTheme = hasClickAction() and hasAnyAncestor(hasText(themesTitle))
            composeRule.onAllNodes(addTheme).assertCountEquals(1)
            composeRule.onNode(addTheme).assertIsEnabled().performScrollTo().performClick()
            val sourceChoice = hasText("$id:source") and hasClickAction() and isEnabled()
            composeRule.onAllNodes(sourceChoice).assertCountEquals(1)
            composeRule.onNode(sourceChoice).performScrollTo().performClick()
            composeRule.onNodeWithText(context.getString(R.string.action__create)).performClick()
            composeRule.waitUntil(20_000L) {
                composeRule.runOnIdle { opened.editor.themes.size == 3 && opened.currentAction == null }
            }
            composeRule.onNodeWithText("$id:source_2").performScrollTo().assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(source, opened.editor.themes.single { it.id == "source" }.build())
                val copied = opened.editor.themes.single { it.id == "source_2" }.build()
                assertEquals("A local copy must preserve Material You settings", flags, copied.materialYouFlags)
                assertEquals(source.copy(id = "source_2", stylesheetPath = null), copied)
            }
            assertEquals(stylesheet, File(opened.extDir, "custom/source.json").readText())
            assertEquals(stylesheet, File(opened.extDir, "stylesheets/source_2.json").readText())

            composeRule.runOnIdle {
                assertTrue(
                    "The real editor route needs a previous destination",
                    navigation.previousBackStackEntry != null,
                )
            }
            composeRule.onNodeWithText(context.getString(R.string.action__save)).performClick()
            composeRule.waitUntil(20_000L) { opened.archiveSaved }
            try {
                composeRule.waitUntil(20_000L) { opened.isClosed() }
            } catch (failure: Throwable) {
                val routeState = composeRule.runOnIdle {
                    "current=${navigation.currentBackStackEntry?.lifecycle?.currentState}, " +
                        "hasPrevious=${navigation.previousBackStackEntry != null}"
                }
                throw AssertionError("Saved editor workspace did not close: $routeState", failure)
            }
            val savedRef = checkNotNull(extension.sourceRef)
            val saved = ExtensionJsonConfig.decodeFromString(
                ThemeExtension.serializer(),
                ZipUtils.readFileFromArchive(context, savedRef, ExtensionDefaults.MANIFEST_FILE_NAME).getOrThrow(),
            )
            assertEquals(3, saved.themes.size)
            assertEquals(source, saved.themes.single { it.id == "source" })
            assertEquals(
                source.copy(id = "source_2", stylesheetPath = null),
                saved.themes.single {
                    it.id == "source_2"
                },
            )
            for (path in listOf("custom/source.json", "stylesheets/source_2.json")) {
                assertEquals(
                    stylesheet,
                    ZipUtils.readFileFromArchive(
                        context,
                        savedRef,
                        path,
                    ).getOrThrow(),
                )
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            suspend fun cleanup(block: suspend () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    if (primaryFailure == null) primaryFailure = failure else primaryFailure!!.addSuppressed(failure)
                }
            }
            cleanup {
                composeRule.runOnIdle { showUi = false }
                composeRule.waitForIdle()
            }
            cleanup {
                workspace?.let {
                    withTimeout(20_000L) { it.previewMaterialization.retireAndAwaitRelease() }
                    withContext(Dispatchers.IO) { it.close() }
                }
            }
            cleanup {
                manager.themes.init()
                manager.getExtensionById(id)?.let { manager.delete(it) }
                manager.themes.init()
            }
            cleanup { assertTrue(staging.deleteRecursively()) }
            primaryFailure?.let { throw it }
        }
    }
}
