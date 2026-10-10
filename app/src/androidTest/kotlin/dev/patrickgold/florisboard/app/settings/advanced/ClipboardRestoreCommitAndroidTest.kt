/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.ClipData
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.PreferenceStoreInitializationState
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSyncBehavior
import dev.patrickgold.florisboard.ime.clipboard.provider.ArchiveClipboardMediaRef
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFilesDatabase
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardHistoryDatabase
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardMediaOwnershipState
import dev.patrickgold.florisboard.ime.clipboard.provider.InstalledClipboardMedia
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.lib.io.ZipUtils
import dev.patrickgold.florisboard.test.EditorHarnessActivity
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.florisboard.lib.android.AndroidClipboardManager
import org.florisboard.lib.android.setOrClearPrimaryClip
import org.florisboard.lib.android.systemService
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardRestoreCommitAndroidTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: ClipboardManager
        get() = context.clipboardManager().value

    @Before
    fun prepareEmptyHistory() = runBlocking {
        dropFailureTriggers()
        awaitReadyClipboardManager()
        dropFailureTriggers()
        manager.commitHistoryRestore(
            items = emptyList(),
            selectedTypes = ItemType.entries.toSet(),
            replaceSelected = true,
        )
    }

    @After
    fun clearHistory() = runBlocking {
        try {
            dropFailureTriggers()
            manager.commitHistoryRestore(
                items = emptyList(),
                selectedTypes = ItemType.entries.toSet(),
                replaceSelected = true,
            )
        } finally {
            dropFailureTriggers()
        }
    }

    @Test
    fun generatedArchiveRoundTripPreservesNormalizedDisplayName() = runBlocking {
        assertEquals(
            "_archive-photo.png",
            roundTripImageArchive(
                sourceDisplayName = " \u0000archive-photo.png ",
                removeDisplayNameForLegacyFixture = false,
            ),
        )
    }

    @Test
    fun legacyArchiveWithoutDisplayNameUsesTheGenericFallback() = runBlocking {
        assertEquals(
            "Image",
            roundTripImageArchive(
                sourceDisplayName = null,
                removeDisplayNameForLegacyFixture = true,
            ),
        )
    }

    @Test
    fun commitRemapsArchiveMediaAndReplacesOnlySelectedTypes() = runBlocking {
        val prefs by FlorisPreferenceStore
        val previousInternalClipboard = prefs.clipboard.useInternalClipboard.get()
        val previousInboundSync = prefs.clipboard.syncToFloris.get()
        var scenario: ActivityScenario<EditorHarnessActivity>? = null
        var platformClipboard: AndroidClipboardManager? = null
        var previousSystemClip: ClipData? = null
        var previousSystemRoots = emptySet<OwnedClipboardMediaUri>()
        var previousClipCaptured = false
        suspend fun awaitWindowFocus() = withTimeout(15_000L) {
            var focused = false
            while (!focused) {
                requireNotNull(scenario).onActivity { focused = it.window.decorView.hasWindowFocus() }
                if (!focused) delay(10L)
            }
        }
        val sourceBytes = byteArrayOf(2, 4, 6, 8)
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-restore-", ".bin")
        var restoredOwned: OwnedClipboardMediaUri? = null
        val testResult = runCatching {
            scenario = ActivityScenario.launch(EditorHarnessActivity::class.java)
            awaitWindowFocus()
            requireNotNull(scenario).onActivity { activity ->
                assertTrue(activity.window.decorView.hasWindowFocus())
                val clipboard = context.systemService(AndroidClipboardManager::class)
                platformClipboard = clipboard
                previousSystemClip = clipboard.primaryClip
                previousSystemRoots = ClipboardFileStorage.systemRoots(context)
                ClipboardFileStorage.prepareSystemRoots(context, previousSystemRoots)
                previousClipCaptured = true
            }
            Files.write(source, sourceBytes)
            prefs.clipboard.useInternalClipboard.set(true).getOrThrow()
            prefs.clipboard.syncToFloris.set(ClipboardSyncBehavior.NO_EVENTS).getOrThrow()
            awaitWindowFocus()
            requireNotNull(scenario).onActivity { activity ->
                assertTrue(activity.window.decorView.hasWindowFocus())
                val clipboard = requireNotNull(platformClipboard)
                clipboard.setPrimaryClip(ClipData.newPlainText("Clipboard test", "unrelated text"))
                assertEquals("unrelated text", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
            manager.commitHistoryRestore(
                items = listOf(
                    ClipboardItem.text("retained text"),
                ),
                selectedTypes = setOf(ItemType.TEXT),
                replaceSelected = true,
            )
            val payload = preparedImagePayload(
                listOf(
                    SourceMedia(
                        sourceId = 42L,
                        path = source,
                        expectedBytes = sourceBytes.size.toLong(),
                        displayName = "Restored photo.png",
                    ),
                ),
            )

            assertEquals(
                ClipboardRestoreCommitResult.Committed,
                ClipboardRestoreCommit.commit(
                    context = context,
                    clipboardManager = manager,
                    payload = payload,
                    replaceSelected = true,
                ),
            )

            val restored = manager.currentHistory.all.single { it.type == ItemType.IMAGE }
            val owned = restored.uri?.let {
                OwnedClipboardMediaUri.parse(it, ItemType.IMAGE)
            }
            assertNotNull(owned)
            restoredOwned = owned
            assertEquals(restored.uri, requireNotNull(owned).uri)
            assertEquals(1, manager.currentHistory.all.count { it.type == ItemType.TEXT })
            val liveFile = ClipboardFileStorage.ownedFile(context, owned)
            assertNotNull(liveFile)
            assertArrayEquals(sourceBytes, requireNotNull(liveFile).readBytes())
            assertEquals(
                "Restored photo.png",
                ClipboardFileStorage.fileInfo(context, owned)?.displayName,
            )

            // Releasing a real media lease requests a fenced actor observation.
            manager.acquireBackupSnapshot(setOf(ItemType.IMAGE)).release()
            executeDatabaseSql(
                """
                    CREATE TRIGGER $MEDIA_ACTIVE_FAILURE_TRIGGER
                    BEFORE UPDATE OF ownership_state ON $MEDIA_DATABASE_NAME
                    WHEN NEW._id = ${owned.id}
                        AND NEW.ownership_state = ${ClipboardMediaOwnershipState.ACTIVE.value}
                    BEGIN
                        SELECT RAISE(ABORT, 'synthetic ownership failure');
                    END
                """.trimIndent(),
                databaseName = MEDIA_DATABASE_NAME,
            )
            manager.commitHistoryRestore(
                items = listOf(restored.copy(isPinned = true)),
                selectedTypes = setOf(ItemType.IMAGE),
                replaceSelected = true,
            )
            val replacement = storedHistoryItems().single { it.type == ItemType.IMAGE }
            assertEquals(owned.uri, replacement.uri)
            assertTrue(replacement.isPinned)
            assertEquals(ClipboardMediaOwnershipState.RETIRING, storedMediaOwnership(owned))
            assertArrayEquals(sourceBytes, requireNotNull(liveFile).readBytes())

            dropFailureTriggers()
            // This no-op cannot independently activate the retained image.
            manager.commitHistoryRestore(emptyList(), setOf(ItemType.TEXT), replaceSelected = false)
            assertEquals(ClipboardMediaOwnershipState.ACTIVE, storedMediaOwnership(owned))
            assertEquals(replacement, storedHistoryItems().single { it.type == ItemType.IMAGE })
            assertArrayEquals(sourceBytes, requireNotNull(liveFile).readBytes())

            manager.commitHistoryRestore(
                items = emptyList(),
                selectedTypes = setOf(ItemType.IMAGE),
                replaceSelected = true,
            )
            val retiredInfo = ClipboardFileStorage.fileInfo(context, owned)
            assertTrue(
                retiredInfo == null ||
                    retiredInfo.ownershipState == ClipboardMediaOwnershipState.RETIRING,
            )
            assertEquals(1, manager.currentHistory.all.count { it.type == ItemType.TEXT })
            assertEquals(
                emptySet<OwnedClipboardMediaUri>(),
                manager.currentHistory.all.mapNotNullTo(mutableSetOf()) { item ->
                    item.uri?.let(OwnedClipboardMediaUri::parse)
                },
            )
        }
        val triggerCleanup = runCatching { dropFailureTriggers() }
        val systemRestore = runCatching {
            if (previousClipCaptured) {
                awaitWindowFocus()
                requireNotNull(scenario).onActivity { activity ->
                    assertTrue(activity.window.decorView.hasWindowFocus())
                    ClipboardFileStorage.prepareSystemRoots(context, previousSystemRoots)
                    requireNotNull(platformClipboard).setOrClearPrimaryClip(previousSystemClip)
                }
            }
        }
        val historyCleanup = runCatching {
            manager.commitHistoryRestore(emptyList(), setOf(ItemType.IMAGE), replaceSelected = true)
        }
        val mediaCleanup = runCatching {
            if (historyCleanup.isSuccess && systemRestore.isSuccess) {
                restoredOwned?.let { owned ->
                    ClipboardFileStorage.recordSystemRoots(
                        context,
                        (ClipboardFileStorage.systemRoots(context) + previousSystemRoots) - owned,
                    )
                    ClipboardFileStorage.deleteOwned(context, owned)
                }
            }
        }
        val sourceCleanup = runCatching { Files.deleteIfExists(source) }
        val inboundRestore = runCatching {
            prefs.clipboard.syncToFloris.set(previousInboundSync).getOrThrow()
        }
        val internalRestore = runCatching {
            prefs.clipboard.useInternalClipboard.set(previousInternalClipboard).getOrThrow()
        }
        val scenarioCleanup = runCatching { scenario?.close() }
        val cleanupResults = listOf(
            triggerCleanup, historyCleanup, mediaCleanup, sourceCleanup,
            systemRestore, inboundRestore, internalRestore, scenarioCleanup,
        )
        testResult.exceptionOrNull()?.let { originalFailure ->
            cleanupResults.mapNotNull { it.exceptionOrNull() }
                .filterNot { it === originalFailure }
                .forEach(originalFailure::addSuppressed)
            throw originalFailure
        }
        cleanupResults.forEach { it.getOrThrow() }
    }

    @Test
    fun sharedMediaReferencesUseTheInstalledMimeUnionImmediately() = runBlocking {
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-shared-", ".bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        var restoredOwned: OwnedClipboardMediaUri? = null
        try {
            val authority = "dev.example.source.provider.clipboard"
            val ref = requireNotNull(
                ArchiveClipboardMediaRef.parse(
                    raw = "content://$authority/clips/images/42",
                    sourceAuthority = authority,
                    expectedType = ItemType.IMAGE,
                ),
            )
            val mimeUnion = listOf("image/png", "image/jpeg")
            val now = System.currentTimeMillis()
            val payload = PreparedClipboardRestore(
                selectedTypes = setOf(ItemType.IMAGE),
                items = listOf(
                    PreparedClipboardItem(
                        type = ItemType.IMAGE,
                        text = null,
                        creationTimestampMs = now,
                        isPinned = false,
                        mimeTypes = listOf("image/png"),
                        isSensitive = false,
                        isRemoteDevice = false,
                        mediaRef = ref,
                    ),
                    PreparedClipboardItem(
                        type = ItemType.IMAGE,
                        text = null,
                        creationTimestampMs = now - 1L,
                        isPinned = false,
                        mimeTypes = listOf("image/jpeg"),
                        isSensitive = false,
                        isRemoteDevice = false,
                        mediaRef = ref,
                    ),
                ),
                media = listOf(
                    PreparedClipboardMedia(
                        ref = ref,
                        stagedFile = source,
                        byteCount = Files.size(source),
                        mimeTypes = mimeUnion,
                    ),
                ),
            )

            assertEquals(
                ClipboardRestoreCommitResult.Committed,
                ClipboardRestoreCommit.commit(
                    context = context,
                    clipboardManager = manager,
                    payload = payload,
                    replaceSelected = true,
                ),
            )

            val restored = manager.currentHistory.all.filter { it.type == ItemType.IMAGE }
            assertEquals(2, restored.size)
            restored.forEach { item -> assertEquals(mimeUnion, item.mimeTypes) }
            restoredOwned = requireNotNull(
                restored.first().uri?.let {
                    OwnedClipboardMediaUri.parse(it, ItemType.IMAGE)
                },
            )
            assertEquals(
                mimeUnion,
                ClipboardFileStorage.fileInfo(context, requireNotNull(restoredOwned))?.mimeTypes,
            )
            assertEquals(
                "Image",
                ClipboardFileStorage.fileInfo(context, requireNotNull(restoredOwned))?.displayName,
            )
        } finally {
            manager.commitHistoryRestore(
                items = emptyList(),
                selectedTypes = setOf(ItemType.IMAGE),
                replaceSelected = true,
            )
            restoredOwned?.let { owned ->
                runCatching { ClipboardFileStorage.deleteOwned(context, owned) }
            }
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun mergePreservesExistingItemsOfTheSelectedType() = runBlocking {
        manager.commitHistoryRestore(
            items = listOf(
                ClipboardItem.text("existing text"),
            ),
            selectedTypes = setOf(ItemType.TEXT),
            replaceSelected = true,
        )
        val payload = PreparedClipboardRestore(
            selectedTypes = setOf(ItemType.TEXT),
            items = listOf(
                PreparedClipboardItem(
                    type = ItemType.TEXT,
                    text = "restored text",
                    creationTimestampMs = System.currentTimeMillis(),
                    isPinned = false,
                    mimeTypes = listOf("text/plain"),
                    isSensitive = false,
                    isRemoteDevice = false,
                    mediaRef = null,
                ),
            ),
            media = emptyList(),
        )

        assertEquals(
            ClipboardRestoreCommitResult.Committed,
            ClipboardRestoreCommit.commit(
                context = context,
                clipboardManager = manager,
                payload = payload,
                replaceSelected = false,
            ),
        )
        assertEquals(2, manager.currentHistory.all.count { it.type == ItemType.TEXT })
    }

    @Test
    fun cancellationAfterOneInstallCleansEveryFreshMediaReceipt() = runBlocking {
        val before = storedMediaNames()
        val first = Files.createTempFile(context.cacheDir.toPath(), "clipboard-cancel-first-", ".bin")
        val second = Files.createTempFile(context.cacheDir.toPath(), "clipboard-cancel-second-", ".bin")
        Files.write(first, byteArrayOf(1))
        RandomAccessFile(second.toFile(), "rw").use { file ->
            file.setLength(CANCELLATION_MEDIA_BYTES)
        }
        val sources = listOf(
            SourceMedia(41L, first, Files.size(first)),
            SourceMedia(42L, second, Files.size(second)),
        )
        val operation = async(Dispatchers.IO) {
            ClipboardRestoreCommit.commit(
                context = context,
                clipboardManager = manager,
                payload = preparedImagePayload(sources),
                replaceSelected = true,
            )
        }
        try {
            withTimeout(CANCELLATION_TIMEOUT_MS) {
                while (!installHasAdvancedToAnotherMedia(before)) {
                    check(!operation.isCompleted) {
                        "Restore completed before the cancellation checkpoint."
                    }
                    yield()
                }
            }

            operation.cancel(CancellationException("synthetic cancellation"))
            operation.cancelAndJoin()

            assertTrue(operation.isCancelled)
            assertEquals(before, storedMediaNames())
            assertEquals(0, manager.currentHistory.all.count { it.type == ItemType.IMAGE })
        } finally {
            if (!operation.isCompleted) {
                operation.cancelAndJoin()
            }
            manager.commitHistoryRestore(
                items = emptyList(),
                selectedTypes = setOf(ItemType.IMAGE),
                replaceSelected = true,
            )
            deleteNewImageMedia(before)
            sources.forEach { Files.deleteIfExists(it.path) }
        }
    }

    @Test
    fun failedInstallCleansEveryMediaPublishedBeforeIt() = runBlocking {
        val before = storedMediaNames()
        val sources = (1L..3L).map { id ->
            val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-restore-", ".bin")
            Files.write(source, byteArrayOf(id.toByte()))
            SourceMedia(
                sourceId = id,
                path = source,
                expectedBytes = if (id == 3L) 2L else 1L,
            )
        }
        try {
            val result = ClipboardRestoreCommit.commit(
                context = context,
                clipboardManager = manager,
                payload = preparedImagePayload(sources),
                replaceSelected = true,
            )

            assertEquals(
                ClipboardRestoreCommitResult.Failed(
                    ClipboardRestoreCommitFailure.MEDIA_INSTALL_FAILED,
                ),
                result,
            )
            assertEquals(before, storedMediaNames())
            assertEquals(0, manager.currentHistory.all.count { it.type == ItemType.IMAGE })
        } finally {
            sources.forEach { Files.deleteIfExists(it.path) }
        }
    }

    @Test
    fun roomTransactionFailureRollsBackSelectedRowsAndCleansFreshMedia() = runBlocking {
        val retainedSource =
            Files.createTempFile(context.cacheDir.toPath(), "clipboard-retained-", ".bin")
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-room-failure-", ".bin")
        Files.write(retainedSource, byteArrayOf(9, 8, 7))
        Files.write(source, byteArrayOf(1, 2, 3))
        var retainedInstall: InstalledClipboardMedia? = null
        var retainedRow: ClipboardItem? = null
        var before: Set<String>? = null
        val retainedTimestamp = System.currentTimeMillis() - 1_000L
        try {
            val installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = retainedSource,
                expectedBytes = Files.size(retainedSource),
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                displayName = "Retained image.png",
            )
            retainedInstall = installed
            manager.commitHistoryRestore(
                items = listOf(
                    ClipboardItem(
                        type = ItemType.IMAGE,
                        text = null,
                        uri = installed.ownedUri.uri,
                        creationTimestampMs = retainedTimestamp,
                        isPinned = true,
                        mimeTypes = listOf("image/png"),
                        isSensitive = true,
                        isRemoteDevice = true,
                    ),
                ),
                selectedTypes = setOf(ItemType.IMAGE),
                replaceSelected = true,
            )
            retainedRow = storedHistoryItems().single()
            before = storedMediaNames()
            installHistoryInsertFailureTrigger()

            val result = ClipboardRestoreCommit.commit(
                context = context,
                clipboardManager = manager,
                payload = preparedImagePayload(
                    listOf(SourceMedia(42L, source, Files.size(source))),
                ),
                replaceSelected = true,
            )

            assertEquals(
                ClipboardRestoreCommitResult.Failed(
                    ClipboardRestoreCommitFailure.HISTORY_UPDATE_FAILED,
                ),
                result,
            )
            assertEquals(requireNotNull(before), storedMediaNames())
            assertEquals(listOf(requireNotNull(retainedRow)), storedHistoryItems())
        } finally {
            dropFailureTriggers()
            runCatching {
                manager.commitHistoryRestore(
                    items = emptyList(),
                    selectedTypes = setOf(ItemType.IMAGE),
                    replaceSelected = true,
                )
            }
            before?.let(::deleteNewImageMedia)
            retainedInstall?.ownedUri?.let { owned ->
                runCatching {
                    ClipboardFileStorage.deleteOwned(
                        context = context,
                        ownedUri = owned,
                        observedBootCount = Int.MAX_VALUE,
                    )
                }
            }
            runCatching { retainedInstall?.cleanup() }
            Files.deleteIfExists(retainedSource)
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun rejectedHistoryUpdateCleansInstalledMedia() = runBlocking {
        val prefs by FlorisPreferenceStore
        val previousLimitEnabled = prefs.clipboard.historySizeLimitEnabled.get()
        val previousLimit = prefs.clipboard.historySizeLimit.get()
        val before = storedMediaNames()
        val source = Files.createTempFile(context.cacheDir.toPath(), "clipboard-restore-", ".bin")
        Files.write(source, byteArrayOf(1))
        try {
            prefs.clipboard.historySizeLimit.set(0).getOrThrow()
            prefs.clipboard.historySizeLimitEnabled.set(true).getOrThrow()

            val result = ClipboardRestoreCommit.commit(
                context = context,
                clipboardManager = manager,
                payload = preparedImagePayload(listOf(SourceMedia(42L, source, 1L))),
                replaceSelected = true,
            )

            assertEquals(
                ClipboardRestoreCommitResult.Failed(
                    ClipboardRestoreCommitFailure.HISTORY_UPDATE_FAILED,
                ),
                result,
            )
            assertEquals(before, storedMediaNames())
            assertEquals(0, manager.currentHistory.all.count { it.type == ItemType.IMAGE })
        } finally {
            prefs.clipboard.historySizeLimit.set(previousLimit).getOrThrow()
            prefs.clipboard.historySizeLimitEnabled.set(previousLimitEnabled).getOrThrow()
            Files.deleteIfExists(source)
        }
    }

    private suspend fun roundTripImageArchive(
        sourceDisplayName: String?,
        removeDisplayNameForLegacyFixture: Boolean,
    ): String {
        val testRoot = Files.createTempDirectory(
            context.cacheDir.toPath(),
            "clipboard-archive-round-trip-",
        )
        val input = Files.createDirectory(testRoot.resolve("input"))
        val stagingParent = Files.createDirectory(testRoot.resolve("staging"))
        val archive = testRoot.resolve("backup.zip")
        val source = Files.write(testRoot.resolve("source.bin"), byteArrayOf(2, 4, 6, 8))
        var sourceInstall: InstalledClipboardMedia? = null
        var restoredOwned: OwnedClipboardMediaUri? = null
        try {
            val installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = Files.size(source),
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
                displayName = sourceDisplayName,
            )
            sourceInstall = installed
            ClipboardBackupPayload.write(
                context = context,
                stagedRoot = input.toFile(),
                sourcePackageName = context.packageName,
                selectedTypes = setOf(ItemType.IMAGE),
                items = listOf(
                    ClipboardItem(
                        type = ItemType.IMAGE,
                        text = null,
                        uri = installed.ownedUri.uri,
                        creationTimestampMs = 1L,
                        isPinned = false,
                        mimeTypes = listOf("image/png"),
                    ),
                ),
                transferBudget = clipboardTransferBudget(),
                checkActive = {},
            )
            if (removeDisplayNameForLegacyFixture) {
                val index = input.resolve(BackupArchive.CLIPBOARD_IMAGES_PATH)
                val encoded = Files.readAllBytes(index).decodeToString()
                val legacy = encoded.replace(",\"displayName\":\"Image\"", "")
                check(legacy != encoded && "\"displayName\"" !in legacy) {
                    "Generated payload did not contain the expected optional field."
                }
                Files.write(index, legacy.encodeToByteArray())
            }
            writeArchiveControlFiles(input)
            writeArchive(input, archive)
            assertTrue(installed.cleanup())
            sourceInstall = null

            val sessionResult = BackupArchiveSession.open(
                ArchiveSnapshot(archive, Files.size(archive)),
            )
            check(sessionResult is BackupArchiveSessionResult.Valid) {
                "Generated archive did not open."
            }
            sessionResult.session.use { session ->
                val planResult = session.createPlan(
                    setOf(BackupComponent.CLIPBOARD_IMAGES),
                )
                check(planResult is RestorePlanResult.Valid) {
                    "Generated archive did not produce a restore plan."
                }
                val stageResult = BackupArchiveStager.stage(
                    session = session,
                    plan = planResult.plan,
                    stagingParent = stagingParent,
                    budget = RestoreStagingBudget(
                        maxBytes = 1L shl 20,
                        requiredFreeBytes = 0L,
                    ),
                )
                check(stageResult is BackupArchiveStagingResult.Valid) {
                    "Generated archive did not stage."
                }
                stageResult.stagedRestore.use { staged ->
                    val payloadResult = ClipboardRestorePayload.prepare(
                        stagedRoot = staged.root,
                        sourcePackageName = session.archive.metadata.packageName,
                        selectedTypes = setOf(ItemType.IMAGE),
                    )
                    check(payloadResult is ClipboardRestorePayloadResult.Valid) {
                        "Staged clipboard payload was rejected."
                    }
                    assertEquals(
                        ClipboardRestoreCommitResult.Committed,
                        ClipboardRestoreCommit.commit(
                            context = context,
                            clipboardManager = manager,
                            payload = payloadResult.payload,
                            replaceSelected = true,
                        ),
                    )
                }
            }

            val restored = manager.currentHistory.all.single { it.type == ItemType.IMAGE }
            restoredOwned = requireNotNull(
                restored.uri?.let {
                    OwnedClipboardMediaUri.parse(it, ItemType.IMAGE)
                },
            )
            return requireNotNull(
                ClipboardFileStorage.fileInfo(context, requireNotNull(restoredOwned))
                    ?.displayName,
            )
        } finally {
            manager.commitHistoryRestore(
                items = emptyList(),
                selectedTypes = setOf(ItemType.IMAGE),
                replaceSelected = true,
            )
            restoredOwned?.let { owned ->
                runCatching { ClipboardFileStorage.deleteOwned(context, owned) }
            }
            sourceInstall?.let { install ->
                runCatching { install.cleanup() }
            }
            testRoot.toFile().deleteRecursively()
        }
    }

    private fun clipboardTransferBudget() = ZipUtils.TransferBudget(
        maxEntries = 8,
        maxBytes = 1L shl 20,
        maxFileBytes = 1L shl 20,
        checkCancelled = {},
    )

    private fun writeArchiveControlFiles(input: Path) {
        Files.write(
            input.resolve(BackupArchive.METADATA_JSON_NAME),
            """
                {
                  "package": "${context.packageName}",
                  "versionCode": 95,
                  "versionName": "test",
                  "timestamp": 1
                }
            """.trimIndent().encodeToByteArray(),
        )
        Files.write(
            input.resolve(BackupArchive.MANIFEST_JSON_NAME),
            """
                {
                  "formatVersion": 1,
                  "components": ["${BackupComponent.CLIPBOARD_IMAGES.wireId}"]
                }
            """.trimIndent().encodeToByteArray(),
        )
    }

    private fun writeArchive(input: Path, archive: Path) {
        val limits = ArchiveLimits.Default
        ZipUtils.zip(
            input.toFile(),
            archive.toFile(),
            ZipUtils.WriteLimits(
                maxEntries = limits.maxEntries,
                maxSourceBytes = limits.maxExpandedBytes,
                maxFileBytes = limits.maxEntryBytes,
                maxPathBytes = limits.maxPathBytes,
                maxPathSegmentBytes = limits.maxPathSegmentBytes,
                maxOutputBytes = limits.maxArchiveBytes,
                maxFileBytesForPath = limits::maxEntryBytesFor,
                checkCancelled = {},
            ),
        )
    }

    private fun installHasAdvancedToAnotherMedia(before: Set<String>): Boolean {
        val createdNames = storedMediaNames() - before
        return createdNames.any { it.toLongOrNull()?.let { id -> id > 0L } == true } &&
            createdNames.any { name ->
                name.startsWith(".clipboard-media-") && name.endsWith(".partial")
            }
    }

    private fun deleteNewImageMedia(before: Set<String>) {
        (storedMediaNames() - before).forEach { name ->
            val id = name.toLongOrNull() ?: return@forEach
            val owned = OwnedClipboardMediaUri.create(id, ItemType.IMAGE) ?: return@forEach
            runCatching { ClipboardFileStorage.deleteOwned(context, owned) }
        }
    }

    private fun installHistoryInsertFailureTrigger() {
        dropFailureTriggers()
        executeDatabaseSql(
            """
                CREATE TRIGGER $HISTORY_INSERT_FAILURE_TRIGGER
                BEFORE INSERT ON $HISTORY_DATABASE_NAME
                BEGIN
                    SELECT RAISE(ABORT, 'synthetic restore failure');
                END
            """.trimIndent(),
        )
    }

    private fun dropFailureTriggers() {
        val results = listOf(
            HISTORY_DATABASE_NAME to HISTORY_INSERT_FAILURE_TRIGGER,
            MEDIA_DATABASE_NAME to MEDIA_ACTIVE_FAILURE_TRIGGER,
        ).map { (databaseName, triggerName) ->
            runCatching {
                if (context.getDatabasePath(databaseName).isFile) {
                    executeDatabaseSql("DROP TRIGGER IF EXISTS $triggerName", databaseName)
                }
            }
        }
        results.forEach { it.getOrThrow() }
    }

    private fun executeDatabaseSql(statement: String, databaseName: String = HISTORY_DATABASE_NAME) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(statement)
        }
    }

    private fun preparedImagePayload(sources: List<SourceMedia>): PreparedClipboardRestore {
        val authority = "dev.example.source.provider.clipboard"
        val media = sources.map { source ->
            val ref = requireNotNull(
                ArchiveClipboardMediaRef.parse(
                    raw = "content://$authority/clips/images/${source.sourceId}",
                    sourceAuthority = authority,
                    expectedType = ItemType.IMAGE,
                ),
            )
            PreparedClipboardMedia(
                ref = ref,
                stagedFile = source.path,
                byteCount = source.expectedBytes,
                mimeTypes = listOf("image/png"),
                displayName = source.displayName,
            )
        }
        return PreparedClipboardRestore(
            selectedTypes = setOf(ItemType.IMAGE),
            items = media.mapIndexed { index, value ->
                PreparedClipboardItem(
                    type = ItemType.IMAGE,
                    text = null,
                    creationTimestampMs = System.currentTimeMillis() - index,
                    isPinned = false,
                    mimeTypes = listOf("image/png"),
                    isSensitive = false,
                    isRemoteDevice = false,
                    mediaRef = value.ref,
                )
            },
            media = media,
        )
    }

    private fun storedMediaNames(): Set<String> {
        val directory = context.noBackupFilesDir.toPath()
            .resolve(ClipboardFileStorage.CLIPBOARD_FILES_PATH)
        if (!Files.isDirectory(directory)) return emptySet()
        return Files.newDirectoryStream(directory).use { children ->
            children.mapTo(mutableSetOf()) { it.fileName.toString() }
        }
    }

    private fun storedHistoryItems(): List<ClipboardItem> {
        val database = ClipboardHistoryDatabase.new(context)
        return try {
            database.clipboardItemDao().getAll().sortedBy(ClipboardItem::id)
        } finally {
            database.close()
        }
    }

    private fun storedMediaOwnership(owned: OwnedClipboardMediaUri): ClipboardMediaOwnershipState {
        val database = ClipboardFilesDatabase.new(context)
        return try {
            database.clipboardFilesDao().getAll().single { it.id == owned.id }.ownershipState
        } finally {
            database.close()
        }
    }

    private suspend fun awaitReadyClipboardManager() {
        val application = context.applicationContext as FlorisApplication
        val preferenceState = withTimeout(STARTUP_TIMEOUT_MS) {
            application.preferenceStoreInitializationState.first { state ->
                state != PreferenceStoreInitializationState.LOADING
            }
        }
        assertEquals(PreferenceStoreInitializationState.READY, preferenceState)
        withTimeout(STARTUP_TIMEOUT_MS) {
            manager.awaitInitialization()
        }
    }

    private data class SourceMedia(
        val sourceId: Long,
        val path: Path,
        val expectedBytes: Long,
        val displayName: String? = null,
    )

    companion object {
        private const val CANCELLATION_MEDIA_BYTES = 32L * 1024L * 1024L
        private const val CANCELLATION_TIMEOUT_MS = 10_000L
        private const val STARTUP_TIMEOUT_MS = 15_000L
        private const val HISTORY_DATABASE_NAME = "clipboard_history"
        private const val HISTORY_INSERT_FAILURE_TRIGGER =
            "clipboard_restore_test_abort_insert"
        private const val MEDIA_DATABASE_NAME = "clipboard_files"
        private const val MEDIA_ACTIVE_FAILURE_TRIGGER = "clipboard_restore_test_abort_active"
    }
}
