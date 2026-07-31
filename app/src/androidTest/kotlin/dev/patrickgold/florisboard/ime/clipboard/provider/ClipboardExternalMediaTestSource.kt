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

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Controls the external-UID Java provider through Binder. No provider state is
 * shared with the instrumentation classloader.
 */
internal object ClipboardExternalMediaTestSource {
    const val AUTHORITY = "dev.patrickgold.florisboard.test.clipboard-source"
    const val CONTROL_AUTHORITY = "dev.patrickgold.florisboard.test.clipboard-control"

    val healthyUri: Uri
        get() = sourceUri("healthy")
    val emptyUri: Uri
        get() = sourceUri("empty")
    val svgUri: Uri
        get() = sourceUri("svg")
    val orientedJpegUri: Uri
        get() = sourceUri("oriented-jpeg")
    val blockingUri: Uri
        get() = sourceUri("blocking")
    val delayedUri: Uri
        get() = sourceUri("delayed")
    val cancellationAwareUri: Uri
        get() = sourceUri("cancellation-aware")
    val prefixThenBlockUri: Uri
        get() = sourceUri("prefix-then-block")
    val blockingMimeTypeUri: Uri
        get() = sourceUri("blocking-mime-type")
    val blockingDisplayNameUri: Uri
        get() = sourceUri("blocking-display-name")
    val cancellationAwareDisplayNameUri: Uri
        get() = sourceUri("cancellation-aware-display-name")

    val svgBytes: ByteArray =
        """<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><rect width="2" height="2"/></svg>"""
            .encodeToByteArray()
    val orientedJpegBytes: ByteArray
        get() = ClipboardExternalMediaTestProvider.orientedJpegBytes()

    fun reset() {
        val nextGeneration = call(METHOD_RESET).getLong(KEY_FIXTURE_GENERATION)
        check(nextGeneration > 0L) { "Clipboard test generation was unavailable." }
        fixtureGeneration = nextGeneration
    }

    fun grantReadAccess(vararg requestedUris: Uri) {
        urisOrAll(requestedUris).forEach { uri ->
            call(METHOD_GRANT, uri.toString())
        }
    }

    fun revokeReadAccess(vararg requestedUris: Uri) {
        urisOrAll(requestedUris).forEach { uri ->
            call(METHOD_REVOKE, uri.toString())
        }
    }

    fun awaitBlockingOpen(timeoutMs: Long): Boolean =
        awaitStatus(timeoutMs, KEY_BLOCKING_ENTERED)

    fun awaitPrefixWritten(timeoutMs: Long): Boolean =
        awaitStatus(timeoutMs, KEY_PREFIX_WRITTEN)

    fun awaitPrefixCompleted(timeoutMs: Long): Boolean =
        awaitStatus(timeoutMs, KEY_PREFIX_COMPLETED)

    fun delayedOpenIsActive(): Boolean = status().getBoolean(KEY_DELAYED_ACTIVE)

    fun awaitCancellation(timeoutMs: Long): Boolean =
        awaitStatus(timeoutMs, KEY_CANCELLATION_OBSERVED)

    fun releaseBlockingOpen() {
        call(METHOD_RELEASE)
    }

    fun openCount(): Int = status().getInt(KEY_OPEN_COUNT)

    fun mimeTypeQueryCount(): Int = status().getInt(KEY_MIME_TYPE_QUERY_COUNT)

    fun displayNameQueryCount(): Int = status().getInt(KEY_DISPLAY_NAME_QUERY_COUNT)

    fun callerProcesses(): CallerProcesses {
        val status = status()
        return CallerProcesses(
            blockingPid = status.getInt(KEY_BLOCKING_CALLER_PID, -1),
            blockingUid = status.getInt(KEY_BLOCKING_CALLER_UID, -1),
            healthyPid = status.getInt(KEY_HEALTHY_CALLER_PID, -1),
            healthyUid = status.getInt(KEY_HEALTHY_CALLER_UID, -1),
        )
    }

    fun externalProcessCanAcquireImportWorker(): Boolean =
        call(METHOD_PROBE_TARGET_IMPORT_WORKER)
            .getBoolean(KEY_IMPORT_WORKER_ACQUIRED)

    fun probeTargetMedia(uri: Uri): TargetMediaProbe {
        val result = call(METHOD_PROBE_TARGET_MEDIA, uri.toString())
        return TargetMediaProbe(
            queryVisible = result.getBoolean(KEY_QUERY_VISIBLE),
            typeVisible = result.getBoolean(KEY_TYPE_VISIBLE),
            streamTypesVisible = result.getBoolean(KEY_STREAM_TYPES_VISIBLE),
            openVisible = result.getBoolean(KEY_OPEN_VISIBLE),
            typedOpenVisible = result.getBoolean(KEY_TYPED_OPEN_VISIBLE),
            typedOpenReadable = result.getBoolean(KEY_TYPED_OPEN_READABLE),
            typedMismatchRejected = result.getBoolean(KEY_TYPED_MISMATCH_REJECTED),
            typedOptionsRejected = result.getBoolean(KEY_TYPED_OPTIONS_REJECTED),
            typedCancellationObserved = result.getBoolean(KEY_TYPED_CANCELLATION_OBSERVED),
        )
    }

    fun probeColdTargetTypeBeforeGrant(uri: Uri): String? =
        probeColdTargetType(COLD_TYPE_BEFORE_URI, uri)

    fun probeColdTargetTypeWhileGranted(uri: Uri): String? =
        probeColdTargetType(COLD_TYPE_GRANTED_URI, uri)

    fun probeColdTargetTypeAfterRevoke(uri: Uri): String? =
        probeColdTargetType(COLD_TYPE_REVOKED_URI, uri)

    private fun awaitStatus(
        timeoutMs: Long,
        key: String,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (status().getBoolean(key)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(POLL_MS)
        } while (true)
    }

    private fun status(): Bundle = call(METHOD_STATUS)

    private fun call(
        method: String,
        arg: String? = null,
    ): Bundle = call(CONTROL_URI, method, arg)

    private fun call(
        controlUri: Uri,
        method: String,
        arg: String? = null,
    ): Bundle {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        return checkNotNull(resolver.call(controlUri, method, arg, null)) {
            "Clipboard test provider control was unavailable."
        }
    }

    private fun probeColdTargetType(controlUri: Uri, uri: Uri): String? =
        call(controlUri, METHOD_PROBE_COLD_TARGET_TYPE, uri.toString())
            .getString(KEY_COLD_TYPE_VALUE)

    private fun urisOrAll(requestedUris: Array<out Uri>): Collection<Uri> =
        if (requestedUris.isEmpty()) {
            sourceUris()
        } else {
            requestedUris.asList().also { uris ->
                val currentUris = sourceUris()
                require(uris.all(currentUris::contains)) {
                    "Only fixture-owned URIs may be granted."
                }
            }
        }

    private fun sourceUri(path: String): Uri {
        val generation = fixtureGeneration
        check(generation > 0L) { "Reset the clipboard test source before using it." }
        return Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(path)
            .appendQueryParameter(GENERATION_PARAMETER, generation.toString())
            .build()
    }

    private fun sourceUris(): List<Uri> = listOf(
        healthyUri,
        emptyUri,
        svgUri,
        orientedJpegUri,
        blockingUri,
        delayedUri,
        cancellationAwareUri,
        prefixThenBlockUri,
        blockingMimeTypeUri,
        blockingDisplayNameUri,
        cancellationAwareDisplayNameUri,
    )

    private const val METHOD_RESET = "reset"
    private const val METHOD_RELEASE = "release"
    private const val METHOD_STATUS = "status"
    private const val METHOD_GRANT = "grant"
    private const val METHOD_REVOKE = "revoke"
    private const val METHOD_PROBE_TARGET_MEDIA = "probe_target_media"
    private const val METHOD_PROBE_TARGET_IMPORT_WORKER = "probe_target_import_worker"
    private const val METHOD_PROBE_COLD_TARGET_TYPE = "probe_cold_target_type"
    private const val GENERATION_PARAMETER = "generation"
    private const val KEY_FIXTURE_GENERATION = "fixture_generation"
    private const val KEY_BLOCKING_ENTERED = "blocking_entered"
    private const val KEY_PREFIX_WRITTEN = "prefix_written"
    private const val KEY_PREFIX_COMPLETED = "prefix_completed"
    private const val KEY_DELAYED_ACTIVE = "delayed_active"
    private const val KEY_CANCELLATION_OBSERVED = "cancellation_observed"
    private const val KEY_OPEN_COUNT = "open_count"
    private const val KEY_MIME_TYPE_QUERY_COUNT = "mime_type_query_count"
    private const val KEY_DISPLAY_NAME_QUERY_COUNT = "display_name_query_count"
    private const val KEY_BLOCKING_CALLER_PID = "blocking_caller_pid"
    private const val KEY_BLOCKING_CALLER_UID = "blocking_caller_uid"
    private const val KEY_HEALTHY_CALLER_PID = "healthy_caller_pid"
    private const val KEY_HEALTHY_CALLER_UID = "healthy_caller_uid"
    private const val KEY_QUERY_VISIBLE = "query_visible"
    private const val KEY_TYPE_VISIBLE = "type_visible"
    private const val KEY_STREAM_TYPES_VISIBLE = "stream_types_visible"
    private const val KEY_OPEN_VISIBLE = "open_visible"
    private const val KEY_TYPED_OPEN_VISIBLE = "typed_open_visible"
    private const val KEY_TYPED_OPEN_READABLE = "typed_open_readable"
    private const val KEY_TYPED_MISMATCH_REJECTED = "typed_mismatch_rejected"
    private const val KEY_TYPED_OPTIONS_REJECTED = "typed_options_rejected"
    private const val KEY_TYPED_CANCELLATION_OBSERVED = "typed_cancellation_observed"
    private const val KEY_COLD_TYPE_VALUE = "cold_type_value"
    private const val KEY_IMPORT_WORKER_ACQUIRED = "import_worker_acquired"
    private const val POLL_MS = 10L
    private val CONTROL_URI = Uri.parse("content://$CONTROL_AUTHORITY")
    private val COLD_TYPE_BEFORE_URI =
        Uri.parse("content://dev.patrickgold.florisboard.test.clipboard-type-before")
    private val COLD_TYPE_GRANTED_URI =
        Uri.parse("content://dev.patrickgold.florisboard.test.clipboard-type-granted")
    private val COLD_TYPE_REVOKED_URI =
        Uri.parse("content://dev.patrickgold.florisboard.test.clipboard-type-revoked")
    @Volatile
    private var fixtureGeneration = 0L
}

internal data class CallerProcesses(
    val blockingPid: Int,
    val blockingUid: Int,
    val healthyPid: Int,
    val healthyUid: Int,
)

internal data class TargetMediaProbe(
    val queryVisible: Boolean,
    val typeVisible: Boolean,
    val streamTypesVisible: Boolean,
    val openVisible: Boolean,
    val typedOpenVisible: Boolean,
    val typedOpenReadable: Boolean,
    val typedMismatchRejected: Boolean,
    val typedOptionsRejected: Boolean,
    val typedCancellationObserved: Boolean,
) {
    val nothingVisible: Boolean
        get() =
            !queryVisible &&
                !typeVisible &&
                !streamTypesVisible &&
                !openVisible &&
                !typedOpenVisible &&
                !typedOpenReadable

    val everythingVisible: Boolean
        get() =
            queryVisible &&
                typeVisible &&
                streamTypesVisible &&
                openVisible &&
                typedOpenVisible &&
                typedOpenReadable &&
                typedMismatchRejected &&
                typedOptionsRejected &&
                typedCancellationObserved
}
