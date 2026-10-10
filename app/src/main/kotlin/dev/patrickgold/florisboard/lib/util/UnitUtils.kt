/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.util

import java.util.Locale

object UnitUtils {
    private const val KiB = 1024L
    private const val MiB = 1024L * KiB
    private const val GiB = 1024L * MiB

    fun formatMemorySize(sizeBytes: Long): String {
        return when {
            sizeBytes >= GiB -> String.format(Locale.ROOT, "%.2f GiB", sizeBytes.toDouble() / GiB)
            sizeBytes >= MiB -> String.format(Locale.ROOT, "%.2f MiB", sizeBytes.toDouble() / MiB)
            sizeBytes >= KiB -> String.format(Locale.ROOT, "%.2f KiB", sizeBytes.toDouble() / KiB)
            sizeBytes == 1L -> "1 byte"
            else -> "$sizeBytes bytes"
        }
    }
}
