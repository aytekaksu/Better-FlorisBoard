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

package dev.patrickgold.florisboard.ime.nlp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes a provider's create/destroy boundary and changes state only after the operation
 * succeeds. A failed create remains retryable; a failed destroy remains eligible for cleanup.
 */
internal class NlpProviderLifecycle {
    private val guard = Mutex()
    private var isAlive = false

    suspend fun createIfNecessary(create: suspend () -> Unit) = guard.withLock {
        if (!isAlive) {
            create()
            isAlive = true
        }
    }

    suspend fun destroyIfNecessary(destroy: suspend () -> Unit) = guard.withLock {
        if (isAlive) {
            destroy()
            isAlive = false
        }
    }
}
