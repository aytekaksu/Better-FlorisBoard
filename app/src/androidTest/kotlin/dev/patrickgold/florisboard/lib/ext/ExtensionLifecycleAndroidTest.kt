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

package dev.patrickgold.florisboard.lib.ext

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.lib.io.FlorisRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExtensionLifecycleAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unloadDoesNotDeleteAnUnownedWorkingDirectory() {
        val externalDir = File(context.cacheDir, "extension-editor-${UUID.randomUUID()}")
        val sentinel = File(externalDir, "sentinel")
        assertTrue(externalDir.mkdirs())
        assertTrue(sentinel.createNewFile())
        val extension = testExtension()
        extension.workingDir = externalDir

        try {
            extension.unload(context)

            assertNull(extension.workingDir)
            assertTrue(externalDir.isDirectory)
            assertTrue(sentinel.isFile)
        } finally {
            externalDir.deleteRecursively()
        }
    }

    @Test
    fun unloadDeletesOnlyItsRandomRuntimeDirectory() {
        val extensionId = "test.runtime.${UUID.randomUUID().toString().replace("-", "")}"
        val collidingDir = File(context.cacheDir, extensionId)
        val sentinel = File(collidingDir, "sentinel")
        assertTrue(collidingDir.mkdirs())
        assertTrue(sentinel.createNewFile())
        val extension = testExtension(extensionId).apply {
            sourceRef = FlorisRef.assets("ime/theme/org.florisboard.themes")
        }

        try {
            extension.load(context).getOrThrow()
            val runtimeDir = extension.workingDir
            assertNotNull(runtimeDir)
            requireNotNull(runtimeDir)
            assertEqualsRuntimeRoot(runtimeDir)
            assertNotEquals(extensionId, runtimeDir.name)
            assertTrue(File(runtimeDir, "extension.json").isFile)
            assertTrue(sentinel.isFile)

            extension.unload(context)

            assertNull(extension.workingDir)
            assertFalse(runtimeDir.exists())
            assertTrue(sentinel.isFile)
        } finally {
            extension.unload(context)
            collidingDir.deleteRecursively()
        }
    }

    @Test
    fun sameIdInstancesDoNotShareOrDeleteEachOthersRuntime() {
        val extensionId = "test.runtime.${UUID.randomUUID().toString().replace("-", "")}"
        val first = testExtension(extensionId).withBundledSource()
        val second = testExtension(extensionId).withBundledSource()

        try {
            first.load(context).getOrThrow()
            second.load(context).getOrThrow()
            val firstDir = requireNotNull(first.workingDir)
            val secondDir = requireNotNull(second.workingDir)

            assertNotEquals(firstDir.canonicalPath, secondDir.canonicalPath)
            first.unload(context)
            assertFalse(firstDir.exists())
            assertTrue(secondDir.isDirectory)
            assertTrue(File(secondDir, "extension.json").isFile)
        } finally {
            first.unload(context)
            second.unload(context)
        }
    }

    @Test
    fun newInstallNeverReplacesAnExistingArchiveOrBundledId() = runBlocking {
        val extensionId = "local.themes.test_${UUID.randomUUID().toString().replace("-", "")}"
        val extension = testExtension(extensionId)
        val manager = ExtensionManager(context)
        val staging = File(context.cacheDir, "extension-install-${UUID.randomUUID()}")
        val archiveName = ExtensionDefaults.createFlexName(extensionId)
        val themeArchive = File(context.filesDir, "${ExtensionManager.IME_THEME_PATH}/$archiveName")
        val keyboardArchive = File(
            context.filesDir,
            "${ExtensionManager.IME_KEYBOARD_PATH}/$archiveName",
        )
        val sentinel = "existing extension".encodeToByteArray()

        try {
            assertTrue(staging.mkdirs())
            assertTrue(File(staging, ExtensionDefaults.MANIFEST_FILE_NAME).createNewFile())

            assertTrue(themeArchive.parentFile?.mkdirs() == true || themeArchive.parentFile?.isDirectory == true)
            themeArchive.writeBytes(sentinel)
            assertNotNull(runCatching { manager.installNew(extension, staging) }.exceptionOrNull())
            assertArrayEquals(sentinel, themeArchive.readBytes())
            assertTrue(themeArchive.delete())

            assertTrue(
                keyboardArchive.parentFile?.mkdirs() == true ||
                    keyboardArchive.parentFile?.isDirectory == true,
            )
            keyboardArchive.writeBytes(sentinel)
            assertNotNull(runCatching { manager.installNew(extension, staging) }.exceptionOrNull())
            assertArrayEquals(sentinel, keyboardArchive.readBytes())
            assertTrue(keyboardArchive.delete())

            manager.installNew(extension, staging)
            assertTrue(themeArchive.isFile)
            val installedBytes = themeArchive.readBytes()
            assertNotNull(runCatching { manager.installNew(extension, staging) }.exceptionOrNull())
            assertArrayEquals(installedBytes, themeArchive.readBytes())

            assertNotNull(
                runCatching {
                    manager.installNew(testExtension("org.florisboard.themes"), staging)
                }.exceptionOrNull(),
            )
        } finally {
            themeArchive.delete()
            keyboardArchive.delete()
            staging.deleteRecursively()
        }
    }

    @Test
    fun editorReplacementRejectsAnArchiveChangedSinceOpen() = runBlocking {
        val extensionId = "local.themes.edit_${UUID.randomUUID().toString().replace("-", "")}"
        val extension = testExtension(extensionId)
        val manager = ExtensionManager(context)
        val staging = File(context.cacheDir, "extension-edit-stage-${UUID.randomUUID()}")
        val materialized = File(context.cacheDir, "extension-edit-open-${UUID.randomUUID()}")
        val archiveName = ExtensionDefaults.createFlexName(extensionId)
        val archive = File(context.filesDir, "${ExtensionManager.IME_THEME_PATH}/$archiveName")
        val changedBytes = "newer installed extension".encodeToByteArray()

        try {
            assertTrue(staging.mkdirs())
            assertTrue(File(staging, ExtensionDefaults.MANIFEST_FILE_NAME).createNewFile())
            manager.installNew(extension, staging)
            extension.sourceRef = FlorisRef.internal(ExtensionManager.IME_THEME_PATH)
                .subRef(archiveName)

            val fingerprint = manager.materializeForEditor(extension, materialized)
            assertNotNull(fingerprint)
            archive.writeBytes(changedBytes)

            assertNotNull(
                runCatching {
                    manager.replace(extension, staging, requireNotNull(fingerprint))
                }.exceptionOrNull(),
            )
            assertArrayEquals(changedBytes, archive.readBytes())
        } finally {
            archive.delete()
            staging.deleteRecursively()
            materialized.deleteRecursively()
        }
    }

    @Test
    fun staleExtensionCannotOpenOrDeleteAChangedArchive() = runBlocking {
        val extensionId = "local.themes.stale_${UUID.randomUUID().toString().replace("-", "")}"
        val extension = testExtension(extensionId)
        val manager = ExtensionManager(context)
        val staging = File(context.cacheDir, "extension-stale-stage-${UUID.randomUUID()}")
        val materialized = File(context.cacheDir, "extension-stale-open-${UUID.randomUUID()}")
        val archiveName = ExtensionDefaults.createFlexName(extensionId)
        val archive = File(context.filesDir, "${ExtensionManager.IME_THEME_PATH}/$archiveName")
        val changedBytes = "newer installed extension".encodeToByteArray()

        try {
            assertTrue(staging.mkdirs())
            assertTrue(File(staging, ExtensionDefaults.MANIFEST_FILE_NAME).createNewFile())
            manager.installNew(extension, staging)
            archive.writeBytes(changedBytes)

            assertNotNull(
                runCatching {
                    manager.materializeForEditor(extension, materialized)
                }.exceptionOrNull(),
            )
            assertNotNull(runCatching { manager.delete(extension) }.exceptionOrNull())
            assertArrayEquals(changedBytes, archive.readBytes())
        } finally {
            archive.delete()
            staging.deleteRecursively()
            materialized.deleteRecursively()
        }
    }

    private fun ThemeExtension.withBundledSource() = apply {
        sourceRef = FlorisRef.assets("ime/theme/org.florisboard.themes")
    }

    private fun assertEqualsRuntimeRoot(runtimeDir: File) {
        assertTrue(runtimeDir.parentFile?.name == "extension-runtime")
        assertTrue(runtimeDir.parentFile?.parentFile?.canonicalFile == context.cacheDir.canonicalFile)
    }

    private fun testExtension(id: String = "test.runtime.unowned") = ThemeExtension(
        meta = ExtensionMeta(
            id = id,
            version = "1.0.0",
            title = "Lifecycle test",
            maintainers = listOf(ExtensionMaintainer("test")),
            license = "apache-2.0",
        ),
        themes = emptyList(),
    )
}
