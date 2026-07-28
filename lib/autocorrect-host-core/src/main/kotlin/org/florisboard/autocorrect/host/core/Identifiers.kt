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
