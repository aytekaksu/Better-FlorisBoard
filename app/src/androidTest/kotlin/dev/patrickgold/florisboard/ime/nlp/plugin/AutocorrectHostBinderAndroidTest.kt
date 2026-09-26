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

package dev.patrickgold.florisboard.ime.nlp.plugin

import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.PreferenceStoreInitializationState
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.AbstractEditorInstance
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.florisboard.autocorrect.host.core.BindingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real host against a protocol-v5 Messenger service in a separate fixture app. */
@RunWith(AndroidJUnit4::class)
class AutocorrectHostBinderAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val prefs by FlorisPreferenceStore
    private val providerComponent = ComponentName(
        FIXTURE_PACKAGE,
        FIXTURE_SERVICE,
    )
    private val secondProviderComponent = ComponentName(FIXTURE_PACKAGE, FIXTURE_SECOND_SERVICE)
    private lateinit var manager: AutocorrectPluginManager
    private var oldProviderId: String? = null
    private var oldSuggestionsEnabled: Boolean? = null

    @Before
    fun selectSyntheticProvider(): Unit = runBlocking {
        val application = targetContext.applicationContext as FlorisApplication
        assertEquals(
            PreferenceStoreInitializationState.READY,
            withTimeout(20_000) {
                application.preferenceStoreInitializationState.first {
                    it != PreferenceStoreInitializationState.LOADING
                }
            },
        )
        val serviceInfo = targetContext.packageManager.getServiceInfo(providerComponent, 0)
        val secondServiceInfo = targetContext.packageManager.getServiceInfo(secondProviderComponent, 0)
        val controlInfo = targetContext.packageManager.getProviderInfo(
            ComponentName(FIXTURE_PACKAGE, "$FIXTURE_PACKAGE.SyntheticAutocorrectControlProvider"),
            0,
        )
        assertNotEquals(targetContext.applicationInfo.uid, serviceInfo.applicationInfo.uid)
        assertNotEquals(targetContext.packageName, serviceInfo.processName)
        assertEquals(serviceInfo.applicationInfo.uid, secondServiceInfo.applicationInfo.uid)
        assertEquals(serviceInfo.processName, secondServiceInfo.processName)
        assertEquals(CONTROL_PERMISSION, controlInfo.readPermission)
        assertEquals(PackageManager.PERMISSION_GRANTED, targetContext.checkSelfPermission(CONTROL_PERMISSION))
        oldProviderId = prefs.suggestion.autocorrectPluginComponent.get()
        oldSuggestionsEnabled = prefs.suggestion.enabled.get()
        manager = targetContext.autocorrectPluginManager().value
        control("release_finish")
        control("release_suggest_b")
        manager.finishSession()
        prefs.suggestion.enabled.set(true).getOrThrow()
        prefs.suggestion.autocorrectPluginComponent
            .set(providerComponent.flattenToString()).getOrThrow()
        manager.onSelectedProviderChanged()
        manager.refreshProviders()
        awaitStableEditorGeneration()
        control("reset")
    }

    @After
    fun restoreSelection() {
        if (!::manager.isInitialized) return
        control("release_finish")
        control("release_suggest_b")
        manager.releasePluginUi()
        manager.finishSession()
        val eventsBeforeRestore = snapshot().events
        val bindingBeforeRestore = manager.hostStateSnapshot().binding
        runBlocking {
            oldProviderId?.let {
                prefs.suggestion.autocorrectPluginComponent.set(it).getOrThrow()
            }
            oldSuggestionsEnabled?.let { prefs.suggestion.enabled.set(it).getOrThrow() }
        }
        manager.onSelectedProviderChanged()
        awaitHostCommandBarrier()
        if (bindingBeforeRestore != BindingState.Unbound &&
            eventsBeforeRestore.lastIndexOfAny("UI_REQUEST", "START") >
            eventsBeforeRestore.lastIndexOf("UNBOUND")
        ) {
            waitForEventCount("UNBOUND", eventsBeforeRestore.count { it == "UNBOUND" } + 1)
        }
        if (bindingBeforeRestore != BindingState.Unbound &&
            eventsBeforeRestore.lastIndexOfAny("B_UI_REQUEST", "B_START") >
            eventsBeforeRestore.lastIndexOf("B_UNBOUND")
        ) {
            waitForEventCount("B_UNBOUND", eventsBeforeRestore.count { it == "B_UNBOUND" } + 1)
        }
    }

    @Test
    fun uiOnlyDemandBindsAndReleasesWithoutStartingATypingSession() {
        manager.acquirePluginUi()
        val bound = waitForEvent("UI_REQUEST")
        assertFalse(bound.events.contains("START"))
        assertNotEquals(Process.myPid(), bound.pid)
        assertNotEquals(Process.myUid(), bound.uid)

        manager.releasePluginUi()
        val released = waitForEvent("UNBOUND")
        assertFalse(released.events.contains("START"))
        assertFalse(released.events.contains("SUGGEST"))
        assertTrue(released.events.indexOf("UNBOUND") > released.events.indexOf("UI_REQUEST"))
    }

    @Test
    fun providerReselectionRevokesPickerLeaseWithoutGhostUiBinding() {
        manager.acquirePluginUi()
        waitForEvent("UI_REQUEST")
        val pickerLease = requireNotNull(manager.acquirePluginUiPickerLease())
        manager.releasePluginUi()
        assertFalse(snapshot().events.contains("UNBOUND"))

        manager.onSelectedProviderChanged()
        val released = waitForEvent("UNBOUND")
        assertNull(manager.acquirePluginUiPickerLease())
        manager.sendPluginUiDocument(
            itemId = "stale-fixture-document",
            uri = Uri.parse("content://$FIXTURE_PACKAGE.control/stale-document"),
            write = false,
            pickerLease = pickerLease,
        )
        assertTrue(manager.pluginUiError.value)
        awaitHostCommandBarrier()
        assertEquals(
            released.events.count { it == "UI_REQUEST" },
            snapshot().events.count { it == "UI_REQUEST" },
        )
    }

    @Test
    fun providerSwitchCannotRouteUiToTheOldPhysicalConnection() {
        manager.acquirePluginUi()
        waitForEvent("UI_REQUEST")
        synchronized(manager) {
            runBlocking {
                prefs.suggestion.autocorrectPluginComponent
                    .set(secondProviderComponent.flattenToString()).getOrThrow()
            }
            manager.onSelectedProviderChanged()
            assertNull("B picker lease issued while A is still physical", manager.acquirePluginUiPickerLease())
            manager.invokePluginUiAction("switch-probe")
        }

        val switched = waitForEvent("B_UI_REQUEST")
        assertEquals(1, switched.events.count { it == "UI_REQUEST" })
        assertFalse(switched.events.contains("UI_ACTION"))
        assertFalse(switched.events.contains("B_UI_ACTION"))
        assertTrue(switched.events.indexOf("UNBOUND") < switched.events.indexOf("B_UI_REQUEST"))
        manager.invokePluginUiAction("switch-probe")
        waitForEvent("B_UI_ACTION")
    }

    @Test
    fun disconnectedPickerLeaseDoesNotKeepUiBindingDemandAlive() {
        manager.acquirePluginUi()
        val first = waitForEvent("UI_REQUEST")
        val pickerLease = requireNotNull(manager.acquirePluginUiPickerLease())
        manager.releasePluginUi()
        val bindingsBeforeDeath = bindingStartCount()
        assertEquals(first.pid, control("kill_provider").getInt("pid"))

        val deadline = SystemClock.uptimeMillis() + 20_000
        do {
            val state = manager.hostStateSnapshot()
            if (state.binding == BindingState.Unbound && !state.uiBindingDemand) break
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        val idle = manager.hostStateSnapshot()
        assertEquals(BindingState.Unbound, idle.binding)
        assertFalse(idle.uiBindingDemand)
        manager.sendPluginUiDocument(
            itemId = "stale-fixture-document",
            uri = Uri.parse("content://$FIXTURE_PACKAGE.control/stale-document"),
            write = false,
            pickerLease = pickerLease,
        )
        assertTrue(manager.pluginUiError.value)
        awaitHostCommandBarrier()
        assertEquals(bindingsBeforeDeath, bindingStartCount())
        assertEquals(1, snapshot().events.count { it == "UI_REQUEST" })
    }

    @Test
    fun sessionStartsBeforeSuggestAndHeldFinishKeepsItsBinding(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        try {
            val result = suggestOnce(manager.captureEditorGeneration())
            assertTrue(result.handled)
            assertTrue(result.candidates.isEmpty())
            val suggested = waitForEvent("SUGGEST")
            assertTrue(suggested.events.indexOf("START") < suggested.events.indexOf("SUGGEST"))

            control("hold_finish")
            manager.finishSession()
            waitForEvent("FINISH")
            assertTrue(manager.hostStateSnapshot().pendingFinishes.isNotEmpty())
            awaitHostCommandBarrier()
            assertFalse("binding released before finish acknowledgement", snapshot().events.contains("UNBOUND"))

            control("release_finish")
            val released = waitForEvent("UNBOUND")
            assertTrue(released.events.indexOf("SUGGEST") < released.events.indexOf("FINISH"))
            assertTrue(released.events.indexOf("FINISH") < released.events.indexOf("UNBOUND"))
        } finally {
            control("release_finish")
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun normalDoubleFinishRetainsClosureContentButPrivateFinishDoesNot(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val keyboard by targetContext.keyboardManager()
        val oldInfo = editor.activeInfo
        val oldPrivate = keyboard.activeState.isIncognitoMode
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        var restoreContent: () -> Unit = {}
        try {
            keyboard.activeState.isIncognitoMode = false
            assertTrue(suggestOnce(manager.captureEditorGeneration()).handled)
            synchronized(manager) {
                restoreContent = installSyntheticEditorContent(editor, syntheticEditorContent())
                manager.finishSession()
                manager.finishSession() // onFinishInputView followed by onFinishInput.
                restoreContent()
            }
            waitForEvent("FINISH_CONTENT_PRESENT")
            keyboard.activeState.isIncognitoMode = true
            manager.finishSession() // Stop any editor observer request started after the first close.
            waitForEvent("UNBOUND")

            keyboard.activeState.isIncognitoMode = false
            control("reset")
            editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
            assertTrue(suggestOnce(manager.captureEditorGeneration()).handled)
            synchronized(manager) {
                restoreContent = installSyntheticEditorContent(editor, syntheticEditorContent())
                keyboard.activeState.isIncognitoMode = true
                manager.finishSession()
                restoreContent()
            }
            waitForEvent("FINISH_CONTENT_EMPTY")
            waitForEvent("UNBOUND")
        } finally {
            keyboard.activeState.isIncognitoMode = oldPrivate
            restoreContent()
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun learningPolicyChangeStartsANewSessionBeforeNextSuggestion(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        try {
            editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
            val generation = manager.captureEditorGeneration()
            suggestOnce(generation)
            waitForEvent("START")
            editor.handleStartInput(
                plainTextEditorInfo(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            )
            assertEquals(generation, manager.captureEditorGeneration())
            suggestOnce(generation)
            val events = waitForEventCount("START", 2).events
            val finish = events.indexOf("FINISH")
            assertTrue("events=$events", finish > events.indexOf("START"))
            assertTrue("events=$events", events.lastIndexOf("START") > finish)
        } finally {
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun learningPolicyFlipOrdersFinishBeforeNextStartEvenWhileAckIsHeld(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        try {
            editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
            val generation = manager.captureEditorGeneration()
            assertTrue(suggestOnce(generation).handled)
            control("hold_finish")
            editor.handleStartInput(
                plainTextEditorInfo(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
            )
            val next = async(Dispatchers.Default) { suggestOnce(generation) }
            val held = waitForEvent("FINISH")
            awaitHostCommandBarrier()
            val pending = manager.hostStateSnapshot()
            assertTrue(pending.pendingFinishes.isNotEmpty())
            assertFalse(pending.session?.configuration?.allowPersonalizedLearning ?: true)
            assertFalse("provider serializes callbacks behind FINISH", next.isCompleted)
            assertFalse("new session must retain the binding", held.events.contains("UNBOUND"))
            control("release_finish")
            assertTrue(withTimeout(20_000) { next.await() }.handled)
            val events = waitForEventCount("SUGGEST", 2).events
            assertTrue(events.indexOf("FINISH") > events.indexOf("SUGGEST"))
            assertTrue(events.lastIndexOf("START") > events.indexOf("FINISH"))
            assertTrue(events.lastIndexOf("SUGGEST") > events.lastIndexOf("START"))
        } finally {
            control("release_finish")
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun providerProcessDeathReconnectsWithANewEpochBeforeSuggestingAgain(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        try {
            val generation = manager.captureEditorGeneration()
            assertTrue(suggestOnce(generation).handled)
            val first = waitForEvent("SUGGEST")
            val firstEpoch = requireNotNull(latestConnectedBindingEpoch())
            assertEquals(first.pid, control("kill_provider").getInt("pid"))

            val reconnected = waitForReconnect(first.pid, firstEpoch)
            assertNotEquals(first.pid, reconnected.pid)
            assertTrue(requireNotNull(latestConnectedBindingEpoch()) > firstEpoch)
            assertTrue(suggestOnce(generation).handled)
            val events = waitForEventCount("SUGGEST", 2).events
            val firstSuggest = events.indexOf("SUGGEST")
            val secondStart = events.lastIndexOf("START")
            val secondSuggest = events.lastIndexOf("SUGGEST")
            assertTrue("events=$events", firstSuggest < secondStart)
            assertTrue("events=$events", secondStart < secondSuggest)
        } finally {
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun providerSwitchWithHeldFinishRejectsOldEpochReply(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        try {
            val generation = manager.captureEditorGeneration()
            assertTrue(suggestOnce(generation).handled)
            val oldReply = currentReplyMessenger()
            control("hold_finish")
            control("hold_suggest_b")
            manager.finishSession()
            waitForEvent("FINISH")
            assertTrue(manager.hostStateSnapshot().pendingFinishes.isNotEmpty())
            prefs.suggestion.autocorrectPluginComponent
                .set(secondProviderComponent.flattenToString()).getOrThrow()
            manager.onSelectedProviderChanged()
            awaitStableEditorGeneration()
            val next = async(Dispatchers.Default) { suggestOnce(manager.captureEditorGeneration()) }
            val bSuggested = waitForEvent("B_SUGGEST")
            val events = bSuggested.events
            assertTrue("events=$events", events.indexOf("SUGGEST") < events.indexOf("FINISH"))
            assertTrue("events=$events", events.indexOf("FINISH") < events.indexOf("UNBOUND"))
            assertTrue("events=$events", events.indexOf("UNBOUND") < events.indexOf("B_START"))
            assertTrue("B must proceed before A FINISH ACK", manager.hostStateSnapshot().pendingFinishes.isEmpty())

            val staleBefore = staleReplyCount()
            val requestId = snapshot().latestBRequestId
            assertTrue(requestId > 0L)
            val sent = control("send_stale_suggestions", Bundle().apply {
                putParcelable("reply_to", oldReply)
                putLong("request_id", requestId)
            }).getBoolean("sent")
            assertTrue(sent)
            waitForStaleReplyCount(staleBefore + 1)
            assertFalse("stale A reply completed B's suggestion", next.isCompleted)

            control("release_suggest_b")
            assertTrue(withTimeout(20_000) { next.await() }.handled)
            waitForEvent("B_SUGGEST")
        } finally {
            control("release_suggest_b")
            control("release_finish")
            editor.handleStartInput(oldInfo)
        }
    }

    private suspend fun suggestOnce(generation: Long) = withTimeout(20_000) {
        manager.suggestWithStatus(
            subtype = Subtype.DEFAULT,
            content = syntheticEditorContent(),
            maxCandidateCount = 3,
            allowPossiblyOffensive = false,
            isPrivateSession = false,
            requestEditorGeneration = generation,
        )
    }

    private fun syntheticEditorContent() = EditorContent(
        text = "abc",
        offset = 0,
        localSelection = EditorRange.cursor(3),
        localComposing = EditorRange.Unspecified,
        localCurrentWord = EditorRange(0, 3),
    )

    private fun installSyntheticEditorContent(
        editor: AbstractEditorInstance,
        content: EditorContent,
    ): () -> Unit {
        val field = AbstractEditorInstance::class.java.getDeclaredField("_activeContentFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val contentFlow = field.get(editor) as MutableStateFlow<EditorContent>
        val previous = contentFlow.value
        contentFlow.value = content
        return { contentFlow.value = previous }
    }

    private fun plainTextEditorInfo(imeOptions: Int) = FlorisEditorInfo.wrap(
        EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            this.imeOptions = imeOptions
        },
    )

    private fun control(method: String, extras: Bundle? = null): Bundle = checkNotNull(
        targetContext.contentResolver.call(CONTROL_URI, method, null, extras),
    )

    private fun snapshot(): Snapshot = control("snapshot").let { bundle ->
        Snapshot(
            events = bundle.getStringArrayList("events").orEmpty(),
            pid = bundle.getInt("pid"),
            uid = bundle.getInt("uid"),
            latestBRequestId = bundle.getLong("latest_b_request_id"),
        )
    }

    private fun waitForEvent(event: String): Snapshot {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            val state = snapshot()
            if (event in state.events) return state
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Synthetic provider did not observe $event; events=${snapshot().events}")
    }

    private fun waitForEventCount(event: String, count: Int): Snapshot {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            val state = snapshot()
            if (state.events.count { it == event } >= count) return state
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Synthetic provider observed fewer than $count $event events; events=${snapshot().events}")
    }

    private fun latestConnectedBindingEpoch(): Long? = manager.diagnosticsSnapshot().records
        .mapNotNull { record ->
            (record.event as? AutocorrectPluginDiagnosticEvent.Binding)
                ?.takeIf { it.state == AutocorrectPluginDiagnosticState.CONNECTED }
                ?.bindingEpoch
        }
        .lastOrNull()

    private fun bindingStartCount() = manager.diagnosticsSnapshot().records.count { record ->
        (record.event as? AutocorrectPluginDiagnosticEvent.Binding)?.state ==
            AutocorrectPluginDiagnosticState.STARTED
    }

    private fun currentReplyMessenger(): Messenger =
        AutocorrectPluginManager::class.java.getDeclaredField("replyMessenger").let { field ->
            field.isAccessible = true
            field.get(manager) as Messenger
        }

    private fun staleReplyCount() = manager.diagnosticsSnapshot().records.count { record ->
        val event = record.event as? AutocorrectPluginDiagnosticEvent.ReplyRejected
        event?.error == AutocorrectPluginDiagnosticError.STALE_BINDING
    }

    private fun waitForStaleReplyCount(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (staleReplyCount() < expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue("old-epoch reply was not rejected", staleReplyCount() >= expected)
    }

    private fun discoveryFinishCount() = manager.diagnosticsSnapshot().records.count { record ->
        (record.event as? AutocorrectPluginDiagnosticEvent.Discovery)?.state in setOf(
            AutocorrectPluginDiagnosticState.SUCCEEDED,
            AutocorrectPluginDiagnosticState.FAILED,
        )
    }

    private fun awaitHostCommandBarrier() {
        val previous = discoveryFinishCount()
        manager.refreshProviders()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (discoveryFinishCount() == previous && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue("host command barrier did not complete", discoveryFinishCount() > previous)
    }

    private fun List<String>.lastIndexOfAny(first: String, second: String) =
        maxOf(lastIndexOf(first), lastIndexOf(second))

    private fun waitForReconnect(previousPid: Int, previousEpoch: Long): Snapshot {
        val deadline = SystemClock.uptimeMillis() + 20_000
        do {
            // A control call can race the deliberate process death. Retry that transient failure.
            val state = runCatching(::snapshot).getOrNull()
            if (
                state != null && state.pid != previousPid &&
                state.events.count { it == "START" } >= 2 &&
                (latestConnectedBindingEpoch() ?: 0L) > previousEpoch
            ) {
                return state
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Provider did not reconnect with a new binding epoch")
    }

    private fun awaitStableEditorGeneration() {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var previous = manager.captureEditorGeneration()
        var stableChecks = 0
        do {
            SystemClock.sleep(50)
            val current = manager.captureEditorGeneration()
            stableChecks = if (current == previous) stableChecks + 1 else 0
            if (stableChecks == 4) return
            previous = current
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Manager editor generation never stabilized during fixture setup")
    }

    private data class Snapshot(
        val events: List<String>,
        val pid: Int,
        val uid: Int,
        val latestBRequestId: Long,
    )

    private companion object {
        const val FIXTURE_PACKAGE = "org.florisboard.autocorrect.fixture"
        const val FIXTURE_SERVICE = "$FIXTURE_PACKAGE.SyntheticAutocorrectProviderService"
        const val FIXTURE_SECOND_SERVICE = "$FIXTURE_PACKAGE.SyntheticAutocorrectProviderServiceB"
        const val CONTROL_PERMISSION = "$FIXTURE_PACKAGE.permission.CONTROL"
        val CONTROL_URI = Uri.parse("content://$FIXTURE_PACKAGE.control")
    }
}
