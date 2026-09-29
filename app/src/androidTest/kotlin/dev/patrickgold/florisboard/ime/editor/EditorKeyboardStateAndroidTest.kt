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

package dev.patrickgold.florisboard.ime.editor

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.ObservableKeyboardState
import dev.patrickgold.florisboard.ime.nlp.BreakIteratorGroup
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.lib.FlorisLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorKeyboardStateAndroidTest {
    @Test
    fun subtypeSourceIsLazyAndReadsTheLatestSubtype() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val initial = Subtype.DEFAULT
            val japanese = initial.copy(primaryLocale = FlorisLocale.from("ja"))
            var active = initial
            val reads = mutableListOf<Subtype>()
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy { ObservableKeyboardState.new() },
                { active.also(reads::add) },
                lazy { TestEditorComposingPolicy() },
                { null },
            ) { false }
            val content = EditorContent("abc", 0, EditorRange.cursor(3), EditorRange.Unspecified, EditorRange.Unspecified)

            assertTrue(reads.isEmpty())
            with(editor) { assertEquals("c", content.getTextBeforeCursor(1)) }
            active = japanese
            with(editor) { assertEquals("c", content.getTextBeforeCursor(1)) }
            assertEquals(listOf(initial, japanese), reads)
        }
    }

    @Test
    fun mediaPasteHandsTheCurrentItemToItsOwnerAndNullPasteDoesNothing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy { ObservableKeyboardState.new() },
                { Subtype.DEFAULT },
                lazy { TestEditorComposingPolicy() },
                { null },
            ) { false }
            val media = ClipboardItem(
                type = ItemType.IMAGE,
                text = null,
                uri = requireNotNull(OwnedClipboardMediaUri.create(1L, ItemType.IMAGE)).uri,
                creationTimestampMs = 1L,
                isPinned = false,
                mimeTypes = listOf("image/png"),
            )
            var handedOff: ClipboardItem? = null
            assertFalse(editor.performClipboardPaste(null) { handedOff = it })
            assertEquals(null, handedOff)
            assertTrue(editor.performClipboardPaste(media) { handedOff = it })
            assertEquals(media, handedOff)
        }
    }

    @Test
    fun startInputUsesInjectedStateAndReadsShiftSynchronously() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val state = ObservableKeyboardState.new().apply {
                keyboardMode = KeyboardMode.PHONE
                isActionsOverflowVisible = true
                isActionsEditorVisible = true
            }
            var stateResolutions = 0
            var policyResolutions = 0
            var shiftPressedReads = 0
            var shiftPressed = false
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy {
                    stateResolutions++
                    state
                },
                { Subtype.DEFAULT },
                lazy {
                    policyResolutions++
                    TestEditorComposingPolicy()
                },
                { null },
            ) {
                shiftPressedReads++
                shiftPressed
            }
            assertEquals(0, stateResolutions)
            assertEquals(0, policyResolutions)

            fun startInput(type: Int) {
                editor.handleStartInputView(FlorisEditorInfo.wrap(EditorInfo().apply {
                    inputType = type
                    initialSelStart = -1
                    initialSelEnd = -1
                }), isRestart = false)
            }

            startInput(InputType.TYPE_CLASS_NUMBER)
            assertEquals(1, stateResolutions)
            assertEquals(1, shiftPressedReads)
            assertEquals(InputShiftState.UNSHIFTED, state.inputShiftState)
            assertEquals(KeyboardMode.NUMERIC, state.keyboardMode)
            assertEquals(KeyVariation.NORMAL, state.keyVariation)
            assertFalse(state.isComposingEnabled)
            assertFalse(state.isActionsOverflowVisible)
            assertFalse(state.isActionsEditorVisible)

            startInput(InputType.TYPE_CLASS_PHONE)
            assertEquals(2, shiftPressedReads)
            assertEquals(InputShiftState.UNSHIFTED, state.inputShiftState)
            assertEquals(KeyboardMode.PHONE, state.keyboardMode)
            assertEquals(KeyVariation.NORMAL, state.keyVariation)
            assertFalse(state.isComposingEnabled)

            startInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            assertEquals(3, shiftPressedReads)
            assertEquals(InputShiftState.UNSHIFTED, state.inputShiftState)
            assertEquals(KeyboardMode.CHARACTERS, state.keyboardMode)
            assertEquals(KeyVariation.PASSWORD, state.keyVariation)
            assertFalse(state.isComposingEnabled)

            editor.handleSelectionUpdate(
                EditorRange.Unspecified,
                EditorRange.Unspecified,
            )
            assertEquals(4, shiftPressedReads)
            assertEquals(InputShiftState.UNSHIFTED, state.inputShiftState)
            assertEquals(1, stateResolutions)
            assertEquals(0, policyResolutions)

            state.inputShiftState = InputShiftState.SHIFTED_MANUAL
            shiftPressed = true
            editor.handleSelectionUpdate(EditorRange.Unspecified, EditorRange.Unspecified)
            assertEquals(5, shiftPressedReads)
            assertEquals(InputShiftState.SHIFTED_MANUAL, state.inputShiftState)

            state.inputShiftState = InputShiftState.CAPS_LOCK
            shiftPressed = false
            editor.handleSelectionUpdate(EditorRange.Unspecified, EditorRange.Unspecified)
            assertEquals(5, shiftPressedReads)
            assertEquals(InputShiftState.CAPS_LOCK, state.inputShiftState)
        }
    }

    @Test
    fun applicationAndKeyboardManagerExposeTheSameInputRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext.applicationContext as FlorisApplication
            val manager = app.keyboardManager.value
            assertSame(app.keyboardState.value, manager.activeState)
            assertSame(app.inputEventDispatcher.value, manager.inputEventDispatcher)
            assertSame(manager, app.inputEventDispatcher.value.keyEventReceiver)
        }
    }

    @Test
    fun composingEligibilityUsesTheLazyPolicyOnlyWhenKeyboardAllowsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val state = ObservableKeyboardState.new()
            val policy = TestEditorComposingPolicy()
            var policyResolutions = 0
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy { state },
                { Subtype.DEFAULT },
                lazy {
                    policyResolutions++
                    policy
                },
                { null },
            ) { false }

            assertFalse(editor.determineComposingEnabled())
            assertEquals(0, policyResolutions)
            state.isComposingEnabled = true
            assertFalse(editor.determineComposingEnabled())
            assertEquals(1, policyResolutions)
            policy.suggestionsOn = true
            assertTrue(editor.determineComposingEnabled())
            assertEquals(1, policyResolutions)
            assertEquals(2, policy.suggestionChecks)
            state.isComposingEnabled = false
            assertFalse(editor.determineComposingEnabled())
            assertEquals(2, policy.suggestionChecks)
        }
    }

    @Test
    fun currentWordPolicyRunsForCursorButNotSelectedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val state = ObservableKeyboardState.new().apply { isComposingEnabled = true }
            val policy = TestEditorComposingPolicy().apply {
                suggestionsOn = true
                localComposingRange = EditorRange(0, 3)
            }
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy { state },
                { Subtype.DEFAULT },
                lazy { policy },
                { null },
            ) { false }
            val info = FlorisEditorInfo.wrap(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            })

            val cursorContent = editor.generateContent(info, EditorRange.cursor(3), "abc", "", "")
            assertEquals(EditorRange(0, 3), cursorContent.localCurrentWord)
            assertEquals(EditorRange(0, 3), cursorContent.localComposing)
            assertEquals(listOf("determine", "enabled"), policy.calls)

            policy.suggestionsOn = false
            policy.calls.clear()
            val disabledContent = editor.generateContent(info, EditorRange.cursor(3), "abc", "", "")
            assertEquals(EditorRange(0, 3), disabledContent.localCurrentWord)
            assertEquals(EditorRange.Unspecified, disabledContent.localComposing)
            assertEquals(listOf("determine", "enabled"), policy.calls)

            policy.calls.clear()
            val selectedContent = editor.generateContent(info, EditorRange(1, 2), "a", "c", "b")
            assertEquals(EditorRange.Unspecified, selectedContent.localCurrentWord)
            assertEquals(EditorRange.Unspecified, selectedContent.localComposing)
            assertEquals(listOf("enabled"), policy.calls)
        }
    }

    @Test
    fun inputConnectionReaderUsesTheCurrentConnectionForBaseAndConcreteActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val first = RecordingInputConnection(context)
            val second = RecordingInputConnection(context)
            var activeConnection: InputConnection? = null
            var reads = 0
            val editor = EditorInstance(
                context,
                lazy { ObservableKeyboardState.new() },
                { Subtype.DEFAULT },
                lazy { TestEditorComposingPolicy() },
                {
                    reads++
                    activeConnection
                },
            ) { false }
            val info = FlorisEditorInfo.wrap(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT
            })
            val cursorUpdateAll =
                InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE

            assertEquals(0, reads)
            editor.handleStartInput(info)
            assertFalse(editor.performEnterAction(ImeOptions.Action.DONE))
            assertEquals(2, reads)

            activeConnection = first
            editor.handleStartInput(info)
            assertTrue(editor.performEnterAction(ImeOptions.Action.DONE))
            assertEquals(listOf(cursorUpdateAll), first.cursorUpdateModes)
            assertEquals(listOf(EditorInfo.IME_ACTION_DONE), first.editorActions)
            assertEquals(4, reads)

            activeConnection = second
            editor.handleStartInput(info)
            assertTrue(editor.performEnterAction(ImeOptions.Action.GO))
            assertEquals(listOf(cursorUpdateAll), first.cursorUpdateModes)
            assertEquals(listOf(EditorInfo.IME_ACTION_DONE), first.editorActions)
            assertEquals(listOf(cursorUpdateAll), second.cursorUpdateModes)
            assertEquals(listOf(EditorInfo.IME_ACTION_GO), second.editorActions)
            assertEquals(6, reads)

            activeConnection = null
            editor.handleFinishInput()
            assertFalse(editor.performEnterAction(ImeOptions.Action.DONE))
            assertEquals(listOf(cursorUpdateAll), second.cursorUpdateModes)
            assertEquals(8, reads)
        }
    }

    @Test
    fun syntheticKeyEventsKeepModifierOrderAndRepeatTiming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val connection = RecordingInputConnection(instrumentation.targetContext)
            var activeConnection: InputConnection? = connection
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy { ObservableKeyboardState.new() },
                { Subtype.DEFAULT },
                lazy { TestEditorComposingPolicy() },
                { activeConnection },
            ) { false }
            val down = KeyEvent.ACTION_DOWN
            val up = KeyEvent.ACTION_UP
            val key = KeyEvent.KEYCODE_Z

            assertTrue(editor.sendDownUpKeyEvent(key))
            assertEquals(listOf(Triple(down, key, 0), Triple(up, key, 0)), connection.keyEventSignatures())
            assertEquals(listOf(0, 0), connection.keyEvents.map { it.metaState })
            assertEquals(1, connection.batchBegins)
            assertEquals(1, connection.batchEnds)

            connection.keyEvents.clear()
            val modifiers = editor.meta(ctrl = true, alt = true, shift = true)
            assertTrue(editor.sendDownUpKeyEvent(key, modifiers, count = 3))
            assertEquals(
                listOf(
                    Triple(down, KeyEvent.KEYCODE_CTRL_LEFT, 0),
                    Triple(down, KeyEvent.KEYCODE_ALT_LEFT, 0),
                    Triple(down, KeyEvent.KEYCODE_SHIFT_LEFT, 0),
                    Triple(down, key, 0),
                    Triple(down, key, 1),
                    Triple(down, key, 2),
                    Triple(up, key, 0),
                    Triple(up, KeyEvent.KEYCODE_SHIFT_LEFT, 0),
                    Triple(up, KeyEvent.KEYCODE_ALT_LEFT, 0),
                    Triple(up, KeyEvent.KEYCODE_CTRL_LEFT, 0),
                ),
                connection.keyEventSignatures(),
            )
            assertEquals(listOf(0, 0, 0, modifiers, modifiers, modifiers, modifiers, 0, 0, 0),
                connection.keyEvents.map { it.metaState })
            val eventTime = connection.keyEvents.first().downTime
            connection.keyEvents.forEach { event ->
                assertEquals(eventTime, event.downTime)
                if (event.action == down) assertEquals(eventTime, event.eventTime)
                else assertTrue(event.eventTime >= eventTime)
                assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, event.deviceId)
                assertEquals(0, event.scanCode)
                assertEquals(KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, event.flags)
                assertEquals(InputDevice.SOURCE_KEYBOARD, event.source)
            }
            assertEquals(2, connection.batchBegins)
            assertEquals(2, connection.batchEnds)

            for ((singleModifier, modifierKeyCode) in listOf(
                editor.meta(ctrl = true) to KeyEvent.KEYCODE_CTRL_LEFT,
                editor.meta(alt = true) to KeyEvent.KEYCODE_ALT_LEFT,
                editor.meta(shift = true) to KeyEvent.KEYCODE_SHIFT_LEFT,
            )) {
                connection.keyEvents.clear()
                assertTrue(editor.sendDownUpKeyEvent(key, singleModifier))
                assertEquals(listOf(
                    Triple(down, modifierKeyCode, 0), Triple(down, key, 0),
                    Triple(up, key, 0), Triple(up, modifierKeyCode, 0),
                ), connection.keyEventSignatures())
                assertEquals(listOf(0, singleModifier, singleModifier, 0),
                    connection.keyEvents.map { it.metaState })
            }

            val eventsBeforeInvalidCalls = connection.keyEventSignatures()
            val batchesBeforeInvalidCalls = connection.batchBegins
            assertFalse(editor.sendDownUpKeyEvent(key, count = 0))
            activeConnection = null
            assertFalse(editor.sendDownUpKeyEvent(key))
            assertEquals(eventsBeforeInvalidCalls, connection.keyEventSignatures())
            assertEquals(batchesBeforeInvalidCalls, connection.batchBegins)
            assertEquals(batchesBeforeInvalidCalls, connection.batchEnds)
        }
    }

    @Test
    fun autoCorrectionRevertRestoresTheOriginalWordAfterAnAcknowledgedSpaceCommit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val (editor, connection) = newDirectionalEditor("teh", EditorRange.cursor(3))
        instrumentation.runOnMainSync {
            val candidate = object : SuggestionCandidate by WordSuggestionCandidate(text = "the") {
                override val replacement = SuggestionReplacement(EditorRange(0, 3), "teh", EditorRange.cursor(3))
            }
            assertEquals(EditorEditResult.SUCCESS, editor.commitCompletion(candidate, canRevert = true))
            assertTrue(editor.commitText(" "))
            editor.handleSelectionUpdate(
                connection.currentSelection,
                EditorRange(
                    BaseInputConnection.getComposingSpanStart(connection.getEditable()),
                    BaseInputConnection.getComposingSpanEnd(connection.getEditable()),
                ),
            )
            assertEquals("the ", connection.currentText)
            assertEquals(EditorRange.cursor(4), connection.currentSelection)
            assertEquals(EditorEditResult.SUCCESS, editor.revertAutoCorrection())
            assertEquals("teh", connection.currentText)
            assertEquals(EditorRange.cursor(3), connection.currentSelection)
            assertEquals(EditorEditResult.NOT_APPLICABLE, editor.revertAutoCorrection())
        }
    }

    @Test
    fun directionalCharacterDeletionStagesTheActualCursorAndKeepsTheNextInputInSync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for ((cursor, backwards, expectedDelete, expectedStage) in listOf(
            DeleteCase(3, true, 2 to 0, EditorRange.cursor(1)),
            DeleteCase(1, false, 0 to 2, EditorRange.cursor(1)),
        )) {
            val (editor, connection) = newDirectionalEditor("a🙂b", EditorRange.cursor(cursor))
            instrumentation.runOnMainSync {
                connection.expectedAtBatchStart = { editor.expectedContent() }
                val result = if (backwards) {
                    editor.deleteBackwards(OperationUnit.CHARACTERS)
                } else {
                    editor.deleteForwards(OperationUnit.CHARACTERS)
                }
                assertTrue(result)
                assertEquals(listOf(expectedDelete), connection.deletions)
                assertEquals("ab", connection.currentText)
                assertEquals(EditorRange.cursor(1), connection.currentSelection)
                val staged = requireNotNull(editor.expectedContent())
                assertEquals("ab", staged.text)
                assertEquals(expectedStage, staged.selection)
                assertEquals(staged, connection.contentAtBatchStart.first())
                editor.handleSelectionUpdate(connection.currentSelection, staged.composing)
                assertEquals(null, editor.expectedContent())
                assertEquals("ab", editor.activeContent.text)
                assertEquals(connection.currentSelection, editor.activeContent.selection)
                assertTrue(editor.commitText("x"))
                assertEquals("axb", connection.currentText)
                assertEquals("axb", editor.activeContent.text)
            }
        }
    }

    @Test
    fun directionalWordDeletionKeepsTheCachedCursorAtTheActualBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val subtype = Subtype.DEFAULT.copy(primaryLocale = FlorisLocale.from("fr"))
        for ((cursor, backwards, expectedDelete, expectedStage) in listOf(
            DeleteCase(7, true, 3 to 0, EditorRange.cursor(4)),
            DeleteCase(4, false, 0 to 3, EditorRange.cursor(4)),
        )) {
            val (editor, connection) = newDirectionalEditor("one two", EditorRange.cursor(cursor), subtype = subtype)
            instrumentation.runOnMainSync {
                val deleted = if (backwards) editor.deleteBackwards(OperationUnit.WORDS)
                else editor.deleteForwards(OperationUnit.WORDS)
                assertTrue(deleted)
                assertEquals(listOf(expectedDelete), connection.deletions)
                assertEquals("one ", connection.currentText)
                assertEquals(EditorRange.cursor(4), connection.currentSelection)
                val staged = requireNotNull(editor.expectedContent())
                assertEquals("one ", staged.text)
                assertEquals(expectedStage, staged.selection)
                editor.handleSelectionUpdate(connection.currentSelection, staged.composing)
                assertEquals(null, editor.expectedContent())
                assertEquals("one ", editor.activeContent.text)
                assertEquals(connection.currentSelection, editor.activeContent.selection)
            }
        }
    }

    @Test
    fun directionalDeleteRemovesSelectionWithoutMeasuringSurroundings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (backwards in listOf(true, false)) {
            val (editor, connection) = newDirectionalEditor("a🙂b", EditorRange(1, 3))
            instrumentation.runOnMainSync {
                val deleted = if (backwards) editor.deleteBackwards(OperationUnit.CHARACTERS)
                else editor.deleteForwards(OperationUnit.CHARACTERS)
                assertTrue(deleted)
                assertEquals("ab", connection.currentText)
                assertEquals(EditorRange.cursor(1), connection.currentSelection)
                assertTrue(connection.deletions.isEmpty())
                val staged = requireNotNull(editor.expectedContent())
                assertEquals("ab", staged.text)
                assertEquals(EditorRange.cursor(1), staged.selection)
            }
        }
    }

    @Test
    fun rawAndEmptyScopeDeletionUseDirectionalHardwareKeys() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        fun assertFallback(type: Int, text: String, cursor: Int, backwards: Boolean, unit: OperationUnit) {
            val (editor, connection) = newDirectionalEditor(text, EditorRange.cursor(cursor), type)
            val keyCode = if (backwards) KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_FORWARD_DEL
            instrumentation.runOnMainSync {
                assertTrue(if (backwards) editor.deleteBackwards(unit) else editor.deleteForwards(unit))
                val expectedCodes = if (unit == OperationUnit.WORDS) {
                    listOf(KeyEvent.KEYCODE_CTRL_LEFT, keyCode, keyCode, KeyEvent.KEYCODE_CTRL_LEFT)
                } else {
                    listOf(keyCode, keyCode)
                }
                assertEquals(expectedCodes, connection.keyEvents.map { it.keyCode })
                assertEquals(
                    if (unit == OperationUnit.WORDS) editor.meta(ctrl = true) else 0,
                    connection.keyEvents.first { it.keyCode == keyCode }.metaState,
                )
                assertTrue(connection.deletions.isEmpty())
                assertEquals(null, editor.expectedContent())
            }
        }

        assertFallback(InputType.TYPE_NULL, "a🙂b", 3, backwards = true, unit = OperationUnit.WORDS)
        assertFallback(InputType.TYPE_NULL, "a🙂b", 1, backwards = false, unit = OperationUnit.WORDS)
        assertFallback(InputType.TYPE_CLASS_TEXT, "", 0, backwards = true, unit = OperationUnit.CHARACTERS)
        assertFallback(InputType.TYPE_CLASS_TEXT, "", 0, backwards = false, unit = OperationUnit.CHARACTERS)
    }

    @Test
    fun surroundingSelectionKeepsDirectionalAnchorsAndZeroCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val initialSelection = EditorRange(1, 4)
        for ((scope, count, expected) in listOf(
            Triple(OperationScope.BEFORE_CURSOR, 1, EditorRange(3, 4)),
            Triple(OperationScope.AFTER_CURSOR, 1, EditorRange(1, 3)),
            Triple(OperationScope.BEFORE_CURSOR, 0, EditorRange.cursor(4)),
            Triple(OperationScope.AFTER_CURSOR, 0, EditorRange.cursor(1)),
            Triple(OperationScope.BEFORE_CURSOR, 99, EditorRange(0, 4)),
            Triple(OperationScope.AFTER_CURSOR, 99, EditorRange(1, 5)),
        )) {
            val (editor, connection) = newDirectionalEditor("a🙂bc", initialSelection)
            instrumentation.runOnMainSync {
                assertTrue(editor.setSelectionSurrounding(count, OperationUnit.CHARACTERS, scope))
                assertEquals(expected, connection.currentSelection)
                assertEquals(expected, connection.setSelections.last())
                assertEquals(expected, editor.expectedContent()?.selection)
            }
        }
    }

    private fun newDirectionalEditor(
        text: String,
        selection: EditorRange,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        subtype: Subtype = Subtype.DEFAULT,
    ): Pair<EditorInstance, RecordingInputConnection> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var editor: EditorInstance
        lateinit var connection: RecordingInputConnection
        instrumentation.runOnMainSync {
            connection = RecordingInputConnection(instrumentation.targetContext, text, selection)
            editor = EditorInstance(
                instrumentation.targetContext,
                lazy { ObservableKeyboardState.new() },
                { subtype },
                lazy { TestEditorComposingPolicy() },
                { connection },
            ) { false }
            editor.handleStartInputView(FlorisEditorInfo.wrap(EditorInfo().apply {
                this.inputType = inputType
                initialSelStart = selection.start
                initialSelEnd = selection.end
            }), isRestart = false)
        }
        instrumentation.waitForIdleSync()
        if (inputType != InputType.TYPE_NULL) {
            instrumentation.runOnMainSync { assertEquals(selection, editor.activeContent.selection) }
        }
        return editor to connection
    }

    private data class DeleteCase(
        val cursor: Int,
        val backwards: Boolean,
        val deletion: Pair<Int, Int>,
        val stagedSelection: EditorRange,
    )
}

private class RecordingInputConnection(
    context: Context,
    text: String = "",
    selection: EditorRange = EditorRange.cursor(text.length),
) : BaseInputConnection(View(context), true) {
    private val editable = SpannableStringBuilder(text).apply {
        Selection.setSelection(this, selection.start, selection.end)
    }
    val cursorUpdateModes = mutableListOf<Int>()
    val editorActions = mutableListOf<Int>()
    val keyEvents = mutableListOf<KeyEvent>()
    val deletions = mutableListOf<Pair<Int, Int>>()
    val setSelections = mutableListOf<EditorRange>()
    val contentAtBatchStart = mutableListOf<EditorContent?>()
    var expectedAtBatchStart: (() -> EditorContent?)? = null
    var batchBegins = 0
    var batchEnds = 0

    val currentText: String get() = editable.toString()
    val currentSelection: EditorRange get() = EditorRange(
        Selection.getSelectionStart(editable),
        Selection.getSelectionEnd(editable),
    )

    fun keyEventSignatures() = keyEvents.map { Triple(it.action, it.keyCode, it.repeatCount) }

    override fun getEditable(): Editable = editable

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
        text = currentText
        startOffset = 0
        partialStartOffset = -1
        partialEndOffset = -1
        selectionStart = currentSelection.start
        selectionEnd = currentSelection.end
    }

    override fun beginBatchEdit(): Boolean {
        expectedAtBatchStart?.let { contentAtBatchStart += it() }
        batchBegins++
        return true
    }

    override fun endBatchEdit(): Boolean {
        batchEnds++
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        keyEvents += event
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        deletions += beforeLength to afterLength
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        setSelections += EditorRange(start, end)
        return super.setSelection(start, end)
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        cursorUpdateModes += cursorUpdateMode
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        editorActions += editorAction
        return true
    }
}

private class TestEditorComposingPolicy : EditorComposingPolicy {
    var suggestionsOn = false
    var suggestionChecks = 0
    var localComposingRange = EditorRange.Unspecified
    val calls = mutableListOf<String>()

    override fun isSuggestionOn(): Boolean {
        suggestionChecks++
        calls += "enabled"
        return suggestionsOn
    }

    override fun determineLocalComposing(
        textBeforeSelection: CharSequence,
        breakIterators: BreakIteratorGroup,
        localLastCommitPosition: Int,
    ): EditorRange {
        calls += "determine"
        return localComposingRange
    }
}
