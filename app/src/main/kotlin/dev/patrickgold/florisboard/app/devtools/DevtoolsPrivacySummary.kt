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

package dev.patrickgold.florisboard.app.devtools

internal enum class ClipboardDebugType {
    NONE,
    TEXT,
    IMAGE,
    VIDEO,
}

internal fun clipboardDebugLines(
    type: ClipboardDebugType = ClipboardDebugType.NONE,
    isPinned: Boolean = false,
    isSensitive: Boolean = false,
    isRemoteDevice: Boolean = false,
    mimeTypes: Collection<*> = emptyList<Any>(),
) = listOf(
    "Type: ${type.name.lowercase()}",
    "Pinned: $isPinned | Sensitive: $isSensitive | Remote: $isRemoteDevice",
    "MIME types: ${mimeTypes.size}",
)

internal enum class EditorRegionState {
    UNAVAILABLE,
    EMPTY,
    PRESENT,
}

internal fun editorDebugLines(
    cachedText: CharSequence,
    textBeforeSelection: CharSequence,
    selectedText: CharSequence,
    textAfterSelection: CharSequence,
    composingText: CharSequence,
    currentWordText: CharSequence,
    selectionIsValid: Boolean,
    composingIsValid: Boolean,
    currentWordIsValid: Boolean,
    lastCommitKnown: Boolean,
) = listOf(
    "Cached text length: ${cachedText.length}",
    "Before/selected/after lengths: ${textBeforeSelection.length}/${selectedText.length}/${textAfterSelection.length}",
    "Selection: ${regionState(selectionIsValid, selectedText.length)}",
    "Composing: ${regionState(composingIsValid, composingText.length)} | length: ${composingText.length}",
    "Current word: ${regionState(currentWordIsValid, currentWordText.length)} | length: ${currentWordText.length}",
    "Last commit known: $lastCommitKnown",
)

internal fun failureClassName(failure: Throwable?): String = failure?.javaClass?.simpleName?.takeIf {
    it.isNotBlank()
} ?: "UnknownFailure"

private fun regionState(isValid: Boolean, length: Int): String = when {
    !isValid -> EditorRegionState.UNAVAILABLE
    length == 0 -> EditorRegionState.EMPTY
    else -> EditorRegionState.PRESENT
}.name.lowercase()
