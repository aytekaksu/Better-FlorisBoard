/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.PreferenceStoreInitializationState
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSyncBehavior
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeJsonConfig
import dev.patrickgold.florisboard.ime.nlp.han.HanShapeBasedLanguageProvider
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.sharedActionsController
import dev.patrickgold.florisboard.smartbarCandidateController
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NlpManagerFlowAndroidTest {
    @Before
    fun awaitPreferenceStore(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as FlorisApplication
        assertEquals(
            PreferenceStoreInitializationState.READY,
            withTimeout(20_000L) {
                application.preferenceStoreInitializationState.first {
                    it != PreferenceStoreInitializationState.LOADING
                }
            },
        )
    }

    @Test
    fun suggestionPolicyReadsTheLiveSubtypeAfterSwitches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nlp by context.nlpManager()
        val subtypeManager by context.subtypeManager()
        val prefs by FlorisPreferenceStore
        val originalSubtypeJson = prefs.localization.subtypes.get()
        val originalSubtypes = if (originalSubtypeJson.isBlank()) emptyList() else
            SubtypeJsonConfig.decodeFromString<List<Subtype>>(originalSubtypeJson)
        val originalSubtypeId = prefs.localization.activeSubtypeId.get()
        val originalSuggestionEnabled = prefs.suggestion.enabled.get()
        val originalEmojiEnabled = prefs.emoji.suggestionEnabled.get()
        val originalPluginComponent = prefs.suggestion.autocorrectPluginComponent.get()
        val occupiedIds = originalSubtypes.mapTo(mutableSetOf()) { it.id }
        val temporaryIds = generateSequence(Long.MIN_VALUE) { it + 1L }
            .filterNot { it in occupiedIds }.take(2).toList()
        val latin = Subtype.DEFAULT.copy(id = temporaryIds[0])
        val han = latin.copy(
            id = temporaryIds[1],
            nlpProviders = latin.nlpProviders.copy(suggestion = HanShapeBasedLanguageProvider.ProviderId),
        )
        val testSubtypes = originalSubtypes + latin + han

        try {
            runBlocking {
                prefs.suggestion.enabled.set(false).getOrThrow()
                prefs.emoji.suggestionEnabled.set(false).getOrThrow()
                prefs.suggestion.autocorrectPluginComponent.set("").getOrThrow()
                prefs.localization.activeSubtypeId.set(latin.id).getOrThrow()
                prefs.localization.subtypes.set(SubtypeJsonConfig.encodeToString(testSubtypes)).getOrThrow()
                withTimeout(10_000L) { subtypeManager.subtypesFlow.first { it == testSubtypes } }
                subtypeManager.switchToSubtypeById(latin.id).join()
            }
            assertEquals(latin.id, subtypeManager.activeSubtype.id)
            assertFalse(nlp.isSuggestionOn())

            runBlocking { subtypeManager.switchToSubtypeById(han.id).join() }
            assertEquals(han.id, subtypeManager.activeSubtype.id)
            assertTrue(nlp.isSuggestionOn())

            runBlocking {
                prefs.suggestion.autocorrectPluginComponent.set("test.external.provider").getOrThrow()
            }
            assertFalse(nlp.isSuggestionOn())
            runBlocking { prefs.suggestion.autocorrectPluginComponent.set("").getOrThrow() }
            assertTrue(nlp.isSuggestionOn())

            runBlocking { subtypeManager.switchToSubtypeById(latin.id).join() }
            assertEquals(latin.id, subtypeManager.activeSubtype.id)
            assertFalse(nlp.isSuggestionOn())
        } finally {
            runBlocking {
                val restores = listOf<suspend () -> Unit>(
                    { prefs.localization.activeSubtypeId.set(originalSubtypeId).getOrThrow() },
                    { prefs.localization.subtypes.set(originalSubtypeJson).getOrThrow() },
                    { withTimeout(10_000L) { subtypeManager.subtypesFlow.first { it == originalSubtypes } } },
                    {
                        if (originalSubtypes.any { it.id == originalSubtypeId }) {
                            subtypeManager.switchToSubtypeById(originalSubtypeId).join()
                            assertEquals(originalSubtypeId, subtypeManager.activeSubtype.id)
                        }
                    },
                    { prefs.suggestion.enabled.set(originalSuggestionEnabled).getOrThrow() },
                    { prefs.emoji.suggestionEnabled.set(originalEmojiEnabled).getOrThrow() },
                    { prefs.suggestion.autocorrectPluginComponent.set(originalPluginComponent).getOrThrow() },
                )
                restores.mapNotNull { runCatching { it() }.exceptionOrNull() }
                    .firstOrNull()?.let { throw it }
            }
        }
    }

    @Test
    fun clipboardSuggestionsUseTheCurrentClipAfterEachChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard by context.clipboardManager()
        val nlp by context.nlpManager()
        val candidates by context.smartbarCandidateController()
        val editor by context.editorInstance()
        val keyboard by context.keyboardManager()
        val sharedActions by context.sharedActionsController()
        val prefs by FlorisPreferenceStore
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runBlocking { withTimeout(10_000L) { clipboard.awaitInitialization() } }
        assertTrue("clipboard fixture requires an idle editor", editor.activeContent.text.isBlank())

        val originalClip = clipboard.primaryClip
        val originalInternalClipboard = prefs.clipboard.useInternalClipboard.get()
        val originalSyncToFloris = prefs.clipboard.syncToFloris.get()
        val originalSyncToSystem = prefs.clipboard.syncToSystem.get()
        val originalSuggestionEnabled = prefs.clipboard.suggestionEnabled.get()
        val originalSuggestionTimeout = prefs.clipboard.suggestionTimeout.get()
        val originalWordSuggestions = prefs.suggestion.enabled.get()
        val originalPluginComponent = prefs.suggestion.autocorrectPluginComponent.get()
        val originalExpanded = prefs.smartbar.sharedActionsExpanded.get()
        var originalIncognito = false
        instrumentation.runOnMainSync { originalIncognito = keyboard.activeState.isIncognitoMode }

        fun waitUntil(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(20L)
            }
            throw AssertionError(message)
        }

        fun restorePreferences() = runBlocking {
            val restores = listOf<suspend () -> Unit>(
                { prefs.clipboard.useInternalClipboard.set(originalInternalClipboard).getOrThrow() },
                { prefs.clipboard.syncToFloris.set(originalSyncToFloris).getOrThrow() },
                { prefs.clipboard.syncToSystem.set(originalSyncToSystem).getOrThrow() },
                { prefs.clipboard.suggestionEnabled.set(originalSuggestionEnabled).getOrThrow() },
                { prefs.clipboard.suggestionTimeout.set(originalSuggestionTimeout).getOrThrow() },
                { prefs.suggestion.enabled.set(originalWordSuggestions).getOrThrow() },
                { prefs.suggestion.autocorrectPluginComponent.set(originalPluginComponent).getOrThrow() },
            )
            restores.mapNotNull { runCatching { it() }.exceptionOrNull() }
                .firstOrNull()?.let { throw it }
        }

        try {
            // Keep test clip updates inside FlorisBoard, without writing Android's clipboard.
            runBlocking {
                prefs.clipboard.syncToFloris.set(ClipboardSyncBehavior.NO_EVENTS).getOrThrow()
                prefs.clipboard.syncToSystem.set(ClipboardSyncBehavior.NO_EVENTS).getOrThrow()
                prefs.clipboard.useInternalClipboard.set(true).getOrThrow()
                prefs.clipboard.suggestionEnabled.set(true).getOrThrow()
                prefs.clipboard.suggestionTimeout.set(60).getOrThrow()
                prefs.suggestion.autocorrectPluginComponent.set("").getOrThrow()
                prefs.suggestion.enabled.set(true).getOrThrow()
            }
            instrumentation.runOnMainSync { keyboard.activeState.isIncognitoMode = false }
            nlp.clearSuggestions()
            fun suggestedText(): String? =
                candidates.activeCandidatesFlow.value.filterIsInstance<ClipboardSuggestionCandidate>()
                    .singleOrNull()?.sourceClipboardItem?.text

            for (text in listOf("nlp-flow-first", "nlp-flow-second")) {
                clipboard.updatePrimaryClip(ClipboardItem.text(text))
                waitUntil("test clip did not become primary") { clipboard.primaryClip?.text == text }
                waitUntil("current clip did not reach the app candidate owner") { suggestedText() == text }
                assertEquals(text, suggestedText())
            }
            clipboard.updatePrimaryClip(null)
            waitUntil("test clip was not cleared") { clipboard.primaryClip == null }
            waitUntil("null clip did not reach the app candidate owner") { suggestedText() == null }
            assertEquals(null, suggestedText())
        } finally {
            try {
                clipboard.updatePrimaryClip(originalClip)
                waitUntil("original clip was not restored") { clipboard.primaryClip == originalClip }
            } finally {
                try {
                    restorePreferences()
                } finally {
                    instrumentation.runOnMainSync { keyboard.activeState.isIncognitoMode = originalIncognito }
                    nlp.clearSuggestions()
                    runBlocking { candidates.refresh() }
                    sharedActions.setExpandedByUser(originalExpanded)
                    waitUntil("original shared actions state was not restored") {
                        prefs.smartbar.sharedActionsExpanded.get() == originalExpanded
                    }
                }
            }
        }
    }
}
