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
import kotlinx.coroutines.cancelAndJoin
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
// One fixture lifecycle serves the Binder scenarios; splitting it would duplicate mutable IME setup.
@Suppress("LargeClass")
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
        control("release_ui_action")
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
        control("release_ui_action")
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
    fun malformedCorrelatedUiReplyKeepsSnapshotAndRevokesOnlyItsActionGrant() {
        openSecondProviderUi()
        val snapshot = manager.pluginUi.value
        sendRejectedUiReply(latestPluginUiRequestId(), "valid") // Completed GET has no ownership.
        assertEquals(snapshot, manager.pluginUi.value)
        control("hold_ui_action")
        manager.invokePluginUiAction("fixture-b-action")
        waitForEvent("B_UI_ACTION")
        val staleId = latestPluginUiRequestId()
        manager.invokePluginUiAction("fixture-b-action")
        val latestId = latestPluginUiRequestId()
        assertNotEquals(staleId, latestId)
        assertEquals(setOf(staleId, latestId), dictionaryActionGrantIds())

        sendRejectedUiReply(latestId + 10_000L, "valid") // A future ID is not a newer request.
        assertEquals(snapshot, manager.pluginUi.value)
        assertTrue(manager.pluginUiLoading.value)
        sendMalformedUiReply(0L, "malformed_pages") // A broken push owns no action grant.
        assertEquals(setOf(staleId, latestId), dictionaryActionGrantIds())
        assertTrue(manager.pluginUiLoading.value)

        sendMalformedUiReply(staleId, "malformed_pages")
        assertEquals(snapshot, manager.pluginUi.value)
        assertFalse(manager.pluginUiError.value)
        assertTrue(manager.pluginUiLoading.value)
        assertEquals(setOf(latestId), dictionaryActionGrantIds())

        sendMalformedUiReply(latestId, "malformed_pages")
        assertEquals(snapshot, manager.pluginUi.value)
        assertTrue(manager.pluginUiError.value)
        assertFalse(manager.pluginUiLoading.value)
        assertTrue(dictionaryActionGrantIds().isEmpty())
        assertTrue(pendingUiOperationIds().isEmpty())

        sendRejectedUiReply(latestId, "valid") // Delayed duplicate cannot clear the error.
        assertEquals(snapshot, manager.pluginUi.value)
        assertTrue(manager.pluginUiError.value)
        val tombstoneId = latestPluginUiRequestId()
        assertNotEquals(latestId, tombstoneId)
        sendRejectedUiReply(tombstoneId, "valid") // An unsent ID is not an active request.
        assertEquals(snapshot, manager.pluginUi.value)
        assertTrue(manager.pluginUiError.value)

        sendUiReply(0L, "valid") // A legitimate push can still refresh the page.
        val pushDeadline = SystemClock.uptimeMillis() + 10_000
        while (manager.pluginUi.value?.appRootPageId != "injected" &&
            SystemClock.uptimeMillis() < pushDeadline
        ) {
            SystemClock.sleep(25)
        }
        assertEquals("injected", manager.pluginUi.value?.appRootPageId)
        assertFalse(manager.pluginUiError.value)
        sendMalformedUiReply(latestId, "malformed_pages") // A duplicate cannot raise an error.
        assertFalse(manager.pluginUiError.value)
    }

    @Test
    fun missingUiReplyIdFailsPendingOperationsWithoutDroppingPickerLease() {
        openSecondProviderUi()
        val snapshot = manager.pluginUi.value
        val pickerLease = requireNotNull(manager.acquirePluginUiPickerLease())
        control("hold_ui_action")
        manager.invokePluginUiAction("fixture-b-action")
        waitForEvent("B_UI_ACTION")
        val requestId = latestPluginUiRequestId()
        assertTrue(requestId in dictionaryActionGrantIds())

        sendMalformedUiReply(requestId, "missing_id")
        assertEquals(snapshot, manager.pluginUi.value)
        assertTrue(manager.pluginUiError.value)
        assertFalse(manager.pluginUiLoading.value)
        assertTrue(dictionaryActionGrantIds().isEmpty())
        assertTrue(pendingUiOperationIds().isEmpty())
        assertTrue(pickerLease.id in activePickerLeaseIds())

        sendRejectedUiReply(requestId, "valid")
        assertEquals(snapshot, manager.pluginUi.value)
        assertTrue(manager.pluginUiError.value)

        manager.invokePluginUiAction("fixture-b-action")
        assertTrue(pendingUiOperationIds().isNotEmpty())
        sendMalformedUiReply(latestPluginUiRequestId(), "wrong_id")
        assertTrue(pendingUiOperationIds().isEmpty())
        assertTrue(dictionaryActionGrantIds().isEmpty())
        assertTrue(pickerLease.id in activePickerLeaseIds())
        manager.invokePluginUiAction("fixture-b-action")
        sendMalformedUiReply(latestPluginUiRequestId(), "wrong_ui")
        assertEquals(snapshot, manager.pluginUi.value)
        assertTrue(manager.pluginUiError.value)
        assertTrue(pickerLease.id in activePickerLeaseIds())
        manager.releasePluginUiPickerLease(pickerLease)
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
        val oldEpoch = requireNotNull(latestConnectedBindingEpoch())
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
        val newEpoch = requireNotNull(latestConnectedBindingEpoch())
        assertTrue(newEpoch > oldEpoch)
        val bindingEvents = manager.diagnosticsSnapshot().records.mapNotNull { record ->
            record.event as? AutocorrectPluginDiagnosticEvent.Binding
        }
        val oldDisconnect = bindingEvents.indexOfLast {
            it.bindingEpoch == oldEpoch && it.state == AutocorrectPluginDiagnosticState.DISCONNECTED
        }
        val newConnect = bindingEvents.indexOfLast {
            it.bindingEpoch == newEpoch && it.state == AutocorrectPluginDiagnosticState.CONNECTED
        }
        assertTrue(
            "old physical lease must release before B connects",
            oldDisconnect >= 0 && oldDisconnect < newConnect,
        )
        waitForEvent("UNBOUND") // Provider cleanup is asynchronous to the host's unbindService call.
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

    @Test
    fun malformedSuggestionWithCurrentIdFailsPromptlyAndUpdatesHealth(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        try {
            selectSecondProvider()
            control("hold_suggest_b")
            val suggestCount = snapshot().events.count { it == "B_SUGGEST" }
            val pending = async(Dispatchers.Default) { suggestOnce(manager.captureEditorGeneration()) }
            waitForEventCount("B_SUGGEST", suggestCount + 1)
            val lease = requireNotNull(manager.hostStateSnapshot().pendingRequest?.lease)
            assertEquals(snapshot().latestBRequestId, lease.requestId.value)
            val failuresBefore = manager.hostStateSnapshot().healthOf(lease.providerId).consecutiveFailures

            assertTrue(sendMalformedSuggestion(lease.requestId.value))
            assertFalse(withTimeout(10_000) { pending.await() }.handled)
            val state = manager.hostStateSnapshot()
            assertNull(state.pendingRequest)
            assertEquals(failuresBefore + 1, state.healthOf(lease.providerId).consecutiveFailures)
            assertMalformedFailure(lease.requestId.value)
        } finally {
            control("release_suggest_b")
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun malformedSuggestionWithoutIdFailsCurrentBindingAndAllowsNextRequest(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        try {
            selectSecondProvider()
            control("hold_suggest_b")
            val generation = manager.captureEditorGeneration()
            val suggestCount = snapshot().events.count { it == "B_SUGGEST" }
            val pending = async(Dispatchers.Default) { suggestOnce(generation) }
            waitForEventCount("B_SUGGEST", suggestCount + 1)
            val lease = requireNotNull(manager.hostStateSnapshot().pendingRequest?.lease)
            assertEquals(snapshot().latestBRequestId, lease.requestId.value)
            val failuresBefore = manager.hostStateSnapshot().healthOf(lease.providerId).consecutiveFailures

            assertTrue(sendMalformedSuggestion(requestId = null, corruptCandidates = false))
            assertFalse(withTimeout(10_000) { pending.await() }.handled)
            val state = manager.hostStateSnapshot()
            assertNull(state.pendingRequest)
            assertEquals(failuresBefore + 1, state.healthOf(lease.providerId).consecutiveFailures)
            assertMalformedFailure(lease.requestId.value)

            control("release_suggest_b")
            assertTrue(suggestOnce(generation).handled)
            waitForEventCount("B_SUGGEST", suggestCount + 2)
        } finally {
            control("release_suggest_b")
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun malformedSuggestionWithStaleIdCannotFailNewerRequest(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        try {
            selectSecondProvider()
            control("hold_suggest_b")
            val generation = manager.captureEditorGeneration()
            val suggestCount = snapshot().events.count { it == "B_SUGGEST" }
            val first = async(Dispatchers.Default) { suggestOnce(generation) }
            waitForEventCount("B_SUGGEST", suggestCount + 1)
            val oldRequestId = snapshot().latestBRequestId
            assertTrue(oldRequestId > 0L)

            val next = async(Dispatchers.Default) { suggestOnce(generation) }
            waitForEventCount("B_SUGGEST", suggestCount + 2)
            val lease = requireNotNull(manager.hostStateSnapshot().pendingRequest?.lease)
            assertEquals(snapshot().latestBRequestId, lease.requestId.value)
            assertNotEquals(oldRequestId, lease.requestId.value)
            val failuresBefore = manager.hostStateSnapshot().healthOf(lease.providerId).consecutiveFailures
            val rejectedBefore = malformedReplyRejectionCount()

            assertTrue(sendMalformedSuggestion(oldRequestId))
            waitForMalformedReplyRejectionCount(rejectedBefore + 1)
            assertFalse("stale malformed reply completed newer work", next.isCompleted)
            val state = manager.hostStateSnapshot()
            assertEquals(lease, state.pendingRequest?.lease)
            assertEquals(failuresBefore, state.healthOf(lease.providerId).consecutiveFailures)

            control("release_suggest_b")
            assertFalse(withTimeout(10_000) { first.await() }.handled)
            assertTrue(withTimeout(10_000) { next.await() }.handled)
        } finally {
            control("release_suggest_b")
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun consumedHintsRetireRequestBeforeLateMalformedReply(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        try {
            selectSecondProvider()
            control("hold_suggest_b")
            val suggestCount = snapshot().events.count { it == "B_SUGGEST" }
            val pending = async(Dispatchers.Default) { suggestOnce(manager.captureEditorGeneration()) }
            waitForEventCount("B_SUGGEST", suggestCount + 1)
            val lease = requireNotNull(manager.hostStateSnapshot().pendingRequest?.lease)
            val failuresBefore = manager.hostStateSnapshot().healthOf(lease.providerId).consecutiveFailures

            manager.consumePredictionHints()
            assertFalse(withTimeout(10_000) { pending.await() }.handled)
            assertNull(manager.hostStateSnapshot().pendingRequest)
            val rejectedBefore = malformedReplyRejectionCount()
            assertTrue(sendMalformedSuggestion(requestId = null))
            waitForMalformedReplyRejectionCount(rejectedBefore + 1)
            val state = manager.hostStateSnapshot()
            assertNull(state.pendingRequest)
            assertEquals(failuresBefore, state.healthOf(lease.providerId).consecutiveFailures)
        } finally {
            control("release_suggest_b")
            editor.handleStartInput(oldInfo)
        }
    }

    @Test
    fun callerCancellationRetiresRequestBeforeLateMalformedReply(): Unit = runBlocking {
        val editor by targetContext.editorInstance()
        val oldInfo = editor.activeInfo
        editor.handleStartInput(plainTextEditorInfo(imeOptions = 0))
        try {
            selectSecondProvider()
            control("hold_suggest_b")
            val suggestCount = snapshot().events.count { it == "B_SUGGEST" }
            val pending = async(Dispatchers.Default) { suggestOnce(manager.captureEditorGeneration()) }
            waitForEventCount("B_SUGGEST", suggestCount + 1)
            val lease = requireNotNull(manager.hostStateSnapshot().pendingRequest?.lease)
            val failuresBefore = manager.hostStateSnapshot().healthOf(lease.providerId).consecutiveFailures

            pending.cancelAndJoin()
            assertNull(manager.hostStateSnapshot().pendingRequest)
            val rejectedBefore = malformedReplyRejectionCount()
            assertTrue(sendMalformedSuggestion(requestId = null))
            waitForMalformedReplyRejectionCount(rejectedBefore + 1)
            val state = manager.hostStateSnapshot()
            assertNull(state.pendingRequest)
            assertEquals(failuresBefore, state.healthOf(lease.providerId).consecutiveFailures)
        } finally {
            control("release_suggest_b")
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

    private fun openSecondProviderUi() {
        runBlocking {
            prefs.suggestion.autocorrectPluginComponent
                .set(secondProviderComponent.flattenToString()).getOrThrow()
        }
        manager.onSelectedProviderChanged()
        manager.refreshProviders()
        manager.acquirePluginUi()
        waitForEvent("B_UI_REQUEST")
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (manager.pluginUi.value?.appRootPageId != "fixture-b" &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(25)
        }
        assertEquals("fixture-b", manager.pluginUi.value?.appRootPageId)
    }

    private fun sendUiReply(requestId: Long, mode: String) {
        val result = control("send_ui_reply", Bundle().apply {
            putParcelable("reply_to", currentReplyMessenger())
            putLong("request_id", requestId)
            putString("mode", mode)
        })
        assertTrue("fixture failed to send synthetic UI reply", result.getBoolean("sent"))
    }

    private fun latestPluginUiRequestId() = synchronized(manager) {
        AutocorrectPluginManager::class.java.getDeclaredField("latestPluginUiRequestId").let { field ->
            field.isAccessible = true
            field.getLong(manager)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> managerSet(name: String): Set<T> = synchronized(manager) {
        AutocorrectPluginManager::class.java.getDeclaredField(name).let { field ->
            field.isAccessible = true
            (field.get(manager) as Set<T>).toSet()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dictionaryActionGrantIds(): Set<Long> = synchronized(manager) {
        AutocorrectPluginManager::class.java.getDeclaredField("pendingDictionaryMutationActions")
            .let { field ->
                field.isAccessible = true
                (field.get(manager) as Map<Long, String>).keys.toSet()
            }
    }

    private fun pendingUiOperationIds(): Set<Long> = managerSet("pendingPluginUiOperations")

    private fun activePickerLeaseIds(): Set<Long> = managerSet("activePluginUiPickerLeaseIds")

    private fun sendMalformedUiReply(requestId: Long, mode: String) {
        sendUiReplyWithDiagnostic(
            requestId,
            mode,
            AutocorrectPluginDiagnosticError.MALFORMED_MESSAGE,
        )
    }

    private fun sendRejectedUiReply(requestId: Long, mode: String) {
        sendUiReplyWithDiagnostic(requestId, mode, AutocorrectPluginDiagnosticError.UNKNOWN_REQUEST)
    }

    private fun sendUiReplyWithDiagnostic(
        requestId: Long,
        mode: String,
        expectedError: AutocorrectPluginDiagnosticError,
    ) {
        val previousSequence = manager.diagnosticsSnapshot().records.lastOrNull()?.sequence ?: 0L
        sendUiReply(requestId, mode)
        val deadline = SystemClock.uptimeMillis() + 10_000
        fun hasDiagnostic() = manager.diagnosticsSnapshot().records.any { record ->
            val event = record.event as? AutocorrectPluginDiagnosticEvent.ReplyRejected
            record.sequence > previousSequence &&
                event?.operation == AutocorrectPluginDiagnosticOperation.PLUGIN_UI &&
                event.error == expectedError
        }
        while (!hasDiagnostic() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue("UI reply rejection was not reported", hasDiagnostic())
        instrumentation.waitForIdleSync()
    }

    private fun staleReplyCount() = manager.diagnosticsSnapshot().records.count { record ->
        val event = record.event as? AutocorrectPluginDiagnosticEvent.ReplyRejected
        event?.error == AutocorrectPluginDiagnosticError.STALE_BINDING
    }

    private suspend fun selectSecondProvider() {
        prefs.suggestion.autocorrectPluginComponent
            .set(secondProviderComponent.flattenToString()).getOrThrow()
        manager.onSelectedProviderChanged()
        awaitStableEditorGeneration()
    }

    private fun sendMalformedSuggestion(requestId: Long?, corruptCandidates: Boolean = true): Boolean = control(
        "send_malformed_suggestions",
        Bundle().apply {
            putParcelable("reply_to", currentReplyMessenger())
            requestId?.let { putLong("request_id", it) }
            putBoolean("corrupt_candidates", corruptCandidates)
        },
    ).getBoolean("sent")

    private fun assertMalformedFailure(requestId: Long) {
        assertTrue(
            "current malformed reply did not fail its request",
            manager.diagnosticsSnapshot().records.any { record ->
                val event = record.event as? AutocorrectPluginDiagnosticEvent.Operation
                event?.requestId?.value == requestId &&
                    event.operation == AutocorrectPluginDiagnosticOperation.SUGGESTION &&
                    event.state == AutocorrectPluginDiagnosticState.FAILED &&
                    event.error == AutocorrectPluginDiagnosticError.MALFORMED_MESSAGE
            },
        )
    }

    private fun malformedReplyRejectionCount() = manager.diagnosticsSnapshot().records.count { record ->
        val event = record.event as? AutocorrectPluginDiagnosticEvent.ReplyRejected
        event?.operation == AutocorrectPluginDiagnosticOperation.SUGGESTION &&
            event.error == AutocorrectPluginDiagnosticError.MALFORMED_MESSAGE
    }

    private fun waitForMalformedReplyRejectionCount(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (malformedReplyRejectionCount() < expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue("stale malformed reply was not rejected", malformedReplyRejectionCount() >= expected)
    }

    private fun waitForStaleReplyCount(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (staleReplyCount() < expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue("old-epoch reply was not rejected", staleReplyCount() >= expected)
    }

    private fun latestDiscoveryFinishSequence() = manager.diagnosticsSnapshot().records
        .lastOrNull { record ->
            (record.event as? AutocorrectPluginDiagnosticEvent.Discovery)?.state in setOf(
                AutocorrectPluginDiagnosticState.SUCCEEDED,
                AutocorrectPluginDiagnosticState.FAILED,
            )
        }?.sequence ?: 0L

    private fun awaitHostCommandBarrier() {
        val previous = latestDiscoveryFinishSequence()
        manager.refreshProviders()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (latestDiscoveryFinishSequence() <= previous && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
        }
        assertTrue("host command barrier did not complete", latestDiscoveryFinishSequence() > previous)
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
