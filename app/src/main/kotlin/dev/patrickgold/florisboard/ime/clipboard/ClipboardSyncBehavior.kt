/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard

enum class ClipboardSyncBehavior(val shouldSyncSet: Boolean, val shouldSyncClear: Boolean) {
    NO_EVENTS(shouldSyncSet = false, shouldSyncClear = false),
    ONLY_CLEAR_EVENTS(shouldSyncSet = false, shouldSyncClear = true),
    ONLY_SET_EVENTS(shouldSyncSet = true, shouldSyncClear = false),
    ALL_EVENTS(shouldSyncSet = true, shouldSyncClear = true);
}

internal fun resolveSyncToFlorisBehavior(
    useInternalClipboard: Boolean,
    configuredBehavior: ClipboardSyncBehavior,
): ClipboardSyncBehavior {
    return if (useInternalClipboard) configuredBehavior else ClipboardSyncBehavior.ALL_EVENTS
}

internal val ClipboardSyncBehavior.requiresSystemClipboardRead: Boolean
    get() = shouldSyncSet || shouldSyncClear
