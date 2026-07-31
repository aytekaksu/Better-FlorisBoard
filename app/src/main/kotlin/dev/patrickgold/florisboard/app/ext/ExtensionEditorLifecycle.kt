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
