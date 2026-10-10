/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Creates a provider once, serializing attempts and allowing retry after failure. */
internal class NlpProviderLifecycle {
    private val guard = Mutex()
    private var isAlive = false

    suspend fun createIfNecessary(create: suspend () -> Unit) = guard.withLock {
        if (!isAlive) {
            create()
            isAlive = true
        }
    }
}
