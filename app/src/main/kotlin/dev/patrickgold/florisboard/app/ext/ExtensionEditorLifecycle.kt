/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.ext

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Transfers [resource] to the caller only after [initialize] succeeds. Any failure, including
 * cancellation, completes [cleanup] before the original failure is rethrown.
 */
internal suspend fun <R, T> initializeOwnedEditorResource(
    resource: R,
    initialize: suspend (R) -> T,
    cleanup: suspend (R) -> Unit,
): T {
    val result = runCatching { initialize(resource) }
    if (result.isFailure) {
        // Initialization owns the resource and must preserve its original failure.
        runCatching {
            withContext(NonCancellable) {
                cleanup(resource)
            }
        }
    }
    return result.getOrThrow()
}
