/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

internal enum class AutocorrectPluginDiagnosticOperation {
    SUGGESTION,
    FINISH_SESSION,
    REMOVE_CANDIDATE,
    PLUGIN_UI,
    USER_DICTIONARY,
    UNKNOWN_REPLY,
}

internal enum class AutocorrectPluginDiagnosticState {
    STARTED,
    FAILED,
    REJECTED,
    CONNECTED,
    DISCONNECTED,
}

internal enum class AutocorrectPluginDiagnosticError {
    NONE,
    BIND_REJECTED,
    NULL_BINDING,
    SERVICE_DISCONNECTED,
    BINDING_DIED,
    DEAD_REMOTE,
    REMOTE_FAILURE,
    STALE_BINDING,
    STALE_SESSION,
    STALE_GENERATION,
    UNAUTHORIZED_SENDER,
    NOT_CONNECTED,
    UNKNOWN_REQUEST,
    SUPERSEDED,
    MALFORMED_MESSAGE,
    INVALID_REQUEST,
}

/**
 * Closed diagnostic event model. None of the event types can hold arbitrary text or exceptions.
 */
internal sealed interface AutocorrectPluginDiagnosticEvent {
    data class Binding(
        val bindingEpoch: Long,
        val state: AutocorrectPluginDiagnosticState,
        val error: AutocorrectPluginDiagnosticError,
    ) : AutocorrectPluginDiagnosticEvent

    data class Operation(
        val bindingEpoch: Long,
        val requestId: Long,
        val operation: AutocorrectPluginDiagnosticOperation,
        val state: AutocorrectPluginDiagnosticState,
        val error: AutocorrectPluginDiagnosticError,
    ) : AutocorrectPluginDiagnosticEvent

    data class ReplyRejected(
        val bindingEpoch: Long,
        val operation: AutocorrectPluginDiagnosticOperation,
        val error: AutocorrectPluginDiagnosticError,
    ) : AutocorrectPluginDiagnosticEvent
}

internal data class AutocorrectPluginDiagnosticRecord(val sequence: Long, val event: AutocorrectPluginDiagnosticEvent)

internal data class AutocorrectPluginDiagnosticSnapshot(
    val records: List<AutocorrectPluginDiagnosticRecord>,
    val droppedRecordCount: Long,
)

/** Bounded bind and reply history for debugging, with host IDs and closed failure categories only. */
internal class AutocorrectPluginDiagnostics(private val capacity: Int = DEFAULT_CAPACITY) {
    private val records = ArrayDeque<AutocorrectPluginDiagnosticRecord>(capacity)
    private var nextSequence = 1L
    private var droppedRecordCount = 0L

    init {
        require(capacity > 0) { "Diagnostic capacity must be positive" }
    }

    @Synchronized
    fun record(event: AutocorrectPluginDiagnosticEvent) {
        if (records.size == capacity) {
            records.removeFirst()
            droppedRecordCount++
        }
        records.addLast(
            AutocorrectPluginDiagnosticRecord(
                sequence = nextSequence++,
                event = event,
            ),
        )
    }

    @Synchronized
    fun snapshot() = AutocorrectPluginDiagnosticSnapshot(
        records = records.toList(),
        droppedRecordCount = droppedRecordCount,
    )

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
