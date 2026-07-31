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

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardExternalMediaImporterAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetSource() {
        ClipboardExternalMediaTestSource.reset()
        ClipboardExternalMediaTestSource.grantReadAccess()
        warmImportWorker()
        ClipboardExternalMediaTestSource.reset()
        ClipboardExternalMediaTestSource.grantReadAccess()
    }

    @After
    fun releaseSource() {
        ClipboardExternalMediaTestSource.releaseBlockingOpen()
        ClipboardExternalMediaTestSource.revokeReadAccess()
    }

    @Test
    fun timeoutReplacesTheBlockedWorkerAndRecoversBeforeTheProviderReturns() {
        val directoryName = "clipboard-importer-timeout-test"
        val recoveryDirectoryName = "$directoryName-recovery"
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = directoryName,
        )
        val recoveryImporter = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = recoveryDirectoryName,
        )
        var recovered: StagedClipboardMedia? = null

        try {
            val timeoutStartedAt = SystemClock.elapsedRealtime()
            assertNull(importer.stage(ClipboardExternalMediaTestSource.blockingUri))
            assertTrue(
                SystemClock.elapsedRealtime() - timeoutStartedAt < MAX_TIMEOUT_RETURN_MS,
            )
            assertTrue(ClipboardExternalMediaTestSource.awaitBlockingOpen(AWAIT_MS))
            awaitCondition { partialFiles(directoryName).isEmpty() }

            recovered = recoveryImporter.stage(ClipboardExternalMediaTestSource.healthyUri)
            val staged = requireNotNull(recovered)
            assertEquals(2, ClipboardExternalMediaTestSource.openCount())
            assertArrayEquals(byteArrayOf(1, 3, 3, 7), Files.readAllBytes(staged.path))
            assertEquals(4L, staged.byteCount)
            assertEquals("_vector.svg", staged.displayName)
            assertEquals(
                "application/octet-stream",
                staged.sourceMimeType,
            )
            assertFalse(staged.toString().contains("_vector.svg"))
            assertFreshRemoteWorkerWasUsed()
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            recovered?.close()
            importer.close()
            recoveryImporter.close()
            awaitCondition { partialFiles(directoryName).isEmpty() }
            awaitCondition { partialFiles(recoveryDirectoryName).isEmpty() }
        }
    }

    @Test
    fun closeAbandonsActiveWorkAndAFreshImporterRecoversBeforeProviderRelease() {
        val directoryName = "clipboard-importer-close-test"
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = directoryName,
        )
        val caller = Executors.newSingleThreadExecutor()
        val import = caller.submit<StagedClipboardMedia?> {
            importer.stage(ClipboardExternalMediaTestSource.cancellationAwareUri)
        }
        val replacement = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = "$directoryName-replacement",
        )
        var recovered: StagedClipboardMedia? = null

        try {
            assertTrue(ClipboardExternalMediaTestSource.awaitBlockingOpen(AWAIT_MS))

            val closeStartedAt = SystemClock.elapsedRealtime()
            importer.close()
            assertTrue(SystemClock.elapsedRealtime() - closeStartedAt < MAX_REJECTION_RETURN_MS)

            assertNull(import.get(AWAIT_MS, TimeUnit.MILLISECONDS))
            assertNull(importer.stage(ClipboardExternalMediaTestSource.healthyUri))
            awaitCondition { partialFiles(directoryName).isEmpty() }

            recovered = replacement.stage(ClipboardExternalMediaTestSource.healthyUri)
            val recoveredStage = requireNotNull(recovered)
            assertArrayEquals(byteArrayOf(1, 3, 3, 7), Files.readAllBytes(recoveredStage.path))
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            recovered?.close()
            importer.close()
            replacement.close()
            caller.shutdownNow()
            awaitCondition { partialFiles(directoryName).isEmpty() }
            awaitCondition { partialFiles("$directoryName-replacement").isEmpty() }
        }
    }

    @Test
    fun blockingMimeTypeLookupRecoversInAFreshProcessBeforeRelease() {
        assertPreOpenTimeoutAndRecovery(
            source = ClipboardExternalMediaTestSource.blockingMimeTypeUri,
            directoryName = "clipboard-importer-blocking-mime-test",
            expectedMimeTypeQueries = 1,
            expectedDisplayNameQueries = 0,
        )
    }

    @Test
    fun blockingDisplayNameLookupRecoversInAFreshProcessBeforeRelease() {
        assertPreOpenTimeoutAndRecovery(
            source = ClipboardExternalMediaTestSource.blockingDisplayNameUri,
            directoryName = "clipboard-importer-blocking-name-test",
            expectedMimeTypeQueries = 1,
            expectedDisplayNameQueries = 1,
        )
    }

    @Test
    fun cancellationReturnsWhileBlockingMimeTypeLookupRemainsIsolated() {
        val directoryName = "clipboard-importer-cancel-mime-test"
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = directoryName,
        )
        val cancellationSignal = CancellationSignal()
        val caller = Executors.newSingleThreadExecutor()
        val import = caller.submit<StagedClipboardMedia?> {
            importer.stage(
                ClipboardExternalMediaTestSource.blockingMimeTypeUri,
                cancellationSignal,
            )
        }
        var recovered: StagedClipboardMedia? = null
        var primaryFailure: Throwable? = null

        try {
            assertTrue(ClipboardExternalMediaTestSource.awaitBlockingOpen(AWAIT_MS))
            cancellationSignal.cancel()
            assertNull(import.get(AWAIT_MS, TimeUnit.MILLISECONDS))
            assertEquals(1, ClipboardExternalMediaTestSource.mimeTypeQueryCount())
            assertEquals(0, ClipboardExternalMediaTestSource.displayNameQueryCount())
            assertEquals(0, ClipboardExternalMediaTestSource.openCount())

            recovered = importer.stage(ClipboardExternalMediaTestSource.healthyUri)
            requireNotNull(recovered)
            assertEquals(1, ClipboardExternalMediaTestSource.openCount())
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            recovered?.close()
            importer.close()
            caller.shutdownNow()
            runCatching {
                awaitCondition { partialFiles(directoryName).isEmpty() }
            }.exceptionOrNull()?.let { cleanupFailure ->
                val originalFailure = primaryFailure
                if (originalFailure == null) {
                    throw cleanupFailure
                } else {
                    originalFailure.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    @Test
    fun mimeLookupRunsInsideTheWorkerOnEverySupportedApi() {
        val directoryName = "clipboard-importer-legacy-mime-test"
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = directoryName,
        )
        var first: StagedClipboardMedia? = null
        var second: StagedClipboardMedia? = null

        try {
            first = importer.stage(ClipboardExternalMediaTestSource.healthyUri)
            val firstStage = requireNotNull(first)
            assertArrayEquals(byteArrayOf(1, 3, 3, 7), Files.readAllBytes(firstStage.path))
            assertEquals("application/octet-stream", firstStage.sourceMimeType)
            assertEquals(1, ClipboardExternalMediaTestSource.mimeTypeQueryCount())

            second = importer.stage(ClipboardExternalMediaTestSource.healthyUri)
            val secondStage = requireNotNull(second)
            assertArrayEquals(byteArrayOf(1, 3, 3, 7), Files.readAllBytes(secondStage.path))
            assertEquals("application/octet-stream", secondStage.sourceMimeType)
            assertEquals(2, ClipboardExternalMediaTestSource.mimeTypeQueryCount())
            assertEquals(2, ClipboardExternalMediaTestSource.displayNameQueryCount())
            assertEquals(2, ClipboardExternalMediaTestSource.openCount())
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            first?.close()
            second?.close()
            importer.close()
            awaitCondition { partialFiles(directoryName).isEmpty() }
        }
    }

    @Test
    fun cancellationStopsCooperativeDisplayNameLookupBeforeOpen() {
        val directoryName = "clipboard-importer-cancel-name-test"
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = directoryName,
        )
        val cancellationSignal = CancellationSignal()
        val caller = Executors.newSingleThreadExecutor()
        val import = caller.submit<StagedClipboardMedia?> {
            importer.stage(
                ClipboardExternalMediaTestSource.cancellationAwareDisplayNameUri,
                cancellationSignal,
            )
        }
        var recovered: StagedClipboardMedia? = null

        try {
            assertTrue(ClipboardExternalMediaTestSource.awaitBlockingOpen(AWAIT_MS))
            cancellationSignal.cancel()
            assertTrue(ClipboardExternalMediaTestSource.awaitCancellation(AWAIT_MS))
            assertNull(import.get(AWAIT_MS, TimeUnit.MILLISECONDS))
            assertEquals(
                1,
                ClipboardExternalMediaTestSource.mimeTypeQueryCount(),
            )
            assertEquals(1, ClipboardExternalMediaTestSource.displayNameQueryCount())
            assertEquals(0, ClipboardExternalMediaTestSource.openCount())

            recovered = importer.stage(ClipboardExternalMediaTestSource.healthyUri)
            requireNotNull(recovered)
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            recovered?.close()
            importer.close()
            caller.shutdownNow()
            awaitCondition { partialFiles(directoryName).isEmpty() }
        }
    }

    @Test
    fun exactGrantIsRequiredInsideTheRemoteWorker() {
        val directoryName = "clipboard-importer-grant-test"
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = directoryName,
        )
        var staged: StagedClipboardMedia? = null

        try {
            ClipboardExternalMediaTestSource.revokeReadAccess()
            assertNull(importer.stage(ClipboardExternalMediaTestSource.healthyUri))
            assertTrue(partialFiles(directoryName).isEmpty())

            ClipboardExternalMediaTestSource.grantReadAccess(
                ClipboardExternalMediaTestSource.svgUri,
            )
            assertNull(importer.stage(ClipboardExternalMediaTestSource.healthyUri))
            assertTrue(partialFiles(directoryName).isEmpty())

            ClipboardExternalMediaTestSource.grantReadAccess(
                ClipboardExternalMediaTestSource.healthyUri,
            )
            staged = importer.stage(ClipboardExternalMediaTestSource.healthyUri)
            assertArrayEquals(
                byteArrayOf(1, 3, 3, 7),
                Files.readAllBytes(requireNotNull(staged).path),
            )

            ClipboardExternalMediaTestSource.revokeReadAccess(
                ClipboardExternalMediaTestSource.healthyUri,
            )
            staged.close()
            staged = null
            assertNull(importer.stage(ClipboardExternalMediaTestSource.healthyUri))
            assertTrue(partialFiles(directoryName).isEmpty())
            assertEquals(1, ClipboardExternalMediaTestSource.openCount())
        } finally {
            staged?.close()
            importer.close()
            awaitCondition { partialFiles(directoryName).isEmpty() }
        }
    }

    @Test
    fun partialStreamDeathPreservesClaimedStagesAndCallerCache() {
        val claimedDirectory = "clipboard-importer-claimed-test"
        val hostileDirectory = "clipboard-importer-partial-stream-test"
        val recoveredDirectory = "clipboard-importer-recovered-test"
        val claimedImporter = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = claimedDirectory,
        )
        val hostileImporter = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = PARTIAL_STREAM_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = hostileDirectory,
        )
        val recoveredImporter = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = recoveredDirectory,
        )
        val sentinelBeforeDeath =
            Files.createTempFile(context.cacheDir.toPath(), "clipboard-import-sentinel-", ".tmp")
        var sentinelAfterDeath: Path? = null
        var claimed: StagedClipboardMedia? = null
        var recovered: StagedClipboardMedia? = null

        try {
            Files.write(sentinelBeforeDeath, byteArrayOf(4, 2))
            claimed = claimedImporter.stage(ClipboardExternalMediaTestSource.healthyUri)
            val claimedStage = requireNotNull(claimed)
            assertArrayEquals(byteArrayOf(1, 3, 3, 7), Files.readAllBytes(claimedStage.path))

            assertNull(hostileImporter.stage(ClipboardExternalMediaTestSource.prefixThenBlockUri))
            assertTrue(ClipboardExternalMediaTestSource.awaitPrefixWritten(AWAIT_MS))
            awaitCondition { partialFiles(hostileDirectory).isEmpty() }

            val freshSentinel = Files.createTempFile(
                context.cacheDir.toPath(),
                "clipboard-import-fresh-sentinel-",
                ".tmp",
            )
            sentinelAfterDeath = freshSentinel
            Files.write(freshSentinel, byteArrayOf(2, 4))
            recovered = recoveredImporter.stage(ClipboardExternalMediaTestSource.healthyUri)
            val recoveredStage = requireNotNull(recovered)

            assertFreshRemoteWorkerWasUsed()
            assertTrue(Files.isRegularFile(sentinelBeforeDeath))
            assertTrue(Files.isRegularFile(freshSentinel))
            assertArrayEquals(byteArrayOf(4, 2), Files.readAllBytes(sentinelBeforeDeath))
            assertArrayEquals(
                byteArrayOf(2, 4),
                Files.readAllBytes(freshSentinel),
            )
            assertArrayEquals(byteArrayOf(1, 3, 3, 7), Files.readAllBytes(claimedStage.path))
            assertArrayEquals(byteArrayOf(1, 3, 3, 7), Files.readAllBytes(recoveredStage.path))
            assertEquals(3, ClipboardExternalMediaTestSource.openCount())

            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            assertTrue(ClipboardExternalMediaTestSource.awaitPrefixCompleted(AWAIT_MS))
            assertTrue(partialFiles(hostileDirectory).isEmpty())
            assertArrayEquals(byteArrayOf(1, 3, 3, 7), Files.readAllBytes(claimedStage.path))

            claimedStage.close()
            claimedStage.close()
            assertFalse(Files.exists(claimedStage.path))
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            claimed?.close()
            recovered?.close()
            claimedImporter.close()
            hostileImporter.close()
            recoveredImporter.close()
            Files.deleteIfExists(sentinelBeforeDeath)
            sentinelAfterDeath?.let { Files.deleteIfExists(it) }
            awaitCondition { partialFiles(claimedDirectory).isEmpty() }
            awaitCondition { partialFiles(hostileDirectory).isEmpty() }
            awaitCondition { partialFiles(recoveredDirectory).isEmpty() }
        }
    }

    private fun assertPreOpenTimeoutAndRecovery(
        source: Uri,
        directoryName: String,
        expectedMimeTypeQueries: Int,
        expectedDisplayNameQueries: Int,
    ) {
        val recoveryDirectoryName = "$directoryName-recovery"
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = directoryName,
        )
        val recoveryImporter = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = LONG_IMPORT_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = recoveryDirectoryName,
        )
        var recovered: StagedClipboardMedia? = null

        try {
            val timeoutStartedAt = SystemClock.elapsedRealtime()
            assertNull(importer.stage(source))
            assertTrue(SystemClock.elapsedRealtime() - timeoutStartedAt < MAX_TIMEOUT_RETURN_MS)
            assertTrue(ClipboardExternalMediaTestSource.awaitBlockingOpen(AWAIT_MS))
            assertEquals(
                expectedMimeTypeQueries,
                ClipboardExternalMediaTestSource.mimeTypeQueryCount(),
            )
            assertEquals(
                expectedDisplayNameQueries,
                ClipboardExternalMediaTestSource.displayNameQueryCount(),
            )
            assertEquals(0, ClipboardExternalMediaTestSource.openCount())
            awaitCondition { partialFiles(directoryName).isEmpty() }

            recovered = recoveryImporter.stage(ClipboardExternalMediaTestSource.healthyUri)
            requireNotNull(recovered)
            assertEquals(1, ClipboardExternalMediaTestSource.openCount())
            if (source == ClipboardExternalMediaTestSource.blockingDisplayNameUri) {
                assertFreshRemoteWorkerWasUsed()
            }
        } finally {
            ClipboardExternalMediaTestSource.releaseBlockingOpen()
            recovered?.close()
            importer.close()
            recoveryImporter.close()
            awaitCondition { partialFiles(directoryName).isEmpty() }
            awaitCondition { partialFiles(recoveryDirectoryName).isEmpty() }
        }
    }

    private fun partialFiles(directoryName: String): List<Path> {
        val directory = context.cacheDir.toPath().resolve(directoryName)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.newDirectoryStream(directory).use { children ->
            children
                .filter { child ->
                    val name = child.fileName.toString()
                    name.startsWith(".clipboard-provider-") && name.endsWith(".partial")
                }
        }
    }

    private fun warmImportWorker() {
        val directoryName = "clipboard-importer-warmup-test"
        val importer = ClipboardExternalMediaImporter(
            context = context,
            timeoutMs = WARMUP_TIMEOUT_MS,
            stageCapacity = { MAX_TEST_BYTES },
            stagingDirectory = directoryName,
        )
        var staged: StagedClipboardMedia? = null
        try {
            staged = importer.stage(ClipboardExternalMediaTestSource.healthyUri)
            requireNotNull(staged)
        } finally {
            staged?.close()
            importer.close()
            awaitCondition(WARMUP_TIMEOUT_MS) { partialFiles(directoryName).isEmpty() }
        }
    }

    private fun assertFreshRemoteWorkerWasUsed() {
        val callers = ClipboardExternalMediaTestSource.callerProcesses()
        assertTrue(callers.blockingPid > 0)
        assertTrue(callers.healthyPid > 0)
        assertEquals(context.applicationInfo.uid, callers.blockingUid)
        assertEquals(context.applicationInfo.uid, callers.healthyUid)
        assertNotEquals(Process.myPid(), callers.blockingPid)
        assertNotEquals(Process.myPid(), callers.healthyPid)
        assertNotEquals(callers.blockingPid, callers.healthyPid)
    }

    private fun awaitCondition(
        timeoutMs: Long = AWAIT_MS,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError("Condition was not met before the timeout.")
            }
            Thread.sleep(POLL_MS)
        }
    }

    companion object {
        private const val IMPORT_TIMEOUT_MS = 250L
        private const val PARTIAL_STREAM_TIMEOUT_MS = 1_000L
        private const val LONG_IMPORT_TIMEOUT_MS = 10_000L
        private const val WARMUP_TIMEOUT_MS = 15_000L
        private const val AWAIT_MS = 15_000L
        private const val MAX_TIMEOUT_RETURN_MS = 3_000L
        private const val MAX_REJECTION_RETURN_MS = 1_000L
        private const val MAX_TEST_BYTES = 1_024L
        private const val POLL_MS = 10L
    }
}
