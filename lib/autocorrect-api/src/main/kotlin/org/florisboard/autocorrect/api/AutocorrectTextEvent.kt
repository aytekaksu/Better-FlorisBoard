/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.autocorrect.api

import android.os.Bundle

enum class AutocorrectTextEventKind {
    COMMIT_TYPED,
    COMMIT_GESTURE,
    DELETE_BACKWARD,
    DELETE_FORWARD,
}

/**
 * A word committed without selecting a provider candidate, or a word the user began deleting.
 * Providers can use these events for optional on-device personalization; surrounding context is
 * available from the latest request in the same session.
 */
data class AutocorrectTextEvent(
    val sessionId: Long,
    val text: String,
    val kind: AutocorrectTextEventKind,
) {
    fun toBundle() = Bundle().apply {
        putLong(Keys.SESSION_ID, sessionId)
        putString(
            Keys.TEXT,
            text.takeWireChars(AutocorrectPluginContract.MAX_CANDIDATE_TEXT_CHARS),
        )
        putString(Keys.KIND, kind.name)
    }

    companion object {
        internal fun fromBundle(bundle: Bundle): AutocorrectTextEvent? {
            val kind = enumValues<AutocorrectTextEventKind>()
                .firstOrNull { it.name == bundle.getString(Keys.KIND) }
                ?: AutocorrectTextEventKind.COMMIT_TYPED
            val text = bundle.getString(Keys.TEXT).orEmpty()
                .takeWireChars(AutocorrectPluginContract.MAX_CANDIDATE_TEXT_CHARS)
            if (
                text.isBlank() &&
                (kind == AutocorrectTextEventKind.COMMIT_TYPED ||
                    kind == AutocorrectTextEventKind.COMMIT_GESTURE)
            ) {
                return null
            }
            return AutocorrectTextEvent(
                sessionId = bundle.getLong(Keys.SESSION_ID),
                text = text,
                kind = kind,
            )
        }
    }
}
