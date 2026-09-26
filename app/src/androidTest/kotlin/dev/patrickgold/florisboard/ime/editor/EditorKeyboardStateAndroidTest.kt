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
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.ObservableKeyboardState
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorKeyboardStateAndroidTest {
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
            var shiftCalls = 0
            val editor = EditorInstance(
                instrumentation.targetContext,
                lazy {
                    stateResolutions++
                    state
                },
            ) {
                shiftCalls++
            }
            assertEquals(0, stateResolutions)

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
        }
    }
}
