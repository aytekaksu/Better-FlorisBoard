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

package dev.patrickgold.florisboard.ime.media.emoji

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.emoji2.widget.EmojiTextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmojiTextAndroidTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compatibilityChangeRecreatesTheCorrectTextView() {
        var useEmojiCompatView by mutableStateOf(false)
        composeRule.setContent {
            EmojiText(text = "🙂", useEmojiCompatView = useEmojiCompatView)
        }

        val plain = composeRule.runOnIdle { emojiTextView() }
        assertEquals(TextView::class.java, plain.javaClass)

        composeRule.runOnIdle { useEmojiCompatView = true }
        val compatible = composeRule.runOnIdle { emojiTextView() }
        assertTrue(compatible is EmojiTextView)
        assertNotSame(plain, compatible)

        composeRule.runOnIdle { useEmojiCompatView = false }
        val plainAgain = composeRule.runOnIdle { emojiTextView() }
        assertEquals(TextView::class.java, plainAgain.javaClass)
        assertNotSame(compatible, plainAgain)
    }

    private fun emojiTextView(): TextView = requireNotNull(
        composeRule.activity.window.decorView.descendantTextViews().singleOrNull { it.text.toString() == "🙂" },
    )
}

private fun View.descendantTextViews(): List<TextView> = buildList {
    if (this@descendantTextViews is TextView) add(this@descendantTextViews)
    if (this@descendantTextViews is ViewGroup) {
        for (index in 0 until childCount) addAll(getChildAt(index).descendantTextViews())
    }
}
