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

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.ObservableKeyboardState
import dev.patrickgold.florisboard.ime.nlp.BreakIteratorGroup
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorKeyboardStateAndroidTest {
    @Test
    fun mediaPasteHandsTheCurrentItemToItsOwnerAndNullPasteDoesNothing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy { ObservableKeyboardState.new() },
                lazy { TestEditorComposingPolicy() },
            ) { }
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
    fun startInputUsesInjectedStateAndReevaluatesShiftSynchronously() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val state = ObservableKeyboardState.new().apply {
                keyboardMode = KeyboardMode.PHONE
                isActionsOverflowVisible = true
                isActionsEditorVisible = true
            }
            var stateResolutions = 0
            var policyResolutions = 0
            var shiftCalls = 0
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy {
                    stateResolutions++
                    state
                },
                lazy {
                    policyResolutions++
                    TestEditorComposingPolicy()
                },
            ) {
                shiftCalls++
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
            assertEquals(1, shiftCalls)
            assertEquals(KeyboardMode.NUMERIC, state.keyboardMode)
            assertEquals(KeyVariation.NORMAL, state.keyVariation)
            assertFalse(state.isComposingEnabled)
            assertFalse(state.isActionsOverflowVisible)
            assertFalse(state.isActionsEditorVisible)

            startInput(InputType.TYPE_CLASS_PHONE)
            assertEquals(2, shiftCalls)
            assertEquals(KeyboardMode.PHONE, state.keyboardMode)
            assertEquals(KeyVariation.NORMAL, state.keyVariation)
            assertFalse(state.isComposingEnabled)

            startInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            assertEquals(3, shiftCalls)
            assertEquals(KeyboardMode.CHARACTERS, state.keyboardMode)
            assertEquals(KeyVariation.PASSWORD, state.keyVariation)
            assertFalse(state.isComposingEnabled)

            editor.handleSelectionUpdate(
                EditorRange.Unspecified,
                EditorRange.Unspecified,
                EditorRange.Unspecified,
            )
            assertEquals(4, shiftCalls)
            assertEquals(1, stateResolutions)
            assertEquals(0, policyResolutions)
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
                lazy {
                    policyResolutions++
                    policy
                },
            ) { }

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
                lazy { policy },
            ) { }
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
