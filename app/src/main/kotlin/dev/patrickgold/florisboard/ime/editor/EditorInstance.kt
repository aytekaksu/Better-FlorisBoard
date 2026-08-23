/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

import android.content.ClipDescription
import android.content.Context
import android.view.KeyEvent
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.ClipboardMediaPasteAccess
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.text.composing.Appender
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import java.util.concurrent.atomic.AtomicInteger
import org.florisboard.lib.android.showShortToast

internal inline fun dispatchMediaPasteContent(
    pasteAccess: ClipboardMediaPasteAccess,
    dispatch: () -> Boolean,
): Boolean {
    return try {
        dispatch()
    } catch (_: Exception) {
        false
    } finally {
        // Once dispatch begins, the editor can request and retain URI access
        // even when it rejects the paste or the Binder call throws.
        pasteAccess.commitSucceededOrMayHaveSucceeded()
    }
}

internal data class AutoCorrectionRevertPlan(
    val range: EditorRange,
    val expectedText: String,
    val replacementText: String,
)

internal fun autoCorrectionRevertPlan(
    replacement: SuggestionReplacement,
    candidateText: String,
    content: EditorContent,
): AutoCorrectionRevertPlan? {
    val selection = content.selection
    if (
        content.offset < 0 ||
        replacement.range.isNotValid ||
        replacement.range.start > replacement.range.end ||
        replacement.originalText.isEmpty() ||
        replacement.originalText.length != replacement.range.length ||
        replacement.expectedSelection != EditorRange.cursor(replacement.range.end) ||
        candidateText == replacement.originalText ||
        candidateText.length > Int.MAX_VALUE - replacement.range.start ||
        !selection.isCursorMode
    ) {
        return null
    }
    val correctedEnd = replacement.range.start + candidateText.length
    val separatorLength = selection.start - correctedEnd
    val localStart = replacement.range.start - content.offset
    val localEnd = selection.start - content.offset
    val expectedText = candidateText + if (separatorLength == 1) " " else ""
    if (
        separatorLength !in 0..1 ||
        localStart < 0 ||
        localEnd > content.text.length ||
        content.text.substring(localStart, localEnd) != expectedText
    ) {
        return null
    }
    return AutoCorrectionRevertPlan(
        range = EditorRange(replacement.range.start, selection.start),
        expectedText = expectedText,
        replacementText = replacement.originalText,
    )
}

internal data class SelectionDragState(
    val anchor: Int,
    val endpoint: Int,
) {
    val selection: EditorRange
        get() = EditorRange.normalized(anchor, endpoint)

    val isMovingSelectionStart: Boolean
        get() = endpoint <= anchor

    fun movedBy(steps: Int, codeUnitDistance: Int): SelectionDragState {
        val distance = codeUnitDistance.coerceAtLeast(0)
        return when {
            steps < 0 -> copy(endpoint = endpoint - distance)
            steps > 0 -> copy(endpoint = endpoint + distance)
            else -> this
        }
    }

    companion object {
        fun create(
            selection: EditorRange,
            initialSteps: Int,
            establishedMovingStart: Boolean? = null,
        ): SelectionDragState? {
            if (selection.isNotValid || initialSteps == 0) return null
            val start = minOf(selection.start, selection.end)
            val end = maxOf(selection.start, selection.end)
            return if (establishedMovingStart ?: (initialSteps < 0)) {
                SelectionDragState(anchor = end, endpoint = start)
            } else {
                SelectionDragState(anchor = start, endpoint = end)
            }
        }
    }
}

class EditorInstance(context: Context) : AbstractEditorInstance(context) {
    companion object {
        private const val SPACE = " "
    }

    private val prefs by FlorisPreferenceStore
    private val appContext by context.appContext()
    private val clipboardManager by context.clipboardManager()
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()
    private val nlpManager by context.nlpManager()

    private val activeState get() = keyboardManager.activeState
    val autoSpace = AutoSpaceState()
    internal val phantomSpace = PhantomSpaceState()
    val massSelection = MassSelectionState()

    private fun currentInputConnection() = FlorisImeService.currentInputConnection()

    private fun Boolean.finishCommitAttempt() = also {
        if (it) updateLastCommitPosition() else phantomSpace.setInactive()
    }

    override fun handleStartInputView(editorInfo: FlorisEditorInfo, isRestart: Boolean) {
        if (!prefs.correction.rememberCapsLockState.get()) {
            activeState.inputShiftState = InputShiftState.UNSHIFTED
        }
        activeState.isActionsOverflowVisible = false
        activeState.isActionsEditorVisible = false
        super.handleStartInputView(editorInfo, isRestart)
        val keyboardMode = when (editorInfo.inputAttributes.type) {
            InputAttributes.Type.NUMBER -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.NUMERIC
            }
            InputAttributes.Type.PHONE -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.PHONE
            }
            InputAttributes.Type.TEXT -> {
                activeState.keyVariation = when (editorInfo.inputAttributes.variation) {
                    InputAttributes.Variation.EMAIL_ADDRESS,
                    InputAttributes.Variation.WEB_EMAIL_ADDRESS,
                    -> {
                        KeyVariation.EMAIL_ADDRESS
                    }
                    InputAttributes.Variation.PASSWORD,
                    InputAttributes.Variation.VISIBLE_PASSWORD,
                    InputAttributes.Variation.WEB_PASSWORD,
                    -> {
                        KeyVariation.PASSWORD
                    }
                    InputAttributes.Variation.URI -> {
                        KeyVariation.URI
                    }
                    else -> {
                        KeyVariation.NORMAL
                    }
                }
                KeyboardMode.CHARACTERS
            }
            else -> {
                activeState.keyVariation = KeyVariation.NORMAL
                KeyboardMode.CHARACTERS
            }
        }
        activeState.keyboardMode = keyboardMode
        activeState.isComposingEnabled = when (keyboardMode) {
            KeyboardMode.NUMERIC,
            KeyboardMode.PHONE,
            KeyboardMode.PHONE2,
            -> false
            else -> activeState.keyVariation != KeyVariation.PASSWORD &&
                prefs.suggestion.enabled.get()
        }
        activeState.isIncognitoMode = when (prefs.suggestion.incognitoMode.get()) {
            IncognitoMode.FORCE_OFF -> false
            IncognitoMode.FORCE_ON -> true
            IncognitoMode.DYNAMIC_ON_OFF -> {
                editorInfo.imeOptions.flagNoPersonalizedLearning || prefs.suggestion.forceIncognitoModeFromDynamic.get()
            }
        }
    }

    override fun handleSelectionUpdate(
        oldSelection: EditorRange,
        newSelection: EditorRange,
        composing: EditorRange,
    ) {
        autoSpace.setInactiveFromUpdate()
        val isExpectedUpdate = if (massSelection.isActive) {
            super.handleMassSelectionUpdate(newSelection, composing)
            false
        } else {
            super.handleSelectionUpdateInternal(newSelection, composing)
        }
        phantomSpace.setInactiveFromUpdate(isExpectedUpdate)
    }

    override fun determineComposingEnabled(): Boolean {
        return activeState.isComposingEnabled && nlpManager.isSuggestionOn()
    }

    override fun determineComposer(composerName: ExtensionComponentName): Composer {
        return keyboardManager.resources.composers.value[composerName] ?: Appender
    }

    override fun shouldDetermineComposingRegion(editorInfo: FlorisEditorInfo): Boolean {
        return super.shouldDetermineComposingRegion(editorInfo) &&
            (phantomSpace.isInactive || phantomSpace.showComposingRegion)
    }

    /**
     * Sets the selection of the input editor to the specified [start] and [end] values. This method does nothing if
     * the input connection is not valid or if the input editor is raw.
     *
     * @param start The start of the selection (inclusive). May be any value ranging from -1 to positive infinity.
     * @param end The end of the selection (exclusive). May be any value ranging from -1 to positive infinity.
     *
     * @return True on success or if the selection is already at specified position, false otherwise.
     */
    fun setSelection(start: Int, end: Int): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val selection = EditorRange.normalized(start, end)
        return super.setSelection(selection)
    }

    /**
     * Moves the cursor directly, without sending directional key events to the editor.
     */
    fun moveCursorBy(steps: Int): Boolean {
        if (steps == 0) return true
        val content = activeContent
        val selection = content.selection
        if (selection.isNotValid) return false

        val isMovingLeft = steps < 0
        val selectionStart = minOf(selection.start, selection.end)
        val selectionEnd = maxOf(selection.start, selection.end)
        val stepCount = kotlin.math.abs(steps)
        val selectionBoundary = if (isMovingLeft) selectionStart else selectionEnd
        val remainingSteps = (stepCount - if (selection.isSelectionMode) 1 else 0)
            .coerceAtLeast(0)
        val movement = if (isMovingLeft) {
            content.getTextBeforeCursor(remainingSteps).length
        } else {
            content.getTextAfterCursor(remainingSteps).length
        }
        val target = selectionBoundary + if (isMovingLeft) -movement else movement
        return setSelection(target, target)
    }

    /**
     * Moves one endpoint of a selection while keeping its original anchor fixed.
     */
    internal fun moveSelectionBy(state: SelectionDragState, steps: Int): SelectionDragState? {
        if (steps == 0) return state
        val content = activeContent
        if (content.offset < 0 || content.selection != state.selection) return null
        val localEndpoint = state.endpoint - content.offset
        if (localEndpoint !in 0..content.text.length) return null

        val stepCount = kotlin.math.abs(steps)
        val movement = if (steps < 0) {
            breakIterators.measureLastUChars(
                content.text.substring(0, localEndpoint),
                stepCount,
                subtypeManager.activeSubtype.primaryLocale,
            )
        } else {
            breakIterators.measureUChars(
                content.text.substring(localEndpoint),
                stepCount,
                subtypeManager.activeSubtype.primaryLocale,
            )
        }
        val next = state.movedBy(steps, movement)
        return next.takeIf { setSelection(it.selection.start, it.selection.end) }
    }

    private fun shouldInsertAutoSpaceBefore(text: String): Boolean {
        if (!prefs.correction.autoSpacePunctuation.get() || text.isEmpty()) return false
        if (activeInfo.isRawInputEditor) return false
        if (activeState.keyVariation != KeyVariation.NORMAL) return false

        val punctuationRule = nlpManager.getActivePunctuationRule()
        val textBefore = activeContent.getTextBeforeCursor(1)
        return textBefore.isNotEmpty() && !textBefore.last().isWhitespace() &&
            punctuationRule.symbolsFollowingAutoSpace.contains(text.first())
    }

    private fun shouldInsertAutoSpaceAfter(text: String): Boolean {
        if (!prefs.correction.autoSpacePunctuation.get() || text.isEmpty()) return false
        if (activeInfo.isRawInputEditor) return false
        if (activeState.keyVariation != KeyVariation.NORMAL) return false

        val punctuationRule = nlpManager.getActivePunctuationRule()
        val content = activeContent
        val textBefore = content.getTextBeforeCursor(3).let { textBefore ->
            if (autoSpace.isActive && textBefore.isNotEmpty() && textBefore.last() == ' ') {
                textBefore.dropLast(1)
            } else {
                textBefore
            }
        }
        return textBefore.isNotEmpty() && !textBefore.last().isWhitespace() &&
            content.currentWordText.all { !it.isDigit() } &&
            punctuationRule.symbolsPrecedingAutoSpace.contains(text.first())
    }

    override fun commitChar(char: String): Boolean {
        val isInsertAutoSpaceBeforeChar = shouldInsertAutoSpaceBefore(char)
        val isInsertAutoSpaceAfterChar = shouldInsertAutoSpaceAfter(char)
        val isDeletePreviousSpace = isInsertAutoSpaceAfterChar && autoSpace.isActive
        if (isInsertAutoSpaceAfterChar) {
            autoSpace.setActive()
        } else {
            autoSpace.setInactive()
        }
        val isPhantomSpaceActive = phantomSpace.determine(char)
        phantomSpace.setInactive()
        return super.commitChar(
            char = char,
            deletePreviousSpace = isDeletePreviousSpace,
            insertSpaceBeforeChar = isInsertAutoSpaceBeforeChar || isPhantomSpaceActive,
            insertSpaceAfterChar = isInsertAutoSpaceAfterChar,
        )
    }

    /**
     * Commits the given [text] to this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * This method overwrites any selected text and replaces it with given [text]. If there is no
     * text selected (selection is in cursor mode), then this method will insert the [text] after
     * the cursor, then set the cursor position to the first character after the inserted text.
     *
     * @param text The text to commit.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    override fun commitText(text: String): Boolean {
        val isPhantomSpaceActive = phantomSpace.determine(text)
        val candidateForRevert = phantomSpace.candidateForRevert.takeIf { text == SPACE }
        autoSpace.setInactive()
        phantomSpace.setInactive(candidateForRevert)
        val committedText = if (isPhantomSpaceActive) "$SPACE$text" else text
        val committed = super.commitText(committedText)
        if (!committed) {
            phantomSpace.setInactive()
        }
        return committed
    }

    /**
     * Completes the given [candidate] in the current composing region. Does nothing if the current
     * input editor is not rich or if the input connection is invalid.
     *
     * Current phantom space state is respected and a space char will be inserted accordingly.
     * Phantom space will be activated if the text is committed.
     *
     * @param candidate The candidate to complete in this editor.
     *
     * @return Whether the candidate was committed, rejected without mutation, or failed after a mutation attempt.
     */
    internal fun commitCompletion(
        candidate: SuggestionCandidate,
        canRevert: Boolean,
    ): EditorEditResult {
        val text = candidate.text.toString()
        if (text.isEmpty() || activeInfo.isRawInputEditor) {
            return EditorEditResult.NOT_APPLICABLE
        }
        val content = activeContent
        val replacement = candidate.replacement
        if (replacement != null) {
            val candidateForRevert = candidate.takeIf { canRevert }
            val replacementRange = replacement.range
            val localRange = replacementRange.translatedBy(-content.offset.coerceAtLeast(0))
            val isCurrentReplacement = localRange.isValid &&
                localRange.start <= localRange.end &&
                localRange.end <= content.text.length &&
                content.text.substring(localRange.start, localRange.end) == replacement.originalText &&
                content.selection == replacement.expectedSelection
            if (!isCurrentReplacement) {
                return EditorEditResult.NOT_APPLICABLE
            }
            phantomSpace.setActive(
                showComposingRegion = false,
                candidate = candidateForRevert,
            )
            val result = replaceTextBeforeCursor(
                range = replacementRange,
                expectedText = replacement.originalText,
                replacementText = text,
            )
            if (result != EditorEditResult.SUCCESS) {
                phantomSpace.setInactive()
            }
            if (result == EditorEditResult.SUCCESS) updateLastCommitPosition()
            return result
        }
        val committed = if (content.composing.isValid) {
            phantomSpace.setActive(showComposingRegion = false)
            super.finalizeComposingText(text)
        } else {
            val isPhantomSpaceActive = phantomSpace.determine(text)
            phantomSpace.setActive(showComposingRegion = false)
            (if (isPhantomSpaceActive) {
                super.commitText("$SPACE$text")
            } else {
                super.commitText(text)
            }).finishCommitAttempt()
        }
        if (!committed) phantomSpace.setInactive()
        return committed.asEditorEditResult()
    }

    internal fun revertAutoCorrection(): EditorEditResult {
        val candidate = phantomSpace.candidateForRevert
            ?: return EditorEditResult.NOT_APPLICABLE
        val plan = candidate.replacement?.let { replacement ->
            autoCorrectionRevertPlan(
                replacement = replacement,
                candidateText = candidate.text.toString(),
                content = activeContent,
            )
        }
        if (plan == null) {
            phantomSpace.setInactive()
            return EditorEditResult.NOT_APPLICABLE
        }
        phantomSpace.setInactive()
        return replaceTextBeforeCursor(
            range = plan.range,
            expectedText = plan.expectedText,
            replacementText = plan.replacementText,
            validateLiveState = true,
            resetLastCommitPositionTo = plan.range.start,
        )
    }

    /**
     * Commit a word generated by a gesture.
     *
     * Ignores the current phantom space state and will insert a space depending on the character
     * before selection start. Phantom space will be activated if the text is committed.
     *
     * @param text The text to commit in this editor.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun commitGesture(text: String): Boolean {
        if (text.isEmpty() || activeInfo.isRawInputEditor) return false
        val isPhantomSpaceActive = phantomSpace.determine(text, forceActive = true)
        phantomSpace.setActive(showComposingRegion = true)
        return (if (isPhantomSpaceActive) {
            super.commitText("$SPACE$text")
        } else {
            super.commitText(text)
        }).finishCommitAttempt()
    }

    /**
     * Commits the given [ClipboardItem]. If the clip data is text (incl. HTML), it delegates to [commitText].
     * If the item has a content URI (and the EditText supports it), the item is committed as rich data.
     * This allows for committing (e.g) images.
     *
     * @param item The ClipboardItem to commit
     *
     * @return True on success, false if something went wrong.
     */
    internal fun commitClipboardItem(
        item: ClipboardItem?,
        pasteAccess: ClipboardMediaPasteAccess? = null,
    ): Boolean {
        if (item == null) return false
        return when (item.type) {
            ItemType.TEXT -> commitText(item.text ?: return false).finishCommitAttempt()
            ItemType.IMAGE, ItemType.VIDEO -> {
                val preparedAccess = pasteAccess ?: return false
                val ownedUri = item.uri
                    ?.let { OwnedClipboardMediaUri.parse(it, item.type) }
                    ?: run {
                        preparedAccess.commitRejected()
                        return false
                    }
                val ic = currentInputConnection() ?: run {
                    preparedAccess.commitRejected()
                    return false
                }
                val composingFinished = try {
                    ic.finishComposingText()
                } catch (_: Exception) {
                    preparedAccess.commitRejected()
                    return false
                }
                if (!composingFinished) {
                    preparedAccess.commitRejected()
                    return false
                }
                val inputContentInfo = try {
                    InputContentInfoCompat(
                        ownedUri.uri,
                        ClipDescription(
                            "clipboard media file",
                            preparedAccess.mimeTypes.toTypedArray(),
                        ),
                        null,
                    )
                } catch (_: Exception) {
                    preparedAccess.commitRejected()
                    return false
                }
                dispatchMediaPasteContent(preparedAccess) {
                    val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                    InputConnectionCompat.commitContent(
                        ic,
                        activeInfo.base,
                        inputContentInfo,
                        flags,
                        null,
                    )
                }
            }
        }.also {
            if (prefs.clipboard.historyHideOnPaste.get()) {
                keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT
            }
        }
    }

    /**
     * Executes a backward delete on this editor's text. If a text selection is active, all
     * characters inside this selection will be removed, else only the left-most character from
     * the cursor's position.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun deleteBackwards(unit: OperationUnit): Boolean {
        val content = activeContent
        if (unit == OperationUnit.CHARACTERS) {
            if (phantomSpace.isActive && content.currentWord.isValid && prefs.glide.immediateBackspaceDeletesWord.get()) {
                return deleteBackwards(OperationUnit.WORDS)
            }
        }
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return if (content.selection.isSelectionMode) {
            commitText("")
        } else deleteAroundCursor(unit, OperationScope.BEFORE_CURSOR, n = 1)
    }

    /**
     * Executes a backward delete on this editor's text. If a text selection is active, all
     * characters inside this selection will be removed, else only the left-most character from
     * the cursor's position.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun deleteForwards(unit: OperationUnit): Boolean {
        val content = activeContent
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return if (content.selection.isSelectionMode) {
            commitText("")
        } else deleteAroundCursor(unit, OperationScope.AFTER_CURSOR, n = 1)
    }

    fun setSelectionSurrounding(n: Int, unit: OperationUnit, scope: OperationScope): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val content = activeContent
        val selection = content.selection
        val safeEditorBounds = content.safeEditorBounds
        if (selection.isNotValid) return false
        when (scope) {
            OperationScope.BEFORE_CURSOR -> {
                if (n <= 0) {
                    return setSelection(selection.end, selection.end)
                }
                val textToAnalyze = content.text.substring(0, content.localSelection.end)
                val length = when (unit) {
                    OperationUnit.CHARACTERS -> breakIterators.measureLastUChars(textToAnalyze, n)
                    OperationUnit.WORDS -> breakIterators.measureLastUWords(textToAnalyze, n)
                }
                return setSelection((selection.end - length).coerceAtLeast(safeEditorBounds.start), selection.end)
            }
            OperationScope.AFTER_CURSOR -> {
                if (n <= 0) {
                    return setSelection(selection.start, selection.start)
                }
                val textToAnalyze = content.text.substring(content.localSelection.start)
                val length = when (unit) {
                    OperationUnit.CHARACTERS -> breakIterators.measureUChars(textToAnalyze, n)
                    OperationUnit.WORDS -> breakIterators.measureUWords(textToAnalyze, n)
                }
                return setSelection(selection.start, (selection.start + length).coerceAtMost(safeEditorBounds.end))
            }
        }
    }

    /**
     * Performs a cut command on this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performClipboardCut(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val text = activeContent.selectedText.ifBlank { currentInputConnection()?.getSelectedText(0) }
        if (text != null) {
            clipboardManager.addNewPlaintext(text.toString())
        } else {
            launchOnMain {
                appContext.showShortToast("Failed to retrieve selected text requested to cut: Either selection state is invalid or an error occurred within the input connection.")
            }
        }
        return deleteBackwards(OperationUnit.CHARACTERS)
    }

    /**
     * Performs a copy command on this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performClipboardCopy(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val text = activeContent.selectedText.ifBlank { currentInputConnection()?.getSelectedText(0) }
        if (text != null) {
            clipboardManager.addNewPlaintext(text.toString())
        } else {
            launchOnMain {
                appContext.showShortToast("Failed to retrieve selected text requested to copy: Either selection state is invalid or an error occurred within the input connection.")
            }
        }
        val activeSelection = activeContent.selection
        return setSelection(activeSelection.end, activeSelection.end)
    }

    /**
     * Performs a paste command on this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performClipboardPaste(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val item = clipboardManager.primaryClip ?: return false
        if (item.type != ItemType.TEXT) {
            clipboardManager.pasteItem(item)
            return true
        }
        return commitClipboardItem(item).also { result ->
            if (!result) {
                launchOnMain { appContext.showShortToast("Failed to paste item.") }
            }
        }
    }

    /**
     * Performs a select all on this editor instance and adjusts both the cursor position and
     * composing region, if any.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performClipboardSelectAll(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val ic = currentInputConnection() ?: return false
        ic.finishComposingText()
        return if (activeInfo.isRawInputEditor) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_A, meta(ctrl = true))
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
        }
    }

    /**
     * Performs an enter key press on the current input editor.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performEnter(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return if (activeInfo.isRawInputEditor) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_ENTER)
        } else {
            commitText("\n")
        }
    }

    fun tryPerformEnterCommitRaw(): Boolean {
        return if (subtypeManager.activeSubtype.primaryLocale.language.startsWith("zh") && activeContent.composing.length > 0) {
            finalizeComposingText(activeContent.composingText)
        } else {
            false
        }
    }

    /**
     * Performs a given [action] on the current input editor.
     *
     * @param action The action to be performed on this editor instance.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performEnterAction(action: ImeOptions.Action): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        val ic = currentInputConnection() ?: return false
        return ic.performEditorAction(action.toInt())
    }

    /**
     * Undoes the last action.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performUndo(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true))
    }

    /**
     * Redoes the last Undo action.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun performRedo(): Boolean {
        autoSpace.setInactive()
        phantomSpace.setInactive()
        return sendDownUpKeyEvent(KeyEvent.KEYCODE_Z, meta(ctrl = true, shift = true))
    }

    override fun reset() {
        super.reset()
        autoSpace.setInactive()
        phantomSpace.setInactive()
        massSelection.reset()
    }

    private fun PhantomSpaceState.determine(text: String, forceActive: Boolean = false): Boolean {
         val content = activeContent
         val selection = content.selection
         if (!(isActive || forceActive) || selection.isNotValid || selection.start <= 0 || text.isEmpty()) return false
         val textBefore = content.getTextBeforeCursor(1)
         val punctuationRule = nlpManager.getActivePunctuationRule()
         if (!subtypeManager.activeSubtype.primaryLocale.supportsAutoSpace) return false;
         return textBefore.isNotEmpty() &&
             (punctuationRule.symbolsPrecedingPhantomSpace.contains(textBefore[textBefore.length - 1]) ||
                 textBefore[textBefore.length - 1].isLetterOrDigit()) &&
             (punctuationRule.symbolsFollowingPhantomSpace.contains(text[0]) || text[0].isLetterOrDigit())
    }

    class AutoSpaceState {
        companion object {
            private const val F_IS_ACTIVE = 0x1
            private const val F_STAY_ACTIVE_NEXT_UPDATE = 0x4
        }

        private val state = AtomicInteger(0)

        val isActive: Boolean
            get() = state.get() and F_IS_ACTIVE != 0

        val isInactive: Boolean
            get() = !isActive

        fun setActive(stayActiveNextUpdate: Boolean = true) {
            state.set(F_IS_ACTIVE or (if (stayActiveNextUpdate) F_STAY_ACTIVE_NEXT_UPDATE else 0))
        }

        fun setInactive() {
            state.set(0)
        }

        fun setInactiveFromUpdate() {
            state.updateAndGet { state ->
                if ((state and F_STAY_ACTIVE_NEXT_UPDATE) != 0) (state and F_STAY_ACTIVE_NEXT_UPDATE.inv()) else 0
            }
        }
    }

    internal class PhantomSpaceState {
        companion object {
            private const val F_IS_ACTIVE = 0x1
            private const val F_SHOW_COMPOSING_REGION = 0x2
            private const val F_STAY_ACTIVE_NEXT_UPDATE = 0x4
        }

        private val state = AtomicInteger(0)
        var candidateForRevert: SuggestionCandidate? = null
            private set

        val isActive: Boolean
            get() = state.get() and F_IS_ACTIVE != 0

        val isInactive: Boolean
            get() = !isActive

        val showComposingRegion: Boolean
            get() = state.get() and F_SHOW_COMPOSING_REGION != 0

        fun setActive(
            showComposingRegion: Boolean,
            stayActiveNextUpdate: Boolean = true,
            candidate: SuggestionCandidate? = null,
        ) {
            state.set(
                F_IS_ACTIVE
                    or (if (showComposingRegion) F_SHOW_COMPOSING_REGION else 0)
                    or (if (stayActiveNextUpdate) F_STAY_ACTIVE_NEXT_UPDATE else 0)
            )
            candidateForRevert = candidate
        }

        fun setInactive(
            candidateToRetain: SuggestionCandidate? = null,
        ) {
            state.set(0)
            candidateForRevert = candidateToRetain
        }

        fun setInactiveFromUpdate(isExpectedUpdate: Boolean) {
            state.updateAndGet { state ->
                if ((state and F_STAY_ACTIVE_NEXT_UPDATE) != 0) (state and F_STAY_ACTIVE_NEXT_UPDATE.inv()) else 0
            }
            if (candidateForRevert != null && !isExpectedUpdate) {
                candidateForRevert = null
            }
        }
    }

    inner class MassSelectionState {
        private val state = AtomicInteger(0)

        val isActive: Boolean
            get() = state.get() > 0

        val isInactive: Boolean
            get() = !isActive

        fun begin() {
            state.incrementAndGet()
        }

        fun end() {
            if (state.decrementAndGet() == 0) {
                // We need to emulate a selection update to update the content if mass selection has ended
                handleSelectionUpdate(EditorRange.Unspecified, activeContent.selection, EditorRange.Unspecified)
            }
        }

        fun reset() {
            state.set(0)
        }
    }
}
