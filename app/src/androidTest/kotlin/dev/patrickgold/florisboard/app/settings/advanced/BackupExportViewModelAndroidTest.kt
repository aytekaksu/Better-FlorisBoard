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

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.lib.cache.CacheManager
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupExportViewModelAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun preparedDocumentRemainsAvailableAcrossRouteOwnerLookupAndCancelCleansIt() = runBlocking {
        val manager = CacheManager(context)
        val existing = workspaceDirectories(manager)
        val owner = TestOwner()
        try {
            val model = onMain { ViewModelProvider(owner)[BackupExportViewModel::class.java] }
            onMain {
                selectOnlyKeyboardExtensions(model)
                model.submit(context, manager)
                model.submit(context, manager)
            }
            awaitPhase(model, BackupExportPhase.DOCUMENT_PICKER_PENDING)
            val source = onlyNewWorkspace(manager, existing)
            val fileName = onMain { model.claimDocumentPicker() }
            assertNotNull(fileName)
            assertTrue(File(source, "output/$fileName").isFile)

            // Exercise the route owner lookup used by a recreated screen, not the picker itself.
            val reattached = onMain { ViewModelProvider(owner)[BackupExportViewModel::class.java] }
            assertEquals(BackupExportPhase.AWAITING_DOCUMENT, reattached.phase)
            onMain { reattached.onDocumentResult(context, null) }
            awaitPhase(reattached, BackupExportPhase.IDLE)
            awaitRemoval(source)

            // A late duplicate callback cannot consume or recreate a retired source.
            onMain { reattached.onDocumentResult(context, Uri.parse("content://invalid/late")) }
            assertEquals(BackupExportPhase.IDLE, reattached.phase)
            assertEquals(existing, workspaceDirectories(manager))
        } finally {
            onMain { owner.viewModelStore.clear() }
        }
    }

    @Test
    fun failedDocumentWriteRetiresSourceAndAllowsAnotherSubmission() = runBlocking {
        val manager = CacheManager(context)
        val existing = workspaceDirectories(manager)
        val owner = TestOwner()
        try {
            val model = onMain { ViewModelProvider(owner)[BackupExportViewModel::class.java] }
            onMain {
                selectOnlyKeyboardExtensions(model)
                model.submit(context, manager)
            }
            awaitPhase(model, BackupExportPhase.DOCUMENT_PICKER_PENDING)
            val source = onlyNewWorkspace(manager, existing)
            onMain {
                assertNotNull(model.claimDocumentPicker())
                model.onDocumentResult(context, Uri.parse("content://invalid.backup.destination/archive"))
            }
            awaitPhase(model, BackupExportPhase.FAILED)
            awaitRemoval(source)
            assertNotNull(model.failureClass)

            onMain {
                model.acknowledgeFailure()
                model.submit(context, manager)
            }
            awaitPhase(model, BackupExportPhase.DOCUMENT_PICKER_PENDING)
            val retrySource = onlyNewWorkspace(manager, existing)
            onMain {
                model.claimDocumentPicker()
                model.onDocumentResult(context, null)
            }
            awaitRemoval(retrySource)
        } finally {
            onMain { owner.viewModelStore.clear() }
        }
    }

    @Test
    fun sharedArchiveRemainsReadableAfterAnotherBackupAndRouteClose() = runBlocking {
        val manager = CacheManager(context)
        val existing = workspaceDirectories(manager)
        val owner = TestOwner()
        var launchedUri: Uri? = null
        try {
            val model = onMain { ViewModelProvider(owner)[BackupExportViewModel::class.java] }
            onMain {
                selectOnlyKeyboardExtensions(model)
                model.destination = Backup.Destination.SHARE_INTENT
                model.submit(context, manager)
            }
            awaitPhase(model, BackupExportPhase.SHARE_PENDING)
            val sharedUri = withContext(Dispatchers.Main) {
                val uri = requireNotNull(model.claimShareUri())
                val chooser = Backup.createShareIntent(context, uri)
                val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                model.onShareLaunched()
                requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            }
            launchedUri = sharedUri
            val originalBytes = context.contentResolver.openInputStream(sharedUri).use { input ->
                requireNotNull(input).readBytes()
            }
            assertTrue(originalBytes.isNotEmpty())
            var hasMetadata = false
            var hasManifest = false
            ZipInputStream(ByteArrayInputStream(originalBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "backup_metadata.json" -> hasMetadata = zip.readBytes().isNotEmpty()
                        "backup_manifest.json" -> hasManifest = zip.readBytes().isNotEmpty()
                    }
                }
            }
            assertTrue(hasMetadata && hasManifest)

            onMain { model.submit(context, manager) }
            awaitPhase(model, BackupExportPhase.SHARE_PENDING)
            onMain { owner.viewModelStore.clear() }
            withTimeout(30_000) {
                while (workspaceDirectories(manager) != existing) delay(20)
            }
            context.contentResolver.openInputStream(sharedUri).use { input ->
                assertArrayEquals(originalBytes, requireNotNull(input).readBytes())
            }
        } finally {
            onMain { owner.viewModelStore.clear() }
            launchedUri?.let { uri ->
                BackupShareLeaseStore(context).release(
                    BackupShareLeaseStore.Lease(uri, uri.pathSegments[1]),
                )
            }
        }
    }

    private fun selectOnlyKeyboardExtensions(model: BackupExportViewModel) {
        model.filesSelector.toggle(BackupComponent.PREFERENCES)
        model.filesSelector.toggle(BackupComponent.THEME_EXTENSIONS)
    }

    private suspend fun awaitPhase(model: BackupExportViewModel, phase: BackupExportPhase) {
        withTimeout(30_000) {
            while (model.phase != phase) delay(20)
        }
    }

    private suspend fun awaitRemoval(directory: File) {
        withTimeout(30_000) {
            while (directory.exists()) delay(20)
        }
    }

    private fun onlyNewWorkspace(manager: CacheManager, before: Set<File>): File {
        val newDirectories = workspaceDirectories(manager) - before
        assertEquals(1, newDirectories.size)
        return newDirectories.single()
    }

    private fun workspaceDirectories(manager: CacheManager): Set<File> =
        manager.backupAndRestore.dir.listFiles()?.filter(File::isDirectory)?.toSet() ?: emptySet()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private class TestOwner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
}
