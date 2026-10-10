/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.media.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.EmojiSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmojiTextAndroidTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @SuppressLint("RestrictedApi")
    fun loadedFontUsesTheCurrentReplacementStrategy() {
        val previousEmojiCompat = if (EmojiCompat.isConfigured()) EmojiCompat.get() else null
        var showText by mutableStateOf(true)
        try {
            // Keep test APK assets when the bundled loader asks for the application context.
            val fontContext = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
                override fun getApplicationContext(): Context = this
            }
            val testConfig = BundledEmojiCompatConfig(
                fontContext,
                Executor { it.run() },
            ).setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
                .setReplaceAll(false)
                // Model a system-supported glyph; the real processor still creates the spans.
                .setGlyphChecker { _, _, _, _ -> true }
            val instance = EmojiCompat.reset(testConfig)
            val initialized = CompletableDeferred<Unit>()
            instance.registerInitCallback(object : EmojiCompat.InitCallback() {
                override fun onInitialized() {
                    initialized.complete(Unit)
                }

                override fun onFailed(throwable: Throwable?) {
                    initialized.completeExceptionally(throwable ?: IllegalStateException("Emoji font failed to load"))
                }
            })
            instance.load()
            runBlocking { withTimeout(10_000L) { initialized.await() } }

            var loadedEmojiCompat by mutableStateOf<EmojiCompat?>(null)
            var replaceAll by mutableStateOf(false)
            composeRule.setContent {
                if (showText) EmojiText(text = "🙂", loadedEmojiCompat = loadedEmojiCompat, replaceAll = replaceAll)
            }
            assertEmojiSpans(0)

            composeRule.runOnIdle {
                loadedEmojiCompat = instance
                replaceAll = true
            }
            assertEmojiSpans(1)

            composeRule.runOnIdle { replaceAll = false }
            assertEmojiSpans(0)

            composeRule.runOnIdle { replaceAll = true }
            assertEmojiSpans(1)
        } finally {
            try {
                // EmojiSpan drawing still reads the global instance; dispose before restoring it.
                composeRule.runOnIdle { showText = false }
                composeRule.waitForIdle()
            } finally {
                EmojiCompat.reset(previousEmojiCompat)
            }
        }
    }

    private fun assertEmojiSpans(expectedCount: Int) = composeRule.runOnIdle {
        val text = emojiTextView().text
        val spanned = text as? Spanned
        val spans = spanned?.getSpans(0, text.length, EmojiSpan::class.java).orEmpty()
        assertEquals(expectedCount, spans.size)
        for (span in spans) {
            val spanText = requireNotNull(spanned)
            assertEquals(0, spanText.getSpanStart(span))
            assertEquals(text.length, spanText.getSpanEnd(span))
        }
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
