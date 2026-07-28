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

package dev.patrickgold.florisboard.test.editor

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import kotlin.math.max
import kotlin.math.min

/**
 * Stateful [InputConnection] test double with deterministic UTF-16 editor semantics.
 *
 * It deliberately has no Android view, looper, clock, or global state. Tests decide when delayed
 * selection updates are delivered through [flushSelectionUpdates], which keeps suites fast and
 * parallel-friendly.
 */
class DeterministicInputConnection(
    initialText: String = "",
    initialSelection: TextRange = TextRange.cursor(initialText.length),
    val selectionUpdateMode: SelectionUpdateMode = SelectionUpdateMode.IMMEDIATE,
    private val onSelectionUpdate: (SelectionUpdate) -> Unit = {},
) : InputConnection {
    enum class SelectionUpdateMode {
        IMMEDIATE,
        DELAYED,
    }

    enum class Operation {
        GET_TEXT_BEFORE_CURSOR,
        GET_TEXT_AFTER_CURSOR,
        GET_SELECTED_TEXT,
        GET_CURSOR_CAPS_MODE,
        GET_EXTRACTED_TEXT,
        DELETE_SURROUNDING_TEXT,
        DELETE_SURROUNDING_CODE_POINTS,
        SET_COMPOSING_TEXT,
        SET_COMPOSING_REGION,
        FINISH_COMPOSING_TEXT,
        COMMIT_TEXT,
        COMMIT_COMPLETION,
        COMMIT_CORRECTION,
        SET_SELECTION,
        PERFORM_EDITOR_ACTION,
        PERFORM_CONTEXT_MENU_ACTION,
        BEGIN_BATCH_EDIT,
        END_BATCH_EDIT,
        SEND_KEY_EVENT,
        CLEAR_META_KEY_STATES,
        REPORT_FULLSCREEN_MODE,
        PERFORM_PRIVATE_COMMAND,
        REQUEST_CURSOR_UPDATES,
        COMMIT_CONTENT,
    }

    data class TextRange(val start: Int, val end: Int) {
        val min: Int get() = min(start, end)
        val max: Int get() = max(start, end)

        companion object {
            fun cursor(position: Int) = TextRange(position, position)
        }
    }

    data class State(
        val text: String,
        val selection: TextRange,
        val composing: TextRange?,
        val batchDepth: Int,
        val closed: Boolean,
    )

    data class SelectionUpdate(val oldSelection: TextRange, val newSelection: TextRange, val composing: TextRange?)

    data class Call(val operation: Operation, val arguments: List<Any?> = emptyList(), val accepted: Boolean)

    private val buffer = StringBuilder(initialText)
    private var selection = requireValidRange(initialSelection)
    private var composing: TextRange? = null
    private var batchDepth = 0
    private var closed = false
    private var batchStartSelection: TextRange? = null
    private var batchStartComposing: TextRange? = null
    private val delayedSelectionUpdates = ArrayDeque<SelectionUpdate>()
    private val nextFailures = mutableMapOf<Operation, Int>()
    private val persistentFailures = mutableSetOf<Operation>()

    private val _calls = mutableListOf<Call>()
    val calls: List<Call> get() = _calls.toList()

    private val _keyEvents = mutableListOf<KeyEvent>()
    val keyEvents: List<KeyEvent> get() = _keyEvents.toList()

    var cursorCapsMode: Int = 0

    val state: State
        get() = State(
            text = buffer.toString(),
            selection = selection,
            composing = composing,
            batchDepth = batchDepth,
            closed = closed,
        )

    val pendingSelectionUpdateCount: Int
        get() = delayedSelectionUpdates.size

    init {
        require(initialSelection.min >= 0 && initialSelection.max <= initialText.length) {
            "Initial selection $initialSelection is outside text length ${initialText.length}"
        }
    }

    /** Causes the next [count] calls of [operation] to return failure without changing state. */
    fun failNext(operation: Operation, count: Int = 1) {
        require(count > 0)
        nextFailures[operation] = nextFailures.getOrDefault(operation, 0) + count
    }

    /** Causes all future calls of [operation] to fail until [allow] is called. */
    fun failAlways(operation: Operation) {
        persistentFailures += operation
    }

    fun allow(operation: Operation) {
        persistentFailures -= operation
        nextFailures -= operation
    }

    /** Delivers at most [limit] queued selection updates in mutation order. */
    fun flushSelectionUpdates(limit: Int = Int.MAX_VALUE): Int {
        require(limit >= 0)
        var delivered = 0
        while (delivered < limit && delayedSelectionUpdates.isNotEmpty()) {
            onSelectionUpdate(delayedSelectionUpdates.removeFirst())
            delivered++
        }
        return delivered
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        if (reject(Operation.GET_TEXT_BEFORE_CURSOR, n, flags) || n < 0) return null
        val cursor = selection.min
        return buffer.substring(max(0, cursor - n), cursor)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        if (reject(Operation.GET_TEXT_AFTER_CURSOR, n, flags) || n < 0) return null
        val cursor = selection.max
        return buffer.substring(cursor, min(buffer.length, cursor + n))
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        if (reject(Operation.GET_SELECTED_TEXT, flags)) return null
        return if (selection.min == selection.max) {
            null
        } else {
            buffer.substring(selection.min, selection.max)
        }
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        if (reject(Operation.GET_CURSOR_CAPS_MODE, reqModes)) return 0
        return cursorCapsMode and reqModes
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        if (reject(Operation.GET_EXTRACTED_TEXT, request, flags)) return null
        return try {
            ExtractedText().also {
                it.text = buffer.toString()
                it.startOffset = 0
                it.partialStartOffset = -1
                it.partialEndOffset = -1
                it.selectionStart = selection.start
                it.selectionEnd = selection.end
            }
        } catch (_: RuntimeException) {
            // Plain JVM Android stubs cannot construct ExtractedText. Robolectric can.
            null
        }
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (
            reject(Operation.DELETE_SURROUNDING_TEXT, beforeLength, afterLength) ||
            beforeLength < 0 ||
            afterLength < 0
        ) {
            return false
        }
        val bounds = deletionAnchor()
        val start = max(0, bounds.first - beforeLength)
        val end = min(buffer.length, bounds.last + 1 + afterLength)
        deleteOutsideAnchor(start, bounds.first, bounds.last + 1, end)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (
            reject(Operation.DELETE_SURROUNDING_CODE_POINTS, beforeLength, afterLength) ||
            beforeLength < 0 ||
            afterLength < 0
        ) {
            return false
        }
        val bounds = deletionAnchor()
        val beforeCount = Character.codePointCount(buffer, 0, bounds.first)
        val afterStart = bounds.last + 1
        val afterCount = Character.codePointCount(buffer, afterStart, buffer.length)
        val start = Character.offsetByCodePoints(
            buffer,
            bounds.first,
            -min(beforeLength, beforeCount),
        )
        val end = Character.offsetByCodePoints(
            buffer,
            afterStart,
            min(afterLength, afterCount),
        )
        deleteOutsideAnchor(start, bounds.first, afterStart, end)
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (reject(Operation.SET_COMPOSING_TEXT, text, newCursorPosition)) return false
        val inserted = text?.toString().orEmpty()
        val target = composing?.normalized() ?: selection.normalized()
        replace(target, inserted, newCursorPosition, keepComposing = inserted.isNotEmpty())
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        if (
            reject(Operation.SET_COMPOSING_REGION, start, end) ||
            start < 0 ||
            end < 0 ||
            start > buffer.length ||
            end > buffer.length
        ) {
            return false
        }
        updateState {
            composing = TextRange(start, end).normalized().takeUnless { it.start == it.end }
        }
        return true
    }

    override fun finishComposingText(): Boolean {
        if (reject(Operation.FINISH_COMPOSING_TEXT)) return false
        updateState { composing = null }
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (reject(Operation.COMMIT_TEXT, text, newCursorPosition)) return false
        val target = composing?.normalized() ?: selection.normalized()
        replace(target, text?.toString().orEmpty(), newCursorPosition, keepComposing = false)
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean =
        acceptNoStateChange(Operation.COMMIT_COMPLETION, text)

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean =
        acceptNoStateChange(Operation.COMMIT_CORRECTION, correctionInfo)

    override fun setSelection(start: Int, end: Int): Boolean {
        if (
            reject(Operation.SET_SELECTION, start, end) ||
            start < 0 ||
            end < 0 ||
            start > buffer.length ||
            end > buffer.length
        ) {
            return false
        }
        updateState { selection = TextRange(start, end) }
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean =
        acceptNoStateChange(Operation.PERFORM_EDITOR_ACTION, editorAction)

    override fun performContextMenuAction(id: Int): Boolean =
        acceptNoStateChange(Operation.PERFORM_CONTEXT_MENU_ACTION, id)

    override fun beginBatchEdit(): Boolean {
        if (reject(Operation.BEGIN_BATCH_EDIT)) return false
        if (batchDepth == 0) {
            batchStartSelection = selection
            batchStartComposing = composing
        }
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (reject(Operation.END_BATCH_EDIT) || batchDepth == 0) return false
        batchDepth--
        if (batchDepth == 0) {
            val oldSelection = requireNotNull(batchStartSelection)
            val oldComposing = batchStartComposing
            batchStartSelection = null
            batchStartComposing = null
            publishUpdateIfChanged(oldSelection, oldComposing)
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (reject(Operation.SEND_KEY_EVENT, event)) return false
        _keyEvents += event
        return true
    }

    override fun clearMetaKeyStates(states: Int): Boolean = acceptNoStateChange(Operation.CLEAR_META_KEY_STATES, states)

    override fun reportFullscreenMode(enabled: Boolean): Boolean =
        acceptNoStateChange(Operation.REPORT_FULLSCREEN_MODE, enabled)

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
        acceptNoStateChange(Operation.PERFORM_PRIVATE_COMMAND, action, data)

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
        acceptNoStateChange(Operation.REQUEST_CURSOR_UPDATES, cursorUpdateMode)

    override fun getHandler(): Handler? = null

    override fun closeConnection() {
        if (closed) return
        if (batchDepth > 0) {
            batchDepth = 0
            val oldSelection = requireNotNull(batchStartSelection)
            val oldComposing = batchStartComposing
            batchStartSelection = null
            batchStartComposing = null
            publishUpdateIfChanged(oldSelection, oldComposing)
        }
        closed = true
    }

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
        acceptNoStateChange(Operation.COMMIT_CONTENT, inputContentInfo, flags, opts)

    private fun replace(range: TextRange, replacement: String, newCursorPosition: Int, keepComposing: Boolean) {
        updateState {
            buffer.replace(range.start, range.end, replacement)
            val cursor = if (newCursorPosition > 0) {
                range.start + replacement.length + newCursorPosition - 1
            } else {
                range.start + newCursorPosition
            }.coerceIn(0, buffer.length)
            selection = TextRange.cursor(cursor)
            composing = if (keepComposing) {
                TextRange(range.start, range.start + replacement.length)
            } else {
                null
            }
        }
    }

    private fun deletionAnchor(): IntRange {
        val composing = composing?.normalized()
        val start = min(selection.min, composing?.start ?: selection.min)
        val endExclusive = max(selection.max, composing?.end ?: selection.max)
        return start until endExclusive
    }

    private fun deleteOutsideAnchor(beforeStart: Int, anchorStart: Int, anchorEnd: Int, afterEnd: Int) {
        updateState {
            val beforeDeleted = anchorStart - beforeStart
            if (afterEnd > anchorEnd) buffer.delete(anchorEnd, afterEnd)
            if (anchorStart > beforeStart) buffer.delete(beforeStart, anchorStart)
            selection = TextRange(
                selection.start - beforeDeleted,
                selection.end - beforeDeleted,
            )
            composing = composing?.let {
                TextRange(it.start - beforeDeleted, it.end - beforeDeleted)
            }
        }
    }

    private inline fun updateState(block: () -> Unit) {
        val oldSelection = selection
        val oldComposing = composing
        block()
        if (batchDepth == 0) {
            publishUpdateIfChanged(oldSelection, oldComposing)
        }
    }

    private fun publishUpdateIfChanged(oldSelection: TextRange, oldComposing: TextRange?) {
        if (oldSelection == selection && oldComposing == composing) return
        val update = SelectionUpdate(oldSelection, selection, composing)
        if (selectionUpdateMode == SelectionUpdateMode.DELAYED) {
            delayedSelectionUpdates += update
        } else {
            onSelectionUpdate(update)
        }
    }

    private fun reject(operation: Operation, vararg arguments: Any?): Boolean {
        val rejected = closed || operation in persistentFailures || consumeNextFailure(operation)
        _calls += Call(operation, arguments.toList(), accepted = !rejected)
        return rejected
    }

    private fun acceptNoStateChange(operation: Operation, vararg arguments: Any?): Boolean =
        !reject(operation, *arguments)

    private fun consumeNextFailure(operation: Operation): Boolean {
        val count = nextFailures[operation] ?: return false
        if (count == 1) {
            nextFailures -= operation
        } else {
            nextFailures[operation] = count - 1
        }
        return true
    }

    private fun requireValidRange(range: TextRange): TextRange {
        require(range.start >= 0 && range.end >= 0)
        return range
    }

    private fun TextRange.normalized() = TextRange(min, max)
}
