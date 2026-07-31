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

package dev.patrickgold.florisboard

internal enum class PreferenceStoreInitializationState {
    LOADING,
    READY,
    FAILED,
}

/**
 * Gates app UI which cannot render safely before preferences and core runtime
 * setup finish. The direct-boot IME deliberately remains usable with its
 * initial preference model while credential-protected storage is locked.
 */
internal enum class ApplicationBootstrapState {
    LOADING,
    READY,
    FAILED,
    ;

    val keepsSplashVisible: Boolean
        get() = this == LOADING

    val canRenderPreferenceBackedUi: Boolean
        get() = this == READY

    val isTerminalFailure: Boolean
        get() = this == FAILED
}

/**
 * Delivers one terminal failure to a dependency whether it is registered
 * before or after that failure. Registration and failure may race.
 */
internal class TerminalFailureLatch<T : Any>(
    private val failTarget: (T) -> Unit,
) {
    private val lock = Any()
    private var failed = false
    private var target: T? = null

    fun register(target: T): T {
        val shouldFail = synchronized(lock) {
            check(this.target == null) { "A target is already registered." }
            this.target = target
            failed
        }
        if (shouldFail) {
            failTarget(target)
        }
        return target
    }

    fun fail() {
        val targetToFail = synchronized(lock) {
            if (failed) {
                null
            } else {
                failed = true
                target
            }
        }
        targetToFail?.let(failTarget)
    }
}
