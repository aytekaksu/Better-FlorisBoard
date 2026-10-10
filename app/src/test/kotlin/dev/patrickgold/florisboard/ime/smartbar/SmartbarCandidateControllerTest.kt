/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar

import android.app.Application
import android.app.KeyguardManager
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.FallbackNlpProvider
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidateKind
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.plugin.AutocorrectPluginManager
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class SmartbarCandidateControllerTest {
    @Test
    fun publicationCombinesTheRealSourcesAndKeepsHiddenAutoCommit() = runTest {
        val fixture = CandidateFixture(backgroundScope)
        fixture.prefs.clipboard.suggestionEnabled.set(true).getOrThrow()
        fixture.clip.set(ClipboardItem.text("clipboard"))
        runCurrent()
        val provider = object : SuggestionProvider by FallbackNlpProvider {
            override val providerId = AutocorrectPluginManager.ProviderId
        }
        val cases = listOf(
            PriorityCase("typed", words("type", "typing", "typed"), true, listOf("type", "typing", "typed")),
            PriorityCase("", words("next", "word"), true, listOf("clipboard")),
            PriorityCase("typed", emptyList(), true, emptyList()),
            PriorityCase("", words("next", "word"), false, listOf("next", "word")),
            PriorityCase(
                "",
                listOf(
                    WordSuggestionCandidate(
                        "external",
                        sourceProvider = provider,
                        kind = SuggestionCandidateKind.CORRECTION,
                    ),
                ),
                true,
                listOf("external"),
            ),
            PriorityCase(
                "",
                listOf(
                    WordSuggestionCandidate(
                        "prediction",
                        sourceProvider = provider,
                        kind = SuggestionCandidateKind.NEXT_WORD,
                    ),
                ),
                true,
                listOf("clipboard"),
            ),
        )
        for (case in cases) {
            fixture.content = content(case.typed)
            fixture.prefs.clipboard.suggestionEnabled.set(case.clipboardEnabled).getOrThrow()
            fixture.controller.replaceCandidates(case.words)
            runCurrent()
            assertEquals(case.visible, fixture.controller.activeCandidatesFlow.value.map { it.text.toString() })
        }

        val hidden = object : SuggestionCandidate by WordSuggestionCandidate("hidden", isEligibleForAutoCommit = true) {
            override val isVisible = false
        }
        val visible = words("first", "second")
        fixture.content = content("typed")
        fixture.controller.replaceCandidates(listOf(hidden) + visible)
        runCurrent()
        val published = fixture.controller.activeCandidatesFlow.value
        assertEquals(listOf("first", "second"), published.map { it.text.toString() })
        assertSame(visible[0], published[0])
        assertSame(visible[1], published[1])
        assertEquals("hidden", fixture.controller.autoCommitCandidate?.text?.toString())
        assertSame(hidden, fixture.controller.autoCommitCandidate)
    }

    @Test
    fun liveClipboardCopiesDeliverNullAndSuppressOnlyTheAcceptedCopy() = runTest {
        val fixture = CandidateFixture(backgroundScope)
        fixture.prefs.clipboard.suggestionEnabled.set(true).getOrThrow()
        runCurrent()
        for (text in listOf("first", "second")) {
            fixture.clip.set(ClipboardItem.text(text))
            runCurrent()
            assertEquals(text, fixture.controller.activeCandidatesFlow.value.single().text.toString())
        }
        val accepted = fixture.controller.activeCandidatesFlow.value.single() as ClipboardSuggestionCandidate
        accepted.sourceProvider!!.notifySuggestionAccepted(Subtype.DEFAULT, accepted)
        fixture.controller.refresh()
        runCurrent()
        assertTrue(fixture.controller.activeCandidatesFlow.value.isEmpty())

        val acceptedCopy = accepted.sourceClipboardItem
        val recopy = acceptedCopy.copy(creationTimestampMs = acceptedCopy.creationTimestampMs + 1)
        fixture.clip.set(recopy)
        runCurrent()
        assertEquals(1, fixture.controller.activeCandidatesFlow.value.size)
        val removable = fixture.controller.activeCandidatesFlow.value.single() as ClipboardSuggestionCandidate
        assertSame(recopy, removable.sourceClipboardItem)
        assertTrue(removable.sourceProvider!!.removeSuggestion(Subtype.DEFAULT, removable))
        fixture.controller.refresh()
        runCurrent()
        assertTrue(fixture.controller.activeCandidatesFlow.value.isEmpty())

        fixture.clip.set(ClipboardItem.text("third"))
        runCurrent()
        assertEquals("third", fixture.controller.activeCandidatesFlow.value.single().text.toString())
        fixture.clip.set(null)
        runCurrent()
        assertTrue(fixture.controller.activeCandidatesFlow.value.isEmpty())
        assertNull(fixture.controller.autoCommitCandidate)
    }

    @Test
    fun inlineChangesAndSmartbarReenableUseTheCurrentPresentation() = runTest {
        val fixture = CandidateFixture(backgroundScope)
        fixture.prefs.clipboard.suggestionEnabled.set(false).getOrThrow()
        runCurrent()
        assertTrue(fixture.prefs.smartbar.sharedActionsExpanded.get())

        fixture.inline.value = listOf(Unit)
        runCurrent()
        assertFalse(fixture.prefs.smartbar.sharedActionsExpanded.get())
        fixture.content = content("selected").copy(localSelection = EditorRange(0, 8))
        fixture.controller.refresh()
        runCurrent()
        assertTrue(fixture.prefs.smartbar.sharedActionsExpanded.get())

        fixture.content = content("")
        fixture.prefs.smartbar.enabled.set(false).getOrThrow()
        runCurrent()
        fixture.inline.value = emptyList<Unit>()
        runCurrent()
        fixture.inline.value = listOf(Unit)
        runCurrent()
        assertTrue(fixture.prefs.smartbar.sharedActionsExpanded.get())
        fixture.prefs.smartbar.enabled.set(true).getOrThrow()
        runCurrent()
        assertFalse(fixture.prefs.smartbar.sharedActionsExpanded.get())
        fixture.inline.value = emptyList<Unit>()
        runCurrent()
        assertTrue(fixture.prefs.smartbar.sharedActionsExpanded.get())
    }

    @Test
    fun publicationRechecksClipboardPreferencePrivacyAndBothLocksAfterReadingTheClip() = runTest {
        for (guard in listOf("private", "device", "keyguard", "disabled")) {
            val fixture = CandidateFixture(backgroundScope)
            fixture.prefs.clipboard.suggestionEnabled.set(true).getOrThrow()
            fixture.clip.set(ClipboardItem.text("clip"))
            runCurrent()
            assertEquals("clip", fixture.controller.activeCandidatesFlow.value.single().text.toString())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.clip.onRead = {
                fixture.clip.onRead = null
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Clipboard input was not released" }
            }
            val completed = CountDownLatch(1)
            val pending = async(Dispatchers.Default) {
                try {
                    fixture.controller.refresh()
                } finally {
                    completed.countDown()
                }
            }
            try {
                check(entered.await(5, TimeUnit.SECONDS)) { "Clipboard assembly did not start" }
                when (guard) {
                    "private" -> fixture.privateSession = true
                    "device" -> fixture.keyguard.setIsDeviceLocked(true)
                    "keyguard" -> fixture.keyguard.setKeyguardLocked(true)
                    else -> fixture.prefs.clipboard.suggestionEnabled.set(false).getOrThrow()
                }
                release.countDown()
                // The preference refresh must not mask a missing final publication check.
                check(completed.await(5, TimeUnit.SECONDS)) { "Clipboard assembly did not finish" }
                assertTrue(
                    "Clipboard publication survived $guard",
                    fixture.controller.activeCandidatesFlow.value.isEmpty(),
                )
                assertNull(fixture.controller.autoCommitCandidate)
                pending.await()
                runCurrent()
            } finally {
                release.countDown()
                pending.cancelAndJoin()
                fixture.keyguard.setIsDeviceLocked(false)
                fixture.keyguard.setKeyguardLocked(false)
            }
        }
    }

    @Test
    fun replacementAndSynchronousClearRetireAnAssemblyWhichAlreadyCapturedItsWords() = runTest {
        for (clear in listOf(false, true)) {
            val fixture = CandidateFixture(backgroundScope)
            fixture.content = content("old")
            fixture.prefs.clipboard.suggestionEnabled.set(false).getOrThrow()
            fixture.controller.replaceCandidates(listOf(WordSuggestionCandidate("old", isEligibleForAutoCommit = true)))
            runCurrent()
            assertEquals(listOf("old"), fixture.controller.activeCandidatesFlow.value.map { it.text.toString() })
            assertEquals("old", fixture.controller.autoCommitCandidate?.text?.toString())
            assertFalse(fixture.prefs.smartbar.sharedActionsExpanded.get())

            val observed = CopyOnWriteArrayList<Pair<List<String>, String?>>()
            val observer = backgroundScope.launch(Dispatchers.Unconfined) {
                fixture.controller.activeCandidatesFlow.collect { candidates ->
                    observed += candidates.map { it.text.toString() } to
                        fixture.controller.autoCommitCandidate?.text?.toString()
                }
            }
            observed.clear()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val armed = AtomicBoolean(true)
            fixture.privacyReader = {
                val private = fixture.privateSession
                if (armed.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Privacy input was not released" }
                }
                private
            }
            val completed = CountDownLatch(1)
            val pending = async(Dispatchers.Default) {
                try {
                    fixture.controller.refresh()
                } finally {
                    completed.countDown()
                }
            }
            try {
                check(entered.await(5, TimeUnit.SECONDS)) { "Old assembly did not start" }
                val expected = if (clear) emptyList() else listOf("new")
                if (clear) {
                    fixture.controller.clearCandidates()
                    // These two reads happen before the queued empty refresh can run.
                    assertTrue(fixture.controller.activeCandidatesFlow.value.isEmpty())
                    assertNull(fixture.controller.autoCommitCandidate)
                } else {
                    fixture.controller.replaceCandidates(
                        listOf(WordSuggestionCandidate("new", isEligibleForAutoCommit = true)),
                    )
                    runCurrent()
                }
                val expectedAutoCommit = if (clear) null else "new"
                assertEquals(expected, fixture.controller.activeCandidatesFlow.value.map { it.text.toString() })
                assertEquals(expectedAutoCommit, fixture.controller.autoCommitCandidate?.text?.toString())

                release.countDown()
                if (clear) {
                    // Keep queued refreshes off the test scheduler until the old assembly returns.
                    check(completed.await(5, TimeUnit.SECONDS)) { "Old assembly did not finish" }
                    assertTrue(fixture.controller.activeCandidatesFlow.value.isEmpty())
                    assertNull(fixture.controller.autoCommitCandidate)
                }
                pending.await()
                runCurrent()
                assertEquals(listOf(expected to expectedAutoCommit), observed.toList())
                assertEquals(expected, fixture.controller.activeCandidatesFlow.value.map { it.text.toString() })
                assertEquals(expectedAutoCommit, fixture.controller.autoCommitCandidate?.text?.toString())
                assertEquals(clear, fixture.prefs.smartbar.sharedActionsExpanded.get())
            } finally {
                release.countDown()
                pending.cancelAndJoin()
                observer.cancel()
            }
        }
    }
}

private class CandidateFixture(scope: CoroutineScope) {
    private val context = RuntimeEnvironment.getApplication()
    private val dataStore = jetprefDataStoreOf(FlorisPreferenceModel::class)
    val prefs by dataStore
    val clip = ClipboardSource()
    val inline = MutableStateFlow<List<*>>(emptyList<Unit>())

    @Volatile var content = content("")

    @Volatile var privateSession = false

    @Volatile var privacyReader: () -> Boolean = { privateSession }
    val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java)).apply {
        setIsDeviceLocked(false)
        setKeyguardLocked(false)
    }
    val sharedActions = SharedActionsController(prefs.smartbar, scope) { content.selection.isSelectionMode }
    val controller = SmartbarCandidateController(
        context = context,
        prefs = prefs,
        clipboardPrimaryClipFlow = lazy { clip },
        inlineSuggestionsFlow = inline,
        scope = scope,
        isSuggestionOn = { true },
        currentEditorContent = { content },
        isIncognitoMode = { privacyReader() },
        sharedActions = sharedActions,
    )
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ClipboardSource(private val state: MutableStateFlow<ClipboardItem?> = MutableStateFlow(null)) :
    StateFlow<ClipboardItem?> by state {
    @Volatile var onRead: (() -> Unit)? = null
    override val value: ClipboardItem?
        get() = state.value.also { onRead?.invoke() }
    fun set(item: ClipboardItem?) {
        state.value = item
    }
}

private data class PriorityCase(
    val typed: String,
    val words: List<SuggestionCandidate>,
    val clipboardEnabled: Boolean,
    val visible: List<String>,
)

private fun words(vararg values: String) = values.map { WordSuggestionCandidate(it) }

private fun content(text: String) = EditorContent(
    text = text,
    offset = 0,
    localSelection = EditorRange.cursor(text.length),
    localComposing = EditorRange.Unspecified,
    localCurrentWord = if (text.isEmpty()) EditorRange.Unspecified else EditorRange(0, text.length),
)
