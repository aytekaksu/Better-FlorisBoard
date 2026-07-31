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

package dev.patrickgold.florisboard.lib.cache

import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.app.ext.EditorAction
import dev.patrickgold.florisboard.app.ext.addThemeComponent
import dev.patrickgold.florisboard.app.ext.newEmptyThemeComponentEditor
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaTestSource
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.ime.theme.ThemeExtensionComponent
import dev.patrickgold.florisboard.lib.ext.ExtensionMaintainer
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import dev.patrickgold.florisboard.lib.io.FileRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheManagerAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantTestSource() {
        ClipboardExternalMediaTestSource.reset()
        ClipboardExternalMediaTestSource.grantReadAccess(
            ClipboardExternalMediaTestSource.healthyUri,
            ClipboardExternalMediaTestSource.blockingUri,
        )
    }

    @After
    fun revokeTestSource() {
        ClipboardExternalMediaTestSource.releaseBlockingOpen()
        ClipboardExternalMediaTestSource.revokeReadAccess()
    }

    @Test
    fun importedProviderNameIsDisplayOnlyAndCopiedSizeIsMeasured() = runBlocking {
        val cacheManager = CacheManager(context)
        val workspace = cacheManager.readFromUriIntoCache(
            ClipboardExternalMediaTestSource.healthyUri,
        )

        try {
            val fileInfo = workspace.inputFileInfos.single()
            assertSame(workspace, cacheManager.importer.getWorkspaceByUuid(workspace.uuid))
            assertEquals("_vector.svg", fileInfo.displayLabel)
            assertEquals(4L, fileInfo.size)
            assertNull(fileInfo.ext)
            assertFalse(fileInfo.file.exists())
            assertEquals(FileRegistry.FlexExtension.mediaType, fileInfo.mediaType)
            assertTrue(fileInfo.file.name.endsWith(".flex"))
            assertNotEquals(fileInfo.displayLabel, fileInfo.file.name)
            assertEquals(
                workspace.inputDir.canonicalPath,
                fileInfo.file.parentFile?.canonicalPath,
            )
            val caller = ClipboardExternalMediaTestSource.callerProcesses()
            assertTrue(caller.healthyPid > 0)
            assertEquals(context.applicationInfo.uid, caller.healthyUid)
            assertNotEquals(Process.myPid(), caller.healthyPid)
        } finally {
            workspace.close()
            workspace.close()
        }

        assertTrue(workspace.isClosed())
        assertNull(cacheManager.importer.getWorkspaceByUuid(workspace.uuid))
    }

    @Test
    fun partialImportFailureLeavesNoRegisteredOrStoredWorkspace() = runBlocking {
        val cacheManager = CacheManager(context)
        val before = cacheManager.importer.dir.list()?.toSet().orEmpty()
        val unavailableUri = Uri.parse("content://invalid.extension.test/source")

        val failure = runCatching {
            cacheManager.readFromUriIntoCache(
                listOf(
                    ClipboardExternalMediaTestSource.healthyUri,
                    unavailableUri,
                ),
            )
        }.exceptionOrNull() as ExtensionImportException

        assertEquals("Unable to import selected extension data.", failure.message)
        assertNull(failure.cause)
        assertEquals(before, cacheManager.importer.dir.list()?.toSet().orEmpty())
    }

    @Test
    fun cancelledImportReturnsBeforeHostileProviderAndDeletesItsWorkspace() = runBlocking {
        val cacheManager = CacheManager(context)
        val before = cacheManager.importer.dir.list()?.toSet().orEmpty()
        val import = async(Dispatchers.Default) {
            cacheManager.readFromUriIntoCache(ClipboardExternalMediaTestSource.blockingUri)
        }

        try {
            assertTrue(ClipboardExternalMediaTestSource.awaitBlockingOpen(5_000))
            val cancellationStartedAt = SystemClock.elapsedRealtime()
            withTimeout(5_000) {
                import.cancelAndJoin()
            }
            assertTrue(SystemClock.elapsedRealtime() - cancellationStartedAt < 5_000)

            assertEquals(before, cacheManager.importer.dir.list()?.toSet().orEmpty())
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
        }
    }

    @Test
    fun exactUriGrantIsRequiredByTheExtensionImportBoundary() = runBlocking {
        val cacheManager = CacheManager(context)
        val before = cacheManager.importer.dir.list()?.toSet().orEmpty()
        ClipboardExternalMediaTestSource.revokeReadAccess(
            ClipboardExternalMediaTestSource.healthyUri,
        )
        ClipboardExternalMediaTestSource.grantReadAccess(
            ClipboardExternalMediaTestSource.svgUri,
        )

        val rejected = runCatching {
            cacheManager.readFromUriIntoCache(ClipboardExternalMediaTestSource.healthyUri)
        }.exceptionOrNull()
        assertTrue(rejected is ExtensionImportException)
        assertEquals(0, ClipboardExternalMediaTestSource.openCount())
        assertEquals(before, cacheManager.importer.dir.list()?.toSet().orEmpty())

        ClipboardExternalMediaTestSource.grantReadAccess(
            ClipboardExternalMediaTestSource.healthyUri,
        )
        val workspace = cacheManager.readFromUriIntoCache(
            ClipboardExternalMediaTestSource.healthyUri,
        )
        try {
            assertEquals(1, ClipboardExternalMediaTestSource.openCount())
            assertEquals(4L, workspace.inputFileInfos.single().size)
        } finally {
            workspace.close()
        }
    }

    @Test
    fun emptyAndCorruptSelectionsRemainVisibleWithoutRetainingTheirPayloads() = runBlocking {
        val cacheManager = CacheManager(context)
        ClipboardExternalMediaTestSource.grantReadAccess(
            ClipboardExternalMediaTestSource.emptyUri,
        )

        val workspace = cacheManager.readFromUriIntoCache(
            listOf(
                ClipboardExternalMediaTestSource.emptyUri,
                ClipboardExternalMediaTestSource.healthyUri,
            ),
        )
        try {
            assertEquals(listOf(0L, 4L), workspace.inputFileInfos.map { it.size })
            assertTrue(workspace.inputFileInfos.all { it.ext == null })
            assertTrue(workspace.inputFileInfos.none { it.file.exists() })
            assertEquals(2, ClipboardExternalMediaTestSource.openCount())
        } finally {
            workspace.close()
        }
    }

    @Test
    fun exporterAndEditorCloseAreIdempotentAndUnregisterTheirWorkspaces() {
        val cacheManager = CacheManager(context)
        val exporter = cacheManager.exporter.new()
        val editor = cacheManager.themeExtEditor.new()

        assertSame(exporter, cacheManager.exporter.getWorkspaceByUuid(exporter.uuid))
        assertSame(editor, cacheManager.themeExtEditor.getWorkspaceByUuid(editor.uuid))

        exporter.close()
        exporter.close()
        editor.close()
        editor.close()

        assertTrue(exporter.isClosed())
        assertTrue(editor.isClosed())
        assertNull(cacheManager.exporter.getWorkspaceByUuid(exporter.uuid))
        assertNull(cacheManager.themeExtEditor.getWorkspaceByUuid(editor.uuid))
    }

    @Test
    fun addingAThemeComponentMarksTheEditorWorkspaceModified() {
        val cacheManager = CacheManager(context)
        val workspace = cacheManager.themeExtEditor.new()
        val extension = ThemeExtension(
            meta = ExtensionMeta(
                id = "local.themes.editor_test",
                version = "1.0.0",
                title = "Editor test",
                maintainers = listOf(ExtensionMaintainer("test")),
                license = "apache-2.0",
            ),
            themes = emptyList(),
        )
        workspace.editor = extension.edit()
        workspace.currentAction = EditorAction.CreateComponent(
            ThemeExtensionComponent::class,
        )

        try {
            assertFalse(workspace.isModified)
            addThemeComponent(
                workspace,
                newEmptyThemeComponentEditor("new_theme", "New theme", listOf("test")),
            )

            assertTrue(workspace.isModified)
            assertEquals(1, workspace.version)
            assertEquals(1, workspace.editor?.themes?.size)
            assertNull(workspace.currentAction)
        } finally {
            workspace.close()
        }
    }
}
