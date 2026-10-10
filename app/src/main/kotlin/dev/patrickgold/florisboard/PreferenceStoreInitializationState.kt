/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
