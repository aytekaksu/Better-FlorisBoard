/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar

import android.app.Application
import android.content.Context
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionInfo
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.nlp.NlpInlineAutofillSuggestion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], application = Application::class)
class InlineSuggestionsUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nonemptyReplacementAttachesTheCurrentNativeView() {
        lateinit var info: InlineSuggestionInfo
        lateinit var first: InlineContentView
        lateinit var replacement: InlineContentView
        composeRule.runOnUiThread {
            info = inlineSuggestionInfo()
            first = inlineContentView(composeRule.activity)
            replacement = inlineContentView(composeRule.activity)
        }
        var displayed by mutableStateOf(NlpInlineAutofillSuggestion(info, first))
        var showRow by mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (showRow) {
                    InlineSuggestionsUi(listOf(displayed), Modifier.size(320.dp, 64.dp))
                }
            }
        }

        try {
            composeRule.runOnIdle {
                assertNotNull("Initial view must be parented before testing replacement", first.parent)
                assertTrue("Initial view must be attached before testing replacement", first.isAttachedToWindow)
                assertNull("Replacement must start detached", replacement.parent)
                // Keep the same metadata and never publish an intermediate empty list.
                displayed = NlpInlineAutofillSuggestion(info, replacement)
            }
            composeRule.runOnIdle {
                assertNotNull("Current replacement view must be parented", replacement.parent)
                assertTrue("Current replacement view must be attached", replacement.isAttachedToWindow)
                assertNull("Previous view must be removed from its parent", first.parent)
                assertFalse("Previous view must be detached", first.isAttachedToWindow)
            }
        } finally {
            composeRule.runOnIdle { showRow = false }
            composeRule.waitForIdle()
        }
    }

    // The framework creates these views in production; Robolectric exposes their hidden constructors.
    private fun inlineContentView(context: Context): InlineContentView =
        InlineContentView::class.java.getDeclaredConstructor(Context::class.java).newInstance(context).apply {
            layoutParams = ViewGroup.LayoutParams(120, 48)
        }

    private fun inlineSuggestionInfo(): InlineSuggestionInfo {
        val spec = InlinePresentationSpec.Builder(Size(120, 48), Size(120, 48)).build()
        return InlineSuggestionInfo::class.java.getDeclaredMethod(
            "newInlineSuggestionInfo",
            InlinePresentationSpec::class.java,
            String::class.java,
            Array<String>::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).invoke(
            null,
            spec,
            InlineSuggestionInfo.SOURCE_AUTOFILL,
            null,
            InlineSuggestionInfo.TYPE_SUGGESTION,
            false,
        ) as InlineSuggestionInfo
    }
}
