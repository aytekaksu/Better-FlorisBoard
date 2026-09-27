/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.editor

import org.florisboard.lib.kotlin.safeSubstring

/**
 * Editor text around the cursor or selection. The snapshot may cover only part of the document.
 *
 * @property text Full editor text or the available snapshot window.
 * @property offset Start of [text] in the editor, or `-1` when unknown.
 * @property localSelection Selection relative to [text], except in a selection-only snapshot where it uses editor
 *     coordinates.
 * @property localComposing Composing range relative to [text].
 * @property localCurrentWord Current word relative to [text], often matching [localComposing].
 */
data class EditorContent(
    val text: String,
    val offset: Int,
    val localSelection: EditorRange,
    val localComposing: EditorRange,
    val localCurrentWord: EditorRange,
) {
    /** Available text before the selection; it may be partial or empty, including in raw editors. */
    val textBeforeSelection: String
        get() = if (localSelection.isValid) text.safeSubstring(0, localSelection.start) else ""

    /** The selected text, or empty when the selection is invalid or outside this snapshot. */
    val selectedText: String
        get() = if (localSelection.isValid) text.safeSubstring(localSelection.start, localSelection.end) else ""

    /** Available text after the selection; it may be partial or empty, including in raw editors. */
    val textAfterSelection: String
        get() = if (localSelection.isValid) text.safeSubstring(localSelection.end) else ""

    /** Selection in editor coordinates; adds [offset] when it is positive. */
    val selection: EditorRange
        get() = if (offset > 0) localSelection.translatedBy(offset) else localSelection

    /** Composing range in editor coordinates; it may be invalid when composing is disabled. */
    val composing: EditorRange
        get() = if (offset > 0) localComposing.translatedBy(offset) else localComposing

    /** Composing text, or empty when its range is invalid or outside this snapshot. */
    val composingText: String
        get() = if (localComposing.isValid) text.safeSubstring(localComposing.start, localComposing.end) else ""

    /** Current-word range in editor coordinates; adds [offset] when it is positive. */
    val currentWord: EditorRange
        get() = if (offset > 0) localCurrentWord.translatedBy(offset) else localCurrentWord

    /** Current-word text, or empty when its range is invalid or outside this snapshot. */
    val currentWordText: String
        get() = if (localCurrentWord.isValid) text.safeSubstring(localCurrentWord.start, localCurrentWord.end) else ""

    val safeEditorBounds: EditorRange
        get() = if (offset >= 0) EditorRange(0, offset + text.length) else EditorRange(0, 0)

    companion object {
        /** No usable editor snapshot, as with raw input or a failed editor read. */
        val Unspecified =
            EditorContent("", -1, EditorRange.Unspecified, EditorRange.Unspecified, EditorRange.Unspecified)

        /** Selection-only snapshot for mass-selection handling; [selection] uses editor coordinates. */
        fun selectionOnly(selection: EditorRange) =
            EditorContent("", -1, selection, EditorRange.Unspecified, EditorRange.Unspecified)
    }
}
