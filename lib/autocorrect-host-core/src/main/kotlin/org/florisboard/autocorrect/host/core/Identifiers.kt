/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.autocorrect.host.core

@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "Provider ID must not be blank" }
    }

    override fun toString() = value
}

@JvmInline
value class SessionId(val value: Long) {
    init {
        require(value > 0L) { "Session ID must be positive" }
    }
}

@JvmInline
value class RequestId(val value: Long) {
    init {
        require(value > 0L) { "Request ID must be positive" }
    }
}

@JvmInline
value class BindingEpoch(val value: Long) {
    init {
        require(value > 0L) { "Binding epoch must be positive" }
    }
}

@JvmInline
value class EditorGeneration(val value: Long) {
    init {
        require(value >= 0L) { "Editor generation must not be negative" }
    }

    internal fun next() = EditorGeneration(Math.addExact(value, 1L))

    companion object {
        val Initial = EditorGeneration(0L)
    }
}

@JvmInline
value class DiscoveryRevision(val value: Long) {
    init {
        require(value > 0L) { "Discovery revision must be positive" }
    }
}

@JvmInline
value class MonotonicMillis(val value: Long) {
    init {
        require(value >= 0L) { "Monotonic time must not be negative" }
    }

    internal fun plus(durationMillis: Long) = MonotonicMillis(Math.addExact(value, durationMillis))
}
