/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.android

import android.os.FileObserver
import java.io.File

/**
 * Create a new file observer for a certain file or directory. Monitoring does not start on creation! You must call
 * startWatching() before you will receive events.
 *
 * @param file The file or directory to monitor.
 * @param mask The event or events (added together) to watch for.
 * @param onEvent The event handler which gets executed for any new events.
 *
 * @see android.os.FileObserver
 */
fun FileObserver(file: File, mask: Int, onEvent: (event: Int, path: String?) -> Unit): FileObserver {
    return if (AndroidVersion.ATLEAST_API29_Q) {
        object : FileObserver(file, mask) {
            override fun onEvent(event: Int, path: String?) = onEvent(event, path)
        }
    } else {
        @Suppress("DEPRECATION")
        object : FileObserver(file.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) = onEvent(event, path)
        }
    }
}
