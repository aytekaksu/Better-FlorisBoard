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

import dev.patrickgold.florisboard.BuildConfig

/**
 * Small, versioned IPC surface between the app and its disposable import
 * process. Values crossing this boundary are validated again on both sides.
 */
internal object ClipboardExternalMediaImportWorkerContract {
    const val AUTHORITY =
        "${BuildConfig.APPLICATION_ID}.provider.clipboard-import-worker"
    const val PROCESS_SUFFIX = ":clipboard_import"

    const val METHOD_BEGIN = "begin"
    const val METHOD_STAGE = "stage"
    const val METHOD_POLL = "poll"
    const val METHOD_CANCEL = "cancel"

    const val KEY_PROTOCOL_VERSION = "protocol_version"
    const val KEY_STATUS = "status"
    const val KEY_GENERATION = "generation"
    const val KEY_DEADLINE_ELAPSED_REALTIME_MS = "deadline_elapsed_realtime_ms"
    const val KEY_SOURCE_URI = "source_uri"
    const val KEY_DESTINATION = "destination"
    const val KEY_MAXIMUM_BYTES = "maximum_bytes"
    const val KEY_BYTE_COUNT = "byte_count"
    const val KEY_DISPLAY_NAME = "display_name"
    const val KEY_SOURCE_MIME_TYPE = "source_mime_type"

    const val PROTOCOL_VERSION = 1
    const val STATUS_ACCEPTED = 1
    const val STATUS_BUSY = 2
    const val STATUS_SUCCESS = 3
    const val STATUS_REJECTED = 4

    const val MAX_SOURCE_URI_LENGTH = 8_192
    const val MAX_TIMEOUT_MS = 30_000L
    const val CANCEL_GRACE_MS = 250L
    const val WATCHDOG_GRACE_MS = 1_000L

    val TOKEN = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
    )
}
