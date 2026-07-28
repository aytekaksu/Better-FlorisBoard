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

/**
 * The fields which make one provider session observably different from another.
 *
 * Keeping this model Android-free lets the app translate `EditorInfo` and subtype data at the
 * boundary, while the core decides whether an existing session is reusable.
 */
data class SessionConfiguration(
    val primaryLanguageTag: String,
    val secondaryLanguageTags: List<String> = emptyList(),
    val inputType: Int,
    val capsMode: Int,
    val allowPersonalizedLearning: Boolean,
    val editorFlags: Int,
    val preferredEmojiSkinToneModifier: Int,
) {
    init {
        require(primaryLanguageTag.isNotBlank()) { "Primary language tag must not be blank" }
    }
}

data class BindingLease(val providerId: ProviderId, val epoch: BindingEpoch)

data class SessionLease(
    val providerId: ProviderId,
    val epoch: BindingEpoch,
    val sessionId: SessionId,
    val editorGeneration: EditorGeneration,
)

data class RequestLease(
    val providerId: ProviderId,
    val epoch: BindingEpoch,
    val sessionId: SessionId,
    val requestId: RequestId,
    val editorGeneration: EditorGeneration,
)

data class SessionFinishLease(
    val providerId: ProviderId,
    val epoch: BindingEpoch,
    val sessionId: SessionId,
    val finalRequestId: RequestId,
)

data class CircuitPolicy(val failureThreshold: Int = 3, val recoveryDelayMillis: Long = 30_000L) {
    init {
        require(failureThreshold > 0) { "Failure threshold must be positive" }
        require(recoveryDelayMillis > 0L) { "Recovery delay must be positive" }
    }
}

enum class ProviderFailureKind {
    BIND_REJECTED,
    NULL_BINDING,
    BINDING_DIED,
    SERVICE_DISCONNECTED,
    DEAD_REMOTE,
    SEND_FAILED,
    TIMEOUT,
    MALFORMED_REPLY,
    PROVIDER_ERROR,
}

enum class ConnectionLossKind(val preservesSession: Boolean) {
    SERVICE_DISCONNECTED(true),
    DEAD_REMOTE(true),
    BINDING_DIED(false),
    NULL_BINDING(false),
}

enum class RequestCancellationReason {
    SUPERSEDED,
    CALLER_CANCELLED,
    SESSION_FINISHED,
    EDITOR_INVALIDATED,
    PROVIDER_CHANGED,
    PROVIDER_LOST,
    HOST_DESTROYED,
}

sealed interface RequestOutcome {
    data object Success : RequestOutcome

    data class Failure(val kind: ProviderFailureKind) : RequestOutcome
}

enum class RetiredRequestReason {
    COMPLETED,
    FAILED,
    SUPERSEDED,
    CANCELLED,
    SESSION_FINISHED,
    EDITOR_INVALIDATED,
    PROVIDER_CHANGED,
    PROVIDER_LOST,
    HOST_DESTROYED,
}
