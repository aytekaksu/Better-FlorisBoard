/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.util

import dev.patrickgold.jetpref.datastore.model.LocalTime
import java.time.Instant
import java.time.format.DateTimeFormatter

object TimeUtils {
    fun currentUtcTimestamp(): CharSequence {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    }

    val LocalTime.javaLocalTime: java.time.LocalTime
        get() = java.time.LocalTime.of(hour, minute)
}
