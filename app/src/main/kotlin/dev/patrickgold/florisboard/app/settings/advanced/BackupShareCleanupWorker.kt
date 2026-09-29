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
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistent expiry wake-up. Work input and names contain no archive location or contents. */
internal object BackupShareCleanupScheduler {
    private const val WORK_NAME_PREFIX = "backup-share-lease-expiry-"
    private const val EXPIRY_AT_MILLIS = "expiry-at-millis"

    fun scheduleExpiry(context: Context, leaseId: String, expiresAtMillis: Long) {
        require(UUID.fromString(leaseId).toString() == leaseId) { "Invalid lease ID" }
        require(expiresAtMillis > 0L) { "Invalid expiry time" }
        val delayMillis = (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<BackupShareCleanupWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(EXPIRY_AT_MILLIS to expiresAtMillis))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10L, TimeUnit.MINUTES)
            .build()
        // The store calls this on IO before publishing a URI. Await the durable
        // enqueue result so a scheduling failure can abort that publication.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME_PREFIX + leaseId,
            ExistingWorkPolicy.REPLACE,
            request,
        ).result.get()
    }

    internal fun scheduledExpiry(input: androidx.work.Data): Long =
        input.getLong(EXPIRY_AT_MILLIS, 0L)
}

/** Prunes all expired app-owned leases, including those whose earlier wake-up was missed. */
internal class BackupShareCleanupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            BackupShareLeaseStore(applicationContext).pruneExpired()
            // A wall-clock rollback can make WorkManager's elapsed delay arrive early.
            // Retrying preserves a wake-up rather than abandoning this unexpired lease.
            if (System.currentTimeMillis() < BackupShareCleanupScheduler.scheduledExpiry(inputData)) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            try {
                flogError { "Backup share cleanup failed: failureClass=${error.javaClass.simpleName}" }
            } catch (_: Exception) {
                // A diagnostic failure must not suppress the cleanup retry.
            }
            Result.retry()
        }
    }
}
