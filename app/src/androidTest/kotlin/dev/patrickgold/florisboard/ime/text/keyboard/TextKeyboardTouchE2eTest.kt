/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.text.keyboard

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Resources
import android.graphics.PointF
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.compose.ui.unit.IntRect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.emoji2.text.EmojiCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.PreferenceStoreInitializationState
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.imeActionResourceName
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSyncBehavior
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeJsonConfig
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.media.emoji.FlorisEmojiCompat
import dev.patrickgold.florisboard.ime.nlp.NlpInlineAutofill
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionReplacement
import dev.patrickgold.florisboard.ime.nlp.SuggestionSeparatorBehavior
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.smartbar.CandidatesDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.ime.smartbar.SmartbarMotionMode
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.window.ImeWindowMode
import dev.patrickgold.florisboard.ime.window.ImeWindowProps
import dev.patrickgold.florisboard.ime.window.ImeWindowSpec
import dev.patrickgold.florisboard.ime.window.KeyboardContentScaleMode
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.sharedActionsController
import dev.patrickgold.florisboard.smartbarCandidateController
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exercises the installed IME's real Compose touch surface and editor connection.
 *
 * This deliberately injects more MOVE events than the old buffered dispatcher could retain. Key
 * coordinates come from the active production keyboard, so the test follows layout sizing, spacing,
 * orientation, and fixed-window padding instead of assuming a particular screen geometry. A
 * temporary core QWERTY subtype makes the exact n/v/b regression fixture independent of the user's
 * active language; the original subtype and keyboard mode are restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class TextKeyboardTouchE2eTest {
    private lateinit var instrumentation: Instrumentation
    private lateinit var activity: Activity
    private lateinit var editor: EditText
    private lateinit var keyboard: TextKeyboard
    private lateinit var windowBounds: IntRect
    private lateinit var points: KeyPoints
    private var previousDefaultIme: String? = null
    private var previousGlideEnabled: Boolean? = null
    private var previousSwipeActions: SwipeActions? = null
    private var previousLongPressDelay: Int? = null
    private var previousDeleteLongPressAction: SwipeAction? = null
    private var previousSubtypeList: String? = null
    private var previousSubtypeId: Long? = null
    private var previousKeyboardMode: KeyboardMode? = null
    private var testedIme: String? = null
    private var testedImeWasEnabled = false

    @Test
    @SuppressLint("DiscouragedApi")
    fun imeActionLabelsUseTheCurrentServiceResources() {
        val service = FlorisImeService.keyboardActionsOrNull() as FlorisImeService
        listOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_UNSPECIFIED,
        ).forEach { action ->
            val resourceName = requireNotNull(imeActionResourceName(action))
            val resourceId = Resources.getSystem().getIdentifier(resourceName, "string", "android")
            val actual = service.getTextForImeAction(action)
            if (resourceId != 0) {
                assertEquals(resourceName, service.resourcesContext.getString(resourceId), actual)
            }
        }
        assertNull(service.getTextForImeAction(EditorInfo.IME_ACTION_NONE))
    }

    @Test
    fun floatingWindowKeyUsesTheCurrentImeService() {
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        val controller = requireNotNull(FlorisImeService.windowControllerOrNull())
        val original = controller.activeWindowConfig.value
        val prefs by FlorisPreferenceStore
        val originalStored = prefs.keyboard.windowConfig.get()
        val formFactor = controller.activeRootInsets.value.formFactor.typeGuess
        val expectedMode = when (original.mode) {
            ImeWindowMode.FIXED -> ImeWindowMode.FLOATING
            ImeWindowMode.FLOATING -> ImeWindowMode.FIXED
        }
        try {
            instrumentation.runOnMainSync {
                keyboardManager.onInputKeyUp(TextKeyData(code = KeyCode.TOGGLE_FLOATING_WINDOW))
            }
            waitUntil("floating-window key did not reach the active IME") {
                controller.activeWindowConfig.value.mode == expectedMode &&
                    prefs.keyboard.windowConfig.get()[formFactor]?.mode == expectedMode &&
                    keyboardManager.activeEvaluator.value.windowMode() == expectedMode
            }
        } finally {
            instrumentation.runOnMainSync { controller.updateWindowConfig { original } }
            waitUntil("floating-window config was not restored") {
                controller.activeWindowConfig.value == original &&
                    prefs.keyboard.windowConfig.get()[formFactor] == original
            }
            runBlocking { prefs.keyboard.windowConfig.set(originalStored).getOrThrow() }
        }
    }

    @Test
    fun liveEmojiToneCommitsTheDisplayedChoice() {
        val prefs by FlorisPreferenceStore
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        val previousTone = prefs.emoji.preferredSkinTone.get()
        val previousHistory = prefs.emoji.historyEnabled.get()
        val previousMode = keyboardManager.activeState.imeUiMode
        val automation = instrumentation.uiAutomation
        val previousAccessibilityFlags = automation.serviceInfo.flags
        var heldPoint: PointF? = null
        var heldDownTime = 0L
        val testResult = runCatching {
            waitUntil("emoji font did not reach a stable loading state") {
                !EmojiCompat.isConfigured() || when (EmojiCompat.get().loadState) {
                    EmojiCompat.LOAD_STATE_SUCCEEDED -> FlorisEmojiCompat.instanceFlow.value === EmojiCompat.get()
                    EmojiCompat.LOAD_STATE_FAILED -> true
                    else -> false
                }
            }
            val loadedFont = FlorisEmojiCompat.instanceFlow.value
            runBlocking {
                prefs.emoji.historyEnabled.set(false).getOrThrow()
                prefs.emoji.preferredSkinTone.set(EmojiSkinTone.DEFAULT).getOrThrow()
            }
            instrumentation.runOnMainSync { keyboardManager.activeState.imeUiMode = ImeUiMode.MEDIA }
            val smiley = waitForEmojiPoint("😀")
            val rowEnd = listOf("😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃")
                .mapNotNull(::visibleEmojiPoint).filter { abs(it.y - smiley.y) < 1f }.maxBy { it.x }
            // Reach People/Body through the real pager, staying inside the visible grid row.
            val swipeTime = SystemClock.uptimeMillis()
            val startX = rowEnd.x
            val endX = smiley.x
            inject(MotionEvent.ACTION_DOWN, startX, smiley.y, swipeTime, 0, waitForFinish = true)
            repeat(6) { index ->
                val x = startX + (endX - startX) * (index + 1) / 6
                inject(MotionEvent.ACTION_MOVE, x, smiley.y, swipeTime, 0, waitForFinish = true)
            }
            inject(MotionEvent.ACTION_UP, endX, smiley.y, swipeTime, 0, waitForFinish = true)
            tap(waitForEmojiPoint("👋"))
            waitForText("👋") // Start the key's lazy gesture handler before changing its preference.

            runBlocking { prefs.emoji.preferredSkinTone.set(EmojiSkinTone.LIGHT_SKIN_TONE).getOrThrow() }
            val lightPoint = waitForEmojiPoint("👋🏻")
            assertTrue("emoji font changed during the gesture fixture", loadedFont === FlorisEmojiCompat.instanceFlow.value)
            heldDownTime = SystemClock.uptimeMillis()
            heldPoint = lightPoint
            inject(MotionEvent.ACTION_DOWN, lightPoint.x, lightPoint.y, heldDownTime, 0, waitForFinish = true)
            inject(MotionEvent.ACTION_CANCEL, lightPoint.x, lightPoint.y, heldDownTime, 0, waitForFinish = true)
            heldPoint = null
            instrumentation.waitForIdleSync()
            assertEquals("cancelled emoji press must not insert text", "👋", readEditorText())
            tap(lightPoint)
            waitUntil("emoji tap did not reach the editor") { readEditorText().length > "👋".length }
            assertEquals("tap must commit the displayed emoji", "👋👋🏻", readEditorText())

            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val previousWindows = automation.windows.map { it.id }.toSet()
            heldDownTime = SystemClock.uptimeMillis()
            heldPoint = lightPoint
            inject(MotionEvent.ACTION_DOWN, lightPoint.x, lightPoint.y, heldDownTime, 0, waitForFinish = true)
            waitForPopupEmojiPoint("👋") { it !in previousWindows }
            assertEquals("opening emoji variations must not insert text", "👋👋🏻", readEditorText())
            inject(MotionEvent.ACTION_UP, lightPoint.x, lightPoint.y, heldDownTime, 0, waitForFinish = true)
            heldPoint = null
            instrumentation.waitForIdleSync()
            assertEquals("releasing the long press must not insert text", "👋👋🏻", readEditorText())

            val (_, defaultPoint) = waitForPopupEmojiPoint("👋") { it !in previousWindows }
            heldDownTime = SystemClock.uptimeMillis()
            heldPoint = defaultPoint
            inject(MotionEvent.ACTION_DOWN, defaultPoint.x, defaultPoint.y, heldDownTime, 0, waitForFinish = true)
            inject(MotionEvent.ACTION_CANCEL, defaultPoint.x, defaultPoint.y, heldDownTime, 0, waitForFinish = true)
            heldPoint = null
            instrumentation.waitForIdleSync()
            assertEquals("cancelled popup press must not insert text", "👋👋🏻", readEditorText())

            runBlocking { prefs.emoji.preferredSkinTone.set(EmojiSkinTone.DEFAULT).getOrThrow() }
            val (popupWindow, popupLightPoint) = waitForPopupEmojiPoint("👋🏻") { it !in previousWindows }
            tap(popupLightPoint)
            waitUntil("popup tap did not reach the editor") { readEditorText().length > "👋👋🏻".length }
            assertEquals("popup tap must commit the displayed emoji", "👋👋🏻👋🏻", readEditorText())
            waitUntil("selected emoji popup did not close") { automation.windows.none { it.id == popupWindow } }
        }
        val cleanup = listOf(
            runCatching {
                heldPoint?.let { inject(MotionEvent.ACTION_CANCEL, it.x, it.y, heldDownTime, 0, waitForFinish = true) }
            },
            runCatching { instrumentation.runOnMainSync { keyboardManager.activeState.imeUiMode = previousMode } },
            runCatching { runBlocking { prefs.emoji.preferredSkinTone.set(previousTone).getOrThrow() } },
            runCatching { runBlocking { prefs.emoji.historyEnabled.set(previousHistory).getOrThrow() } },
            runCatching {
                automation.serviceInfo = automation.serviceInfo.apply { flags = previousAccessibilityFlags }
                assertEquals("accessibility flags were not restored", previousAccessibilityFlags, automation.serviceInfo.flags)
            },
        )
        val failure = testResult.exceptionOrNull() ?: cleanup.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (failure != null) {
            cleanup.mapNotNull { it.exceptionOrNull() }.filter { it !== failure }.forEach(failure::addSuppressed)
            throw failure
        }
    }

    @Test
    fun liveResizeUpdatesRenderedHeightAndInlineChipSize() {
        val prefs by FlorisPreferenceStore
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        val controller = requireNotNull(FlorisImeService.windowControllerOrNull())
        val originalConfig = controller.activeWindowConfig.value
        val originalStoredConfigs = prefs.keyboard.windowConfig.get()
        val originalNumberRow = prefs.keyboard.numberRow.get()
        val originalContentScaleMode = prefs.keyboard.contentScaleMode.get()
        val originalSmartbarEnabled = prefs.smartbar.enabled.get()
        val originalSmartbarLayout = prefs.smartbar.layout.get()
        val originalMotionMode = prefs.smartbar.motionMode.get()
        val originalSharedActionsExpanded = prefs.smartbar.sharedActionsExpanded.get()
        val sharedActions by instrumentation.targetContext.sharedActionsController()
        val originalUiMode = keyboardManager.activeState.value.imeUiMode
        val originalEditorEnabled = controller.editor.state.value.isEnabled
        assertTrue("window editor already has an active gesture", !controller.editor.state.value.isAnyGesture)

        var density = 0f
        var keyboardHeight = 0f
        fun renderedHeight(
            message: String = "rendered IME height did not settle",
            matches: (Int) -> Boolean = { true },
        ): Int {
            var height = 0
            var previous: Pair<Int, Int>? = null
            var stablePolls = 0
            waitUntil(message) {
                instrumentation.runOnMainSync {
                    density =
                        requireNotNull(FlorisImeService.currentImeRootViewOrNull()).resources.displayMetrics.density
                    keyboardHeight = keyboardManager.activeEvaluator.value.keyboard.layoutHeight()
                    height = controller.activeWindowInsets.value?.boundsPx?.height ?: 0
                }
                val current = height to NlpInlineAutofill.suggestionsChipHeightPx
                stablePolls = if (height > 0 && matches(height) && current == previous) stablePolls + 1 else 0
                previous = current
                stablePolls >= REQUIRED_STABLE_LAYOUT_POLLS
            }
            return height
        }

        try {
            runBlocking {
                prefs.keyboard.numberRow.set(false).getOrThrow()
                // Keep the floating caption's font height unchanged while measuring the window delta.
                prefs.keyboard.contentScaleMode.set(KeyboardContentScaleMode.FIXED).getOrThrow()
                prefs.smartbar.layout.set(SmartbarLayout.SUGGESTIONS_ONLY).getOrThrow()
                prefs.smartbar.motionMode.set(SmartbarMotionMode.OFF).getOrThrow()
            }
            instrumentation.runOnMainSync { keyboardManager.activeState.imeUiMode = ImeUiMode.TEXT }
            waitUntil("height fixture did not load four rows with fixed content scaling") {
                keyboardManager.activeEvaluator.value.keyboard.rowCount == 4 &&
                    keyboardManager.lastCharactersEvaluator.value.keyboard.rowCount == 4 &&
                    controller.activeWindowSpec.value.userPreferredOptions.contentScaleMode ==
                    KeyboardContentScaleMode.FIXED
            }

            for (mode in listOf(ImeWindowMode.FIXED, ImeWindowMode.FLOATING)) {
                instrumentation.runOnMainSync {
                    controller.editor.cancelGesture()
                    controller.editor.enable()
                }
                runBlocking { prefs.smartbar.enabled.set(false).getOrThrow() }
                controller.updateWindowConfig { it.copy(mode = mode, fixedMode = ImeWindowMode.Fixed.NORMAL) }
                waitUntil("window mode $mode did not settle") {
                    controller.activeWindowConfig.value.mode == mode &&
                        when (val spec = controller.activeWindowSpec.value) {
                            is ImeWindowSpec.Fixed ->
                                mode == ImeWindowMode.FIXED &&
                                    spec.fixedMode == ImeWindowMode.Fixed.NORMAL

                            is ImeWindowSpec.Floating -> mode == ImeWindowMode.FLOATING
                        }
                }
                val initialSpec = controller.activeWindowSpec.value
                val constraints = initialSpec.constraints
                val range = constraints.maxKeyboardHeight - constraints.minKeyboardHeight
                val savedHeight = constraints.minKeyboardHeight + range * 0.25f
                val draftHeight = constraints.minKeyboardHeight + range * 0.5f
                assertTrue(
                    "fixture needs distinct non-default heights",
                    savedHeight < draftHeight &&
                        savedHeight != constraints.defKeyboardHeight && draftHeight != constraints.defKeyboardHeight,
                )
                val savedSpec = when (initialSpec) {
                    is ImeWindowSpec.Fixed -> initialSpec.copy(
                        props = initialSpec.constraints.defaultProps.copy(keyboardHeight = savedHeight),
                    )

                    is ImeWindowSpec.Floating -> initialSpec.copy(
                        props = initialSpec.constraints.defaultProps.copy(keyboardHeight = savedHeight),
                    )
                }
                controller.updateWindowConfig { config ->
                    when (savedSpec) {
                        is ImeWindowSpec.Fixed -> config.copy(
                            fixedProps =
                            config.fixedProps + (savedSpec.fixedMode to savedSpec.props),
                        )

                        is ImeWindowSpec.Floating -> config.copy(
                            floatingProps =
                            config.floatingProps + (savedSpec.floatingMode to savedSpec.props),
                        )
                    }
                }
                waitUntil("saved height $mode did not settle") {
                    controller.activeWindowSpec.value.props.keyboardHeight == savedHeight &&
                        prefs.keyboard.windowConfig.get()[controller.activeRootInsets.value.formFactor.typeGuess] ==
                        controller.activeWindowConfig.value
                }
                instrumentation.runOnMainSync { controller.editor.beginResizeGesture() }
                val savedConfig = prefs.keyboard.windowConfig.get()
                val beforeResize = renderedHeight("$mode saved height must reach the four-row layout") {
                    abs(keyboardHeight - savedHeight.value * density) <= 1f &&
                        NlpInlineAutofill.suggestionsChipHeightPx > 0
                }
                val savedChipHeight = NlpInlineAutofill.suggestionsChipHeightPx
                val draftSpec = when (savedSpec) {
                    is ImeWindowSpec.Fixed -> savedSpec.copy(props = savedSpec.props.copy(keyboardHeight = draftHeight))

                    is ImeWindowSpec.Floating -> savedSpec.copy(
                        props = savedSpec.props.copy(keyboardHeight = draftHeight),
                    )
                }
                instrumentation.runOnMainSync { controller.editor.onSpecUpdated(draftSpec) }
                val afterResize = renderedHeight("$mode live resize must update the rendered window and chip size") {
                    abs((it - beforeResize) - (draftHeight - savedHeight).value * density) <= 1f &&
                        NlpInlineAutofill.suggestionsChipHeightPx > savedChipHeight
                }
                assertEquals("draft resize must not save the config", savedConfig, prefs.keyboard.windowConfig.get())

                runBlocking { prefs.smartbar.enabled.set(true).getOrThrow() }
                renderedHeight("$mode inline chip must match the rendered row minus its two 5dp margins") {
                    abs((it - afterResize) - (NlpInlineAutofill.suggestionsChipHeightPx + 10f * density)) <= 1f
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                controller.editor.cancelGesture()
                keyboardManager.activeState.imeUiMode = originalUiMode
            }
            controller.updateWindowConfig { originalConfig }
            waitUntil("original window config was not restored") {
                controller.activeWindowConfig.value == originalConfig &&
                    prefs.keyboard.windowConfig.get()[controller.activeRootInsets.value.formFactor.typeGuess] ==
                    originalConfig
            }
            runBlocking {
                listOf(
                    // The controller's queued writes have finished; restore absent profiles too.
                    prefs.keyboard.windowConfig.set(originalStoredConfigs),
                    prefs.keyboard.numberRow.set(originalNumberRow),
                    prefs.keyboard.contentScaleMode.set(originalContentScaleMode),
                    prefs.smartbar.enabled.set(originalSmartbarEnabled),
                    prefs.smartbar.layout.set(originalSmartbarLayout),
                    prefs.smartbar.motionMode.set(originalMotionMode),
                )
            }.forEach { it.getOrThrow() }
            sharedActions.setExpandedByUser(originalSharedActionsExpanded)
            waitUntil("original shared actions state was not restored") {
                prefs.smartbar.sharedActionsExpanded.get() == originalSharedActionsExpanded
            }
            instrumentation.runOnMainSync { if (originalEditorEnabled) controller.editor.enable() }
            renderedHeight()
        }
    }

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val ime = "$packageName/${FlorisImeService::class.java.name}"
        testedIme = ime
        val initiallyEnabledImes = shell("ime list -s")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        testedImeWasEnabled = ime in initiallyEnabledImes
        previousDefaultIme = shell("settings get secure default_input_method")
            .trim()
            .takeIf { '/' in it }
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        val subtypeManager by instrumentation.targetContext.subtypeManager()
        previousKeyboardMode = keyboardManager.activeState.keyboardMode
        val prefs by FlorisPreferenceStore
        previousGlideEnabled = prefs.glide.enabled.get()
        previousSwipeActions = SwipeActions(
            up = prefs.gestures.swipeUp.get(),
            down = prefs.gestures.swipeDown.get(),
            left = prefs.gestures.swipeLeft.get(),
            right = prefs.gestures.swipeRight.get(),
        )
        previousLongPressDelay = prefs.keyboard.longPressDelay.get()
        previousDeleteLongPressAction = prefs.gestures.deleteKeyLongPress.get()
        previousSubtypeList = prefs.localization.subtypes.get()
        previousSubtypeId = prefs.localization.activeSubtypeId.get()
        val storedSubtypes =
            SubtypeJsonConfig.decodeFromString<List<Subtype>>(previousSubtypeList!!)
        val subtypes = storedSubtypes
            .filterNot(::isStaleTestSubtype)
            .ifEmpty { listOf(Subtype.DEFAULT) }
        val latinSubtype = Subtype.DEFAULT.copy(
            id = TEST_SUBTYPE_ID,
        )
        runBlocking {
            prefs.glide.enabled.set(false).getOrThrow()
            prefs.gestures.swipeUp.set(SwipeAction.NO_ACTION).getOrThrow()
            prefs.gestures.swipeDown.set(SwipeAction.NO_ACTION).getOrThrow()
            prefs.gestures.swipeLeft.set(SwipeAction.NO_ACTION).getOrThrow()
            prefs.gestures.swipeRight.set(SwipeAction.NO_ACTION).getOrThrow()
            prefs.keyboard.longPressDelay.set(DENSE_LONG_PRESS_DELAY_MS).getOrThrow()
            prefs.gestures.deleteKeyLongPress.set(SwipeAction.DELETE_CHARACTER).getOrThrow()
            prefs.localization.subtypes
                .set(SubtypeJsonConfig.encodeToString(subtypes + latinSubtype))
                .getOrThrow()
        }
        waitUntil("temporary Latin test subtype did not load") {
            subtypeManager.subtypes.any { it.id == latinSubtype.id }
        }
        runBlocking {
            subtypeManager.switchToSubtypeById(latinSubtype.id).join()
        }
        waitUntil("temporary Latin test subtype did not become active") {
            subtypeManager.activeSubtype.id == latinSubtype.id
        }
        shell("ime enable $ime")
        if (previousDefaultIme == ime) {
            initiallyEnabledImes.firstOrNull { it != ime }?.let { shell("ime set $it") }
        }
        shell("ime set $ime")

        activity = instrumentation.startActivitySync(
            Intent("dev.patrickgold.florisboard.test.action.EDITOR_HARNESS").apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        editor = activity.findViewById(R.id.editor_normal_autocorrect)
        instrumentation.runOnMainSync {
            editor.requestFocus()
            activity.getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        instrumentation.waitForIdleSync()
        var editorCenter: PointF? = null
        waitUntil("editor did not become ready for touch") {
            instrumentation.runOnMainSync {
                val location = IntArray(2)
                editor.getLocationOnScreen(location)
                if (
                    activity.window.decorView.hasWindowFocus() &&
                    editor.width > 0 &&
                    editor.height > 0
                ) {
                    editorCenter = PointF(
                        location[0] + editor.width * 0.5f,
                        location[1] + editor.height * 0.5f,
                    )
                }
            }
            editorCenter != null
        }
        tap(editorCenter!!)

        instrumentation.runOnMainSync {
            keyboardManager.activeState.keyboardMode = KeyboardMode.CHARACTERS
        }
        var previousLayoutSignature: String? = null
        var stableLayoutPolls = 0
        waitUntil("harness text keyboard and IME window did not settle") {
            val evaluator = keyboardManager.activeEvaluator.value
            val candidate = evaluator.keyboard
            val requiredCodes = setOf(
                'n'.code,
                'v'.code,
                'b'.code,
                KeyCode.SPACE,
                KeyCode.DELETE,
                KeyCode.SHIFT,
            )
            val bounds = FlorisImeService.windowControllerOrNull()
                ?.activeWindowInsets
                ?.value
                ?.boundsPx
            val requiredKeys = candidate
                .keys()
                .asSequence()
                .filter { it.computedData.code in requiredCodes }
                .toList()
            val keyboardHeight = candidate.layoutHeight()
            val layoutSignature = if (
                requiredKeys.size == requiredCodes.size &&
                requiredKeys.all { !it.touchBounds.isEmpty() && !it.visibleBounds.isEmpty() } &&
                bounds != null &&
                !bounds.isEmpty &&
                bounds.height >= keyboardHeight
            ) {
                buildString {
                    append(bounds)
                    for (key in requiredKeys) {
                        append(':').append(key.computedData.code)
                        append(':').append(key.touchBounds)
                        append(':').append(key.visibleBounds)
                    }
                }
            } else {
                null
            }
            stableLayoutPolls = if (layoutSignature != null && layoutSignature == previousLayoutSignature) {
                stableLayoutPolls + 1
            } else {
                0
            }
            previousLayoutSignature = layoutSignature
            if (stableLayoutPolls >= REQUIRED_STABLE_LAYOUT_POLLS) {
                keyboard = candidate
                windowBounds = bounds!!
                true
            } else {
                false
            }
        }
        points = resolveKeyPoints()

        // Prove that production geometry was translated to display coordinates correctly before
        // running the regression stream.
        clearEditor()
        tap(points.n.center)
        waitForText("n")
        clearEditor()
    }

    @After
    fun tearDown() {
        if (::activity.isInitialized) {
            instrumentation.runOnMainSync { activity.finish() }
        }
        previousGlideEnabled?.let { enabled ->
            val prefs by FlorisPreferenceStore
            runBlocking { prefs.glide.enabled.set(enabled).getOrThrow() }
        }
        previousSwipeActions?.let { actions ->
            val prefs by FlorisPreferenceStore
            runBlocking {
                prefs.gestures.swipeUp.set(actions.up).getOrThrow()
                prefs.gestures.swipeDown.set(actions.down).getOrThrow()
                prefs.gestures.swipeLeft.set(actions.left).getOrThrow()
                prefs.gestures.swipeRight.set(actions.right).getOrThrow()
            }
        }
        previousLongPressDelay?.let { delay ->
            val prefs by FlorisPreferenceStore
            runBlocking { prefs.keyboard.longPressDelay.set(delay).getOrThrow() }
        }
        previousDeleteLongPressAction?.let { action ->
            val prefs by FlorisPreferenceStore
            runBlocking { prefs.gestures.deleteKeyLongPress.set(action).getOrThrow() }
        }
        if (previousSubtypeList != null && previousSubtypeId != null) {
            val prefs by FlorisPreferenceStore
            val subtypeManager by instrumentation.targetContext.subtypeManager()
            val previousSubtypes =
                SubtypeJsonConfig.decodeFromString<List<Subtype>>(previousSubtypeList!!)
            runBlocking { prefs.localization.subtypes.set(previousSubtypeList!!).getOrThrow() }
            waitUntil("previous subtype list did not reload during cleanup") {
                subtypeManager.subtypes == previousSubtypes
            }
            if (previousSubtypes.any { it.id == previousSubtypeId }) {
                runBlocking {
                    subtypeManager.switchToSubtypeById(previousSubtypeId!!).join()
                }
                waitUntil("previous subtype did not become active during cleanup") {
                    subtypeManager.activeSubtype.id == previousSubtypeId
                }
            }
            runBlocking { prefs.localization.activeSubtypeId.set(previousSubtypeId!!).getOrThrow() }
        }
        previousKeyboardMode?.let { mode ->
            val keyboardManager by instrumentation.targetContext.keyboardManager()
            instrumentation.runOnMainSync {
                keyboardManager.activeState.keyboardMode = mode
            }
        }
        previousDefaultIme?.let { shell("ime set $it") }
        if (!testedImeWasEnabled) {
            testedIme?.let { shell("ime disable $it") }
        }
    }

    @Test
    fun pauseReleasesHeldKeyAfterKeyboardControllerReplacement() {
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        val rootView = requireNotNull(FlorisImeService.currentImeRootViewOrNull())
        lateinit var lifecycle: LifecycleRegistry
        instrumentation.runOnMainSync {
            lifecycle = requireNotNull(rootView.findViewTreeLifecycleOwner()).lifecycle as LifecycleRegistry
            assertEquals(Lifecycle.State.RESUMED, lifecycle.currentState)
        }

        // Replacing the keyboard also replaces the controller captured by the pause callback.
        switchKeyboardModeAndWait(KeyboardMode.SYMBOLS, setOf(KeyCode.SPACE))
        switchKeyboardModeAndWait(KeyboardMode.CHARACTERS, setOf('n'.code))
        val heldCenter = awaitStableKeyCenter('n'.code)
        val heldKey = keyboard.keys().asSequence().first { it.computedData.code == 'n'.code }
        var downTime: Long? = null
        var paused = false
        try {
            downTime = startHold(heldCenter, DIRECT_HELD_POINTER_ID)
            instrumentation.runOnMainSync {
                assertTrue("held key did not become pressed", heldKey.isPressed)
                assertTrue("held key did not reach dispatcher", keyboardManager.inputEventDispatcher.isPressed('n'.code))
            }
            assertEquals("", readEditorText())

            instrumentation.runOnMainSync {
                paused = true
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            waitUntil("pause did not release the held key") {
                var released = false
                instrumentation.runOnMainSync {
                    assertTrue("IME root detached during lifecycle pause", rootView.isAttachedToWindow)
                    released = !heldKey.isPressed && !keyboardManager.inputEventDispatcher.isPressed('n'.code)
                }
                released
            }
            assertEquals("", readEditorText())
        } finally {
            if (paused) {
                instrumentation.runOnMainSync {
                    lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                }
            }
            downTime?.let { startedAt ->
                inject(
                    MotionEvent.ACTION_UP,
                    heldCenter.x,
                    heldCenter.y,
                    startedAt,
                    DIRECT_HELD_POINTER_ID,
                    waitForFinish = true,
                )
            }
            instrumentation.waitForIdleSync()
        }

        assertTextRemains("", repeatObservationDuration(), "after a stale key-up following pause")
        tap(awaitStableKeyCenter('b'.code))
        waitForText("b")
    }

    @Test
    fun distinctPointersLiftInOrderWithoutDuplicatingTheFirstKey() {
        clearEditor()
        clearPredictionHints()
        val downTime = SystemClock.uptimeMillis()
        val first = InjectedPointer(DUPLICATE_POINTER_ID_1, points.n.center)
        val second = InjectedPointer(DUPLICATE_POINTER_ID_2, points.b.center)

        injectPointers(MotionEvent.ACTION_DOWN, 0, listOf(first), downTime)
        injectPointers(MotionEvent.ACTION_POINTER_DOWN, 1, listOf(first, second), downTime)
        injectPointers(MotionEvent.ACTION_POINTER_UP, 1, listOf(first, second), downTime)
        injectPointers(MotionEvent.ACTION_UP, 0, listOf(first), downTime)

        waitForText("nb")
        assertTextRemains(
            expected = "nb",
            durationMs = repeatObservationDuration(),
            context = "after two distinct pointers lift",
        )
    }

    @Test
    fun denseMotionCancelAndPointerReuseAreLossless() {
        for ((point, expected) in listOf(points.n to "n", points.v to "v", points.b to "b")) {
            clearEditor()
            injectDenseStationaryGesture(point.center)
            waitForText(expected)
        }

        clearEditor()
        injectDenseStationaryGesture(points.space.center)
        waitForText(" ")

        clearEditor()
        clearPredictionHints()
        injectDenseStationaryGesture(points.coveredVisualGap.center)
        waitUntil("visible inter-key padding did not commit a neighboring key") {
            readEditorText() in points.coveredVisualGap.expectedTexts
        }
        val coveredGapText = readEditorText()
        assertTextRemains(
            expected = coveredGapText,
            durationMs = repeatObservationDuration(),
            context = "after a dense stationary gesture in visible inter-key padding",
        )

        findSilentTouchGap()?.let { silentGap ->
            clearEditor()
            clearPredictionHints()
            injectDenseStationaryGesture(silentGap)
            assertTextRemains(
                expected = "",
                durationMs = repeatObservationDuration(),
                context = "after a dense stationary gesture outside all key touch bounds",
            )

            clearEditor()
            clearPredictionHints()
            injectDenseDriftGesture(silentGap, points.b.center)
            assertTextRemains(
                expected = "",
                durationMs = repeatObservationDuration(),
                context = "after moving from outside all key touch bounds onto b",
            )
        }

        for ((point, expected) in listOf(points.n to "n", points.v to "v", points.b to "b")) {
            clearEditor()
            injectDenseDriftGesture(point.center, point.smallDownwardDrift)
            waitForText(expected)

            clearEditor()
            injectDenseDriftGesture(point.center, points.space.center)
            waitForText(" ")
        }

        clearEditor()
        injectDenseCancel(points.v.center, pointerId = REUSED_POINTER_ID)
        assertEquals("", readEditorText())
        injectDenseStationaryGesture(points.b.center, pointerId = REUSED_POINTER_ID)
        waitForText("b")

        clearEditor()
        injectDuplicatePointerGesture(points.n.center)
        waitForText("nn")
        assertTextRemains(
            expected = "nn",
            durationMs = repeatObservationDuration(),
            context = "after two physical pointers each tapped n",
        )
        clearEditor()
        injectDuplicatePointerGesture(points.space.center)
        waitForText(" ")
        assertTextRemains(
            expected = " ",
            durationMs = repeatObservationDuration(),
            context = "after duplicate dispatcher ownership on Space",
        )
        clearEditor()
        tap(points.b.center)
        waitForText("b")

        clearEditor()
        injectTransferGesture(points.shift.center, 'n'.code)
        waitUntil("shift slide did not commit exactly one n key") {
            readEditorText().lowercase() == "n"
        }
        awaitStableKeyCenter('n'.code)
        points = resolveKeyPoints()

        setLongPressDelay(TEST_LONG_PRESS_DELAY_MS)
        setEditorText(REPEAT_TEST_TEXT)
        val downTime = startTransferredHold(
            start = points.n.center,
            end = points.delete.center,
            pointerId = HELD_POINTER_ID,
        )
        assertTextRemains(
            expected = REPEAT_TEST_TEXT,
            durationMs = transferredHoldDuration(),
            context = "while holding a pointer transferred onto Delete",
        )
        inject(
            MotionEvent.ACTION_UP,
            points.delete.center.x,
            points.delete.center.y,
            downTime,
            HELD_POINTER_ID,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
        waitForText(REPEAT_TEST_TEXT.dropLast(1))
        assertTextRemains(
            expected = REPEAT_TEST_TEXT.dropLast(1),
            durationMs = repeatObservationDuration(),
            context = "after releasing a pointer transferred onto Delete",
        )

        setEditorText(REPEAT_TEST_TEXT)
        val directHoldDownTime = startHold(
            center = points.delete.center,
            pointerId = DIRECT_HELD_POINTER_ID,
        )
        waitUntil("a direct Delete hold did not repeat") {
            readEditorText().length <= REPEAT_TEST_TEXT.length - 2
        }
        inject(
            MotionEvent.ACTION_UP,
            points.delete.center.x,
            points.delete.center.y,
            directHoldDownTime,
            DIRECT_HELD_POINTER_ID,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
        val textAfterDirectHold = readEditorText()
        assertTextRemains(
            expected = textAfterDirectHold,
            durationMs = repeatObservationDuration(),
            context = "after releasing a direct Delete hold",
        )

        setLongPressDelay(DENSE_LONG_PRESS_DELAY_MS)
        clearEditor()
        val layoutChangeDownTime = startHold(
            center = points.n.center,
            pointerId = LAYOUT_CHANGE_POINTER_ID,
        )
        switchKeyboardModeAndWait(KeyboardMode.SYMBOLS, setOf(KeyCode.SPACE))
        inject(
            MotionEvent.ACTION_UP,
            points.n.center.x,
            points.n.center.y,
            layoutChangeDownTime,
            LAYOUT_CHANGE_POINTER_ID,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
        assertTextRemains(
            expected = "",
            durationMs = repeatObservationDuration(),
            context = "after changing layouts while n was held",
        )
    }

    @Test
    fun appCandidateOwnerFeedsTheVisibleRowAndHiddenSeparatorCommit() {
        val context = instrumentation.targetContext
        val application = context.applicationContext as FlorisApplication
        runBlocking {
            assertEquals(
                PreferenceStoreInitializationState.READY,
                withTimeout(20_000L) {
                    application.preferenceStoreInitializationState.first {
                        it != PreferenceStoreInitializationState.LOADING
                    }
                },
            )
        }
        val nlp by context.nlpManager()
        val candidates by context.smartbarCandidateController()
        val editorInstance by context.editorInstance()
        val keyboardManager by context.keyboardManager()
        val sharedActions by context.sharedActionsController()
        val prefs by FlorisPreferenceStore
        val originalSuggestions = prefs.suggestion.enabled.get()
        val originalPlugin = prefs.suggestion.autocorrectPluginComponent.get()
        val originalEmoji = prefs.emoji.suggestionEnabled.get()
        val originalClipboard = prefs.clipboard.suggestionEnabled.get()
        val originalDisplayMode = prefs.suggestion.displayMode.get()
        val originalSmartbar = prefs.smartbar.enabled.get()
        val originalLayout = prefs.smartbar.layout.get()
        val originalMotion = prefs.smartbar.motionMode.get()
        val originalExpanded = prefs.smartbar.sharedActionsExpanded.get()
        var originalFlags = false to false
        instrumentation.runOnMainSync {
            originalFlags = keyboardManager.activeState.let { it.isComposingEnabled to it.isIncognitoMode }
        }
        val automation = instrumentation.uiAutomation
        val originalAccessibilityFlags = automation.serviceInfo.flags
        val labels = setOf("chosen", "first", "second")

        fun candidatePoints(): Map<String, PointF> = buildMap {
            fun visit(node: AccessibilityNodeInfo) {
                val text = node.text?.toString()
                if (node.isVisibleToUser && text != null && text in labels) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) put(text, PointF(bounds.exactCenterX(), bounds.exactCenterY()))
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            }
            automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.forEach { window ->
                window.root?.takeIf { it.packageName?.toString() == context.packageName }?.let(::visit)
            }
        }

        fun publishForDraft() {
            setEditorText("draft")
            waitUntil("the IME did not acknowledge the synthetic draft") {
                val content = editorInstance.activeContentFlow.value
                content.text == "draft" && content.selection == EditorRange.cursor(5)
            }
            val origin = editorInstance.activeContent
            val requestedReplacement = SuggestionReplacement(EditorRange(0, 5), "draft", EditorRange.cursor(5))
            fun candidate(text: String, visible: Boolean, autoCommit: Boolean) =
                object : SuggestionCandidate by WordSuggestionCandidate(
                    text,
                    isEligibleForAutoCommit = autoCommit,
                    originContent = origin,
                ) {
                    override val isVisible = visible
                    override val replacement = requestedReplacement
                    override val separatorBehavior = SuggestionSeparatorBehavior.INSERT
                }
            nlp.suggestDirectly(
                listOf(
                    candidate("chosen", visible = false, autoCommit = true),
                    candidate("first", visible = true, autoCommit = false),
                    candidate("second", visible = true, autoCommit = true),
                ),
            )
            waitUntil("the app owner did not publish the visible candidates") {
                candidates.activeCandidatesFlow.value.map { it.text.toString() } == listOf("first", "second")
            }
            assertEquals("chosen", candidates.autoCommitCandidate?.text?.toString())
        }

        val testResult = runCatching {
            runBlocking {
                prefs.suggestion.autocorrectPluginComponent.set("").getOrThrow()
                prefs.suggestion.enabled.set(true).getOrThrow()
                prefs.emoji.suggestionEnabled.set(false).getOrThrow()
                prefs.clipboard.suggestionEnabled.set(false).getOrThrow()
                prefs.suggestion.displayMode.set(CandidatesDisplayMode.CLASSIC).getOrThrow()
                prefs.smartbar.enabled.set(true).getOrThrow()
                prefs.smartbar.layout.set(SmartbarLayout.SUGGESTIONS_ONLY).getOrThrow()
                prefs.smartbar.motionMode.set(SmartbarMotionMode.OFF).getOrThrow()
            }
            instrumentation.runOnMainSync {
                keyboardManager.activeState.isComposingEnabled = true
                keyboardManager.activeState.isIncognitoMode = false
            }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            publishForDraft()
            var previousPoints: Map<String, PointF>? = null
            var stablePolls = 0
            waitUntil("the production Smartbar row did not render both visible choices") {
                val points = candidatePoints()
                stablePolls = if (points.keys == setOf("first", "second") && points == previousPoints) stablePolls + 1 else 0
                previousPoints = points
                stablePolls >= REQUIRED_STABLE_LAYOUT_POLLS
            }
            assertTrue("the hidden choice leaked into the row", "chosen" !in candidatePoints())
            tap(awaitStableKeyCenter(KeyCode.SPACE))
            waitForText("chosen ")
            waitUntil("the editor did not acknowledge the hidden separator commit") {
                val content = editorInstance.activeContentFlow.value
                content.text == "chosen " && content.selection == EditorRange.cursor(7)
            }

            publishForDraft()
            var firstPoint: PointF? = null
            waitUntil("the republished visible choice did not reach the row") {
                firstPoint = candidatePoints()["first"]
                firstPoint != null
            }
            tap(requireNotNull(firstPoint))
            waitForText("first")
            waitUntil("the editor did not acknowledge the visible candidate tap") {
                editorInstance.activeContentFlow.value.text == "first"
            }

            publishForDraft()
            nlp.clearSuggestions()
            assertTrue(candidates.activeCandidatesFlow.value.isEmpty())
            assertNull(candidates.autoCommitCandidate)
        }
        val restores = listOf<() -> Unit>(
            { nlp.clearSuggestions() },
            { automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags } },
            { runBlocking { prefs.suggestion.enabled.set(originalSuggestions).getOrThrow() } },
            { runBlocking { prefs.suggestion.autocorrectPluginComponent.set(originalPlugin).getOrThrow() } },
            { runBlocking { prefs.emoji.suggestionEnabled.set(originalEmoji).getOrThrow() } },
            { runBlocking { prefs.clipboard.suggestionEnabled.set(originalClipboard).getOrThrow() } },
            { runBlocking { prefs.suggestion.displayMode.set(originalDisplayMode).getOrThrow() } },
            { runBlocking { prefs.smartbar.enabled.set(originalSmartbar).getOrThrow() } },
            { runBlocking { prefs.smartbar.layout.set(originalLayout).getOrThrow() } },
            { runBlocking { prefs.smartbar.motionMode.set(originalMotion).getOrThrow() } },
            {
                instrumentation.runOnMainSync {
                    keyboardManager.activeState.isComposingEnabled = originalFlags.first
                    keyboardManager.activeState.isIncognitoMode = originalFlags.second
                }
            },
            {
                nlp.clearSuggestions()
                runBlocking { candidates.refresh() }
                sharedActions.setExpandedByUser(originalExpanded)
                waitUntil("original shared actions state was not restored") {
                    prefs.smartbar.sharedActionsExpanded.get() == originalExpanded
                }
            },
            { assertEquals(originalAccessibilityFlags, automation.serviceInfo.flags) },
        )
        val cleanup = restores.map { runCatching { it() } }
        val failure = testResult.exceptionOrNull() ?: cleanup.firstNotNullOfOrNull { it.exceptionOrNull() }
        if (failure != null) {
            cleanup.mapNotNull { it.exceptionOrNull() }.filter { it !== failure }.forEach(failure::addSuppressed)
            throw failure
        }
    }

    @Test
    fun clipboardCopyCutAndPasteKeepEditorSelectionAndCurrentClip() {
        val clipboard by instrumentation.targetContext.clipboardManager()
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        val editorInstance by instrumentation.targetContext.editorInstance()
        runBlocking { withTimeout(10_000L) { clipboard.awaitInitialization() } }
        val prefs by FlorisPreferenceStore
        val originalClip = clipboard.primaryClip
        val originalInternalClipboard = prefs.clipboard.useInternalClipboard.get()
        val originalSyncToFloris = prefs.clipboard.syncToFloris.get()
        val originalSyncToSystem = prefs.clipboard.syncToSystem.get()

        fun selectMiddle() {
            instrumentation.runOnMainSync { editor.setSelection(1, 3) }
            waitUntil("IME did not observe selected text") {
                editorInstance.activeContent.selection == EditorRange(1, 3)
            }
        }

        fun sendClipboardKey(data: TextKeyData) {
            instrumentation.runOnMainSync { keyboardManager.inputEventDispatcher.sendDownUp(data) }
        }

        fun selection(): Pair<Int, Int> {
            var result = 0 to 0
            instrumentation.runOnMainSync { result = editor.selectionStart to editor.selectionEnd }
            return result
        }

        fun clearClipboard() {
            clipboard.updatePrimaryClip(null)
            waitUntil("primary clip was not cleared") { clipboard.primaryClip == null }
        }

        try {
            runBlocking {
                // Keep this internal-only fixture isolated from queued system clipboard events.
                prefs.clipboard.syncToFloris.set(ClipboardSyncBehavior.NO_EVENTS).getOrThrow()
                prefs.clipboard.useInternalClipboard.set(true).getOrThrow()
                prefs.clipboard.syncToSystem.set(ClipboardSyncBehavior.NO_EVENTS).getOrThrow()
            }
            clearClipboard()
            setEditorText("abcd")
            selectMiddle()
            sendClipboardKey(TextKeyData.CLIPBOARD_COPY)
            waitUntil("copy did not publish the selection") { clipboard.primaryClip?.text == "bc" }
            waitUntil("copy did not collapse the selection") { selection() == (3 to 3) }
            assertEquals("abcd", readEditorText())

            clearClipboard()
            setEditorText("abcd")
            selectMiddle()
            sendClipboardKey(TextKeyData.CLIPBOARD_CUT)
            waitForText("ad")
            waitUntil("cut did not publish the selection") { clipboard.primaryClip?.text == "bc" }

            setEditorText("")
            sendClipboardKey(TextKeyData.CLIPBOARD_PASTE)
            waitForText("bc")

            clearClipboard()
            setEditorText("keep")
            sendClipboardKey(TextKeyData.CLIPBOARD_PASTE)
            instrumentation.waitForIdleSync()
            assertEquals("keep", readEditorText())
        } finally {
            try {
                clipboard.updatePrimaryClip(originalClip)
                waitUntil("original primary clip was not restored") {
                    clipboard.primaryClip == originalClip
                }
            } finally {
                runBlocking {
                    try {
                        prefs.clipboard.syncToFloris.set(originalSyncToFloris).getOrThrow()
                    } finally {
                        try {
                            prefs.clipboard.syncToSystem.set(originalSyncToSystem).getOrThrow()
                        } finally {
                            prefs.clipboard.useInternalClipboard.set(originalInternalClipboard).getOrThrow()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun navigationArrowMovesCursorAndExtendsManualSelection() {
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        var previousFlags = Triple(false, false, false)
        instrumentation.runOnMainSync {
            previousFlags = Triple(
                keyboardManager.activeState.isManualSelectionMode,
                keyboardManager.activeState.isManualSelectionModeStart,
                keyboardManager.activeState.isManualSelectionModeEnd,
            )
        }
        fun selection(): Pair<Int, Int> {
            var result = 0 to 0
            instrumentation.runOnMainSync { result = editor.selectionStart to editor.selectionEnd }
            return result
        }

        try {
            setEditorText("abcd")
            instrumentation.runOnMainSync {
                keyboardManager.activeState.isManualSelectionMode = false
                editor.setSelection(2)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                keyboardManager.onInputKeyDown(TextKeyData.ARROW_LEFT)
                keyboardManager.onInputKeyUp(TextKeyData.ARROW_LEFT)
            }
            waitUntil("left arrow did not move the cursor") { selection() == (1 to 1) }

            instrumentation.runOnMainSync {
                keyboardManager.activeState.isManualSelectionMode = true
                keyboardManager.onInputKeyDown(TextKeyData.ARROW_LEFT)
                keyboardManager.onInputKeyUp(TextKeyData.ARROW_LEFT)
            }
            instrumentation.waitForIdleSync()
            val manualSelection = selection()
            assertEquals(setOf(0, 1), setOf(manualSelection.first, manualSelection.second))
            instrumentation.runOnMainSync {
                assertTrue(keyboardManager.activeState.isManualSelectionModeStart)
                assertEquals(false, keyboardManager.activeState.isManualSelectionModeEnd)
            }
        } finally {
            instrumentation.runOnMainSync {
                keyboardManager.activeState.isManualSelectionMode = previousFlags.first
                keyboardManager.activeState.isManualSelectionModeStart = previousFlags.second
                keyboardManager.activeState.isManualSelectionModeEnd = previousFlags.third
            }
        }
    }

    @Test
    fun kanaAndCharacterWidthKeysKeepTheirStateTransitions() {
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        var previousFlags = false to false
        instrumentation.runOnMainSync {
            previousFlags = keyboardManager.activeState.let { it.isKanaKata to it.isCharHalfWidth }
        }

        fun assertTransition(code: Int, fromKana: Boolean, fromHalf: Boolean, toKana: Boolean, toHalf: Boolean) {
            instrumentation.runOnMainSync {
                keyboardManager.activeState.batchEdit {
                    it.isKanaKata = fromKana
                    it.isCharHalfWidth = fromHalf
                }
                keyboardManager.onInputKeyUp(TextKeyData(type = KeyType.SYSTEM_GUI, code = code))
                assertEquals("Kana state after key $code", toKana, keyboardManager.activeState.value.isKanaKata)
                assertEquals("width state after key $code", toHalf, keyboardManager.activeState.value.isCharHalfWidth)
            }
        }

        try {
            assertTransition(KeyCode.KANA_SWITCHER, false, true, true, false)
            assertTransition(KeyCode.KANA_SWITCHER, true, true, false, false)
            assertTransition(KeyCode.KANA_HIRA, true, true, false, false)
            assertTransition(KeyCode.KANA_KATA, false, true, true, false)
            assertTransition(KeyCode.KANA_HALF_KATA, false, false, true, true)
            assertTransition(KeyCode.CHAR_WIDTH_SWITCHER, true, false, true, true)
            assertTransition(KeyCode.CHAR_WIDTH_SWITCHER, false, true, false, false)
            assertTransition(KeyCode.CHAR_WIDTH_FULL, true, true, true, false)
            assertTransition(KeyCode.CHAR_WIDTH_HALF, false, false, false, true)
        } finally {
            instrumentation.runOnMainSync {
                keyboardManager.activeState.batchEdit {
                    it.isKanaKata = previousFlags.first
                    it.isCharHalfWidth = previousFlags.second
                }
            }
        }
    }

    private fun switchKeyboardModeAndWait(mode: KeyboardMode, requiredCodes: Set<Int>) {
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        instrumentation.runOnMainSync { keyboardManager.activeState.keyboardMode = mode }
        waitUntil("keyboard mode $mode did not settle") {
            val candidate = keyboardManager.activeEvaluator.value.keyboard
            val codes = candidate.keys().asSequence()
                .filter { !it.visibleBounds.isEmpty() }
                .map { it.computedData.code }
                .toSet()
            if (candidate.mode == mode && codes.containsAll(requiredCodes)) {
                keyboard = candidate
                true
            } else {
                false
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun resolveKeyPoints(): KeyPoints {
        val keys = keyboard.keys().asSequence().toList()
        val keyboardHeight = keyboard.layoutHeight()
        val keyboardOrigin = keyboardOrigin(keyboardHeight)
        val touchSlop = ViewConfiguration.get(instrumentation.targetContext)
            .scaledTouchSlop
            .toFloat()

        fun findKey(code: Int): TextKey {
            val key = keys.firstOrNull { it.computedData.code == code }
            assertNotNull("active keyboard does not contain key code $code", key)
            return key!!
        }

        fun find(code: Int): KeyPoint {
            val key = findKey(code)
            return KeyPoint(
                center = PointF(
                    keyboardOrigin.x + key.visibleBounds.center.x,
                    keyboardOrigin.y + key.visibleBounds.center.y,
                ),
                smallDownwardDrift = PointF(
                    keyboardOrigin.x + key.visibleBounds.center.x,
                    keyboardOrigin.y + key.visibleBounds.bottom + touchSlop * 0.5f,
                ),
            )
        }

        val firstGapKey = findKey('v'.code)
        val secondGapKey = findKey('b'.code)
        val (leftGapKey, rightGapKey) = if (
            firstGapKey.visibleBounds.center.x < secondGapKey.visibleBounds.center.x
        ) {
            firstGapKey to secondGapKey
        } else {
            secondGapKey to firstGapKey
        }
        val gapLeft = leftGapKey.visibleBounds.right
        val gapRight = rightGapKey.visibleBounds.left
        val gapTop = maxOf(leftGapKey.visibleBounds.top, rightGapKey.visibleBounds.top)
        val gapBottom = minOf(leftGapKey.visibleBounds.bottom, rightGapKey.visibleBounds.bottom)
        assertTrue("v and b do not have visible inter-key padding", gapLeft < gapRight)
        assertTrue("v and b visible bounds do not overlap vertically", gapTop < gapBottom)
        val coveredVisualGapLocal = PointF(
            (gapLeft + gapRight) * 0.5f,
            (gapTop + gapBottom) * 0.5f,
        )
        assertEquals(
            "visible inter-key midpoint unexpectedly belongs to a visible key",
            null,
            keyboard.getVisibleKeyForPos(coveredVisualGapLocal.x, coveredVisualGapLocal.y),
        )
        val coveredGapKey = keyboard.getKeyForPos(
            coveredVisualGapLocal.x,
            coveredVisualGapLocal.y,
        )
        assertNotNull(
            "visible inter-key padding should retain the keyboard's wider touch target",
            coveredGapKey,
        )
        val coveredGapTexts = sequenceOf(leftGapKey, rightGapKey, coveredGapKey!!)
            .map { it.computedData.asString(isForDisplay = false) }
            .toSet()
        assertTrue(
            "covered visual gap must resolve only to neighboring single-code-point text keys",
            coveredGapTexts.all { it.codePointCount(0, it.length) == 1 },
        )

        return KeyPoints(
            n = find('n'.code),
            v = find('v'.code),
            b = find('b'.code),
            space = find(KeyCode.SPACE),
            delete = find(KeyCode.DELETE),
            shift = find(KeyCode.SHIFT),
            coveredVisualGap = CoveredVisualGap(
                center = PointF(
                    keyboardOrigin.x + coveredVisualGapLocal.x,
                    keyboardOrigin.y + coveredVisualGapLocal.y,
                ),
                expectedTexts = coveredGapTexts,
            ),
        )
    }

    private fun findSilentTouchGap(): PointF? {
        val keys = keyboard.keys().asSequence()
            .filter { !it.touchBounds.isEmpty() }
            .toList()
        val keyboardHeight = keyboard.layoutHeight()
        val keyboardWidth = keys.maxOfOrNull { it.touchBounds.right } ?: return null
        val origin = keyboardOrigin(keyboardHeight)
        val clearance = STATIONARY_MOVE_RADIUS_PX + 0.5f

        fun isSilentLocalPoint(x: Float, y: Float): Boolean {
            if (x !in clearance..(keyboardWidth - clearance)) return false
            if (y !in clearance..(keyboardHeight - clearance)) return false
            return keys.none { key ->
                val bounds = key.touchBounds
                x >= bounds.left - clearance &&
                    x <= bounds.right + clearance &&
                    y >= bounds.top - clearance &&
                    y <= bounds.bottom + clearance
            }
        }

        val rowBands = keyboard.rows().asSequence().mapNotNull { row ->
            row.filter { !it.touchBounds.isEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.let { keysInRow ->
                    keysInRow.minOf { it.touchBounds.top } to
                        keysInRow.maxOf { it.touchBounds.bottom }
                }
        }.sortedBy { it.first }.toList()
        val preferredXs = buildList {
            add(points.b.center.x - origin.x)
            add(points.coveredVisualGap.center.x - origin.x)
            add(keyboardWidth * 0.5f)
            keys.forEach { add(it.visibleBounds.center.x) }
        }
        for (index in 0 until rowBands.lastIndex) {
            val gapTop = rowBands[index].second
            val gapBottom = rowBands[index + 1].first
            if (gapBottom - gapTop <= clearance * 2f) continue
            val y = (gapTop + gapBottom) * 0.5f
            for (x in preferredXs) {
                if (isSilentLocalPoint(x, y)) {
                    return PointF(origin.x + x, origin.y + y)
                }
            }
        }
        return null
    }

    private fun keyboardOrigin(keyboardHeight: Float = keyboard.layoutHeight()): PointF {
        var origin = PointF()
        instrumentation.runOnMainSync {
            // The outer IME bounds include safe-area and user padding outside the keyboard touch surface.
            val fixedProps = FlorisImeService.windowControllerOrNull()
                ?.activeWindowSpec?.value?.props as? ImeWindowProps.Fixed
            val rootView = FlorisImeService.currentImeRootViewOrNull()
            assertNotNull("IME root view is not available for screen coordinates", rootView)
            assertTrue("IME root view is detached", rootView!!.isAttachedToWindow)
            val rootLocation = IntArray(2)
            rootView.getLocationOnScreen(rootLocation)
            val decorView = rootView.rootView
            val decorLocation = IntArray(2)
            decorView.getLocationOnScreen(decorLocation)
            val safeInsets = if (fixedProps != null) {
                val insets = ViewCompat.getRootWindowInsets(rootView)
                assertNotNull("IME root window insets are unavailable", insets)
                insets!!.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            } else {
                null
            }
            // Older IME windows can exclude a system bar before Compose receives its safe-drawing insets.
            val excludedLeft = rootLocation[0] - decorLocation[0]
            val excludedBottom = decorLocation[1] + decorView.height - rootLocation[1] - rootView.height
            val safeLeft = ((safeInsets?.left ?: 0) - excludedLeft).coerceAtLeast(0)
            val safeBottom = ((safeInsets?.bottom ?: 0) - excludedBottom).coerceAtLeast(0)
            val density = activity.resources.displayMetrics.density
            origin = PointF(
                rootLocation[0] + windowBounds.left + safeLeft +
                    (fixedProps?.paddingLeft?.value ?: 0f) * density,
                rootLocation[1] + windowBounds.bottom - keyboardHeight - safeBottom -
                    (fixedProps?.paddingBottom?.value ?: 0f) * density,
            )
        }
        return origin
    }

    /**
     * The last row's touch bounds intentionally extend below the actual Compose layout. Derive the
     * real layout height from the last row's top plus the normal row height instead.
     */
    private fun TextKeyboard.layoutHeight(): Float {
        val keys = keys().asSequence().filter { !it.touchBounds.isEmpty() }.toList()
        if (keys.isEmpty()) return 0f
        val firstRowTop = keys.minOf { it.touchBounds.top }
        val rowHeight = keys.first { it.touchBounds.top == firstRowTop }.touchBounds.height
        return keys.maxOf { it.touchBounds.top } + rowHeight
    }

    private fun injectDenseStationaryGesture(center: PointF, pointerId: Int = 0) {
        val downTime = SystemClock.uptimeMillis()
        inject(
            MotionEvent.ACTION_DOWN,
            center.x,
            center.y,
            downTime,
            pointerId,
            waitForFinish = true,
        )
        repeat(DENSE_MOVE_COUNT) { index ->
            val radians = index * Math.PI * 2.0 / DENSE_MOVE_COUNT
            inject(
                MotionEvent.ACTION_MOVE,
                center.x + cos(radians).toFloat(),
                center.y + sin(radians).toFloat(),
                downTime,
                pointerId,
                waitForFinish = false,
            )
        }
        inject(
            MotionEvent.ACTION_UP,
            center.x,
            center.y,
            downTime,
            pointerId,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
    }

    private fun injectDenseDriftGesture(start: PointF, end: PointF) {
        val downTime = SystemClock.uptimeMillis()
        inject(
            MotionEvent.ACTION_DOWN,
            start.x,
            start.y,
            downTime,
            pointerId = 0,
            waitForFinish = true,
        )
        repeat(DENSE_MOVE_COUNT) { index ->
            val fraction = (index + 1f) / DENSE_MOVE_COUNT
            inject(
                MotionEvent.ACTION_MOVE,
                start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction,
                downTime,
                pointerId = 0,
                waitForFinish = false,
            )
        }
        inject(
            MotionEvent.ACTION_UP,
            end.x,
            end.y,
            downTime,
            pointerId = 0,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
    }

    private fun injectTransferGesture(start: PointF, targetCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        inject(
            MotionEvent.ACTION_DOWN,
            start.x,
            start.y,
            downTime,
            pointerId = 0,
            waitForFinish = true,
        )
        val end = awaitStableKeyCenter(targetCode)
        inject(
            MotionEvent.ACTION_MOVE,
            end.x,
            end.y,
            downTime,
            pointerId = 0,
            waitForFinish = true,
        )
        inject(
            MotionEvent.ACTION_UP,
            end.x,
            end.y,
            downTime,
            pointerId = 0,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
    }

    private fun awaitStableKeyCenter(code: Int): PointF {
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        var previousSignature: String? = null
        var stablePolls = 0
        var center: PointF? = null
        waitUntil("key code $code did not settle after the keyboard state changed") {
            val candidate = keyboardManager.activeEvaluator.value.keyboard
            val sourceText = String(Character.toChars(code))
            val key = candidate.keys().asSequence().firstOrNull {
                !it.visibleBounds.isEmpty() &&
                    (
                        it.computedData.code == code ||
                            it.data.asString(isForDisplay = false) == sourceText
                    )
            }
            val bounds = FlorisImeService.windowControllerOrNull()
                ?.activeWindowInsets
                ?.value
                ?.boundsPx
            val signature = if (key != null && bounds != null) {
                "${candidate.mode}:$bounds:${key.visibleBounds}"
            } else {
                null
            }
            stablePolls = if (signature != null && signature == previousSignature) {
                stablePolls + 1
            } else {
                0
            }
            previousSignature = signature
            if (stablePolls >= REQUIRED_STABLE_LAYOUT_POLLS) {
                keyboard = candidate
                windowBounds = bounds!!
                val origin = keyboardOrigin()
                center = PointF(
                    origin.x + key!!.visibleBounds.center.x,
                    origin.y + key.visibleBounds.center.y,
                )
                true
            } else {
                false
            }
        }
        return center!!
    }

    private fun injectDenseCancel(center: PointF, pointerId: Int) {
        val downTime = SystemClock.uptimeMillis()
        inject(
            MotionEvent.ACTION_DOWN,
            center.x,
            center.y,
            downTime,
            pointerId,
            waitForFinish = true,
        )
        repeat(DENSE_MOVE_COUNT) {
            inject(
                MotionEvent.ACTION_MOVE,
                center.x,
                center.y,
                downTime,
                pointerId,
                waitForFinish = false,
            )
        }
        inject(
            MotionEvent.ACTION_CANCEL,
            center.x,
            center.y,
            downTime,
            pointerId,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
    }

    private fun injectDuplicatePointerGesture(center: PointF) {
        val downTime = SystemClock.uptimeMillis()
        val first = InjectedPointer(DUPLICATE_POINTER_ID_1, center)
        val second = InjectedPointer(DUPLICATE_POINTER_ID_2, center)
        injectPointers(
            actionMasked = MotionEvent.ACTION_DOWN,
            actionIndex = 0,
            pointers = listOf(first),
            downTime = downTime,
        )
        injectPointers(
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(first, second),
            downTime = downTime,
        )
        injectPointers(
            actionMasked = MotionEvent.ACTION_POINTER_UP,
            actionIndex = 1,
            pointers = listOf(first, second),
            downTime = downTime,
        )
        injectPointers(
            actionMasked = MotionEvent.ACTION_UP,
            actionIndex = 0,
            pointers = listOf(first),
            downTime = downTime,
        )
        instrumentation.waitForIdleSync()
    }

    private fun startTransferredHold(start: PointF, end: PointF, pointerId: Int): Long {
        val downTime = SystemClock.uptimeMillis()
        inject(
            MotionEvent.ACTION_DOWN,
            start.x,
            start.y,
            downTime,
            pointerId,
            waitForFinish = true,
        )
        inject(
            MotionEvent.ACTION_MOVE,
            end.x,
            end.y,
            downTime,
            pointerId,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
        return downTime
    }

    private fun startHold(center: PointF, pointerId: Int): Long {
        val downTime = SystemClock.uptimeMillis()
        inject(
            MotionEvent.ACTION_DOWN,
            center.x,
            center.y,
            downTime,
            pointerId,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
        return downTime
    }

    private fun tap(point: PointF) {
        val downTime = SystemClock.uptimeMillis()
        inject(
            MotionEvent.ACTION_DOWN,
            point.x,
            point.y,
            downTime,
            pointerId = 0,
            waitForFinish = true,
        )
        inject(
            MotionEvent.ACTION_UP,
            point.x,
            point.y,
            downTime,
            pointerId = 0,
            waitForFinish = true,
        )
        instrumentation.waitForIdleSync()
    }

    private fun waitForEmojiPoint(text: String): PointF {
        var previous: PointF? = null
        var stablePolls = 0
        waitUntil("emoji glyph did not become visible and stable") {
            val point = visibleEmojiPoint(text)
            stablePolls = if (point != null && point == previous) stablePolls + 1 else 0
            previous = point
            stablePolls >= REQUIRED_STABLE_LAYOUT_POLLS
        }
        return requireNotNull(previous)
    }

    private fun visibleEmojiPoint(text: String): PointF? {
        var point: PointF? = null
        instrumentation.runOnMainSync {
            val root = FlorisImeService.currentImeRootViewOrNull() ?: return@runOnMainSync
            val rootBounds = Rect()
            if (!root.getGlobalVisibleRect(rootBounds)) return@runOnMainSync
            fun find(view: View): PointF? {
                if (view is TextView && view.isShown && view.text.toString() == text) {
                    val bounds = Rect()
                    if (view.getGlobalVisibleRect(bounds) && !bounds.isEmpty &&
                        rootBounds.contains(bounds.centerX(), bounds.centerY())
                    ) return PointF(bounds.exactCenterX(), bounds.exactCenterY())
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            // Global visible rectangles use root coordinates; injected events use the screen.
            val screenOrigin = IntArray(2)
            root.rootView.getLocationOnScreen(screenOrigin)
            point = find(root)?.apply { offset(screenOrigin[0].toFloat(), screenOrigin[1].toFloat()) }
        }
        return point
    }

    private fun waitForPopupEmojiPoint(text: String, acceptsWindow: (Int) -> Boolean): Pair<Int, PointF> {
        val packageName = instrumentation.targetContext.packageName
        fun find(node: AccessibilityNodeInfo): PointF? {
            if (node.isVisibleToUser && node.text?.toString() == text) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) return PointF(bounds.exactCenterX(), bounds.exactCenterY())
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                find(child)?.let { return it }
            }
            return null
        }
        var previous: Pair<Int, PointF>? = null
        var stablePolls = 0
        waitUntil("popup emoji did not become visible and stable in its own window") {
            val point = instrumentation.uiAutomation.windows.filter { acceptsWindow(it.id) }.mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                if (root.packageName?.toString() != packageName) return@mapNotNull null
                find(root)?.let { window.id to it }
            }.singleOrNull()
            stablePolls = if (point != null && point == previous) stablePolls + 1 else 0
            previous = point
            stablePolls >= REQUIRED_STABLE_LAYOUT_POLLS
        }
        return requireNotNull(previous)
    }

    private fun inject(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        pointerId: Int,
        waitForFinish: Boolean,
    ) {
        injectPointers(
            actionMasked = action,
            actionIndex = 0,
            pointers = listOf(InjectedPointer(pointerId, PointF(x, y))),
            downTime = downTime,
            waitForFinish = waitForFinish,
        )
    }

    private fun injectPointers(
        actionMasked: Int,
        actionIndex: Int,
        pointers: List<InjectedPointer>,
        downTime: Long,
        waitForFinish: Boolean = true,
    ) {
        val properties = pointers.map { pointer ->
            MotionEvent.PointerProperties().apply {
                id = pointer.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = pointers.map { pointer ->
            MotionEvent.PointerCoords().apply {
                x = pointer.point.x
                y = pointer.point.y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val action = actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            pointers.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        try {
            assertTrue(
                "UiAutomation rejected ${MotionEvent.actionToString(action)} for $pointers",
                instrumentation.uiAutomation.injectInputEvent(event, waitForFinish),
            )
        } finally {
            event.recycle()
        }
    }

    private fun clearEditor() {
        setEditorText("")
    }

    private fun clearPredictionHints() {
        val autocorrectPluginManager by instrumentation.targetContext.autocorrectPluginManager()
        autocorrectPluginManager.consumePredictionHints()
        instrumentation.waitForIdleSync()
    }

    private fun setEditorText(text: String) {
        instrumentation.runOnMainSync {
            editor.setText(text)
            editor.setSelection(text.length)
        }
        instrumentation.waitForIdleSync()
    }

    private fun readEditorText(): String {
        var text = ""
        instrumentation.runOnMainSync { text = editor.text.toString() }
        return text
    }

    private fun waitForText(expected: String) {
        val deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS
        do {
            val actual = readEditorText()
            if (actual == expected) return
            SystemClock.sleep(WAIT_POLL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(
            "editor text did not match expected text; expected length=${expected.length}, " +
                "actual length=${readEditorText().length}",
        )
    }

    private fun assertTextRemains(expected: String, durationMs: Long, context: String) {
        val deadline = SystemClock.uptimeMillis() + durationMs
        do {
            assertEquals("editor text changed $context", expected, readEditorText())
            SystemClock.sleep(WAIT_POLL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun setLongPressDelay(delayMs: Int) {
        val prefs by FlorisPreferenceStore
        runBlocking { prefs.keyboard.longPressDelay.set(delayMs).getOrThrow() }
    }

    private fun transferredHoldDuration(): Long =
        TEST_LONG_PRESS_DELAY_MS + repeatObservationDuration()

    private fun repeatObservationDuration(): Long =
        ViewConfiguration.getKeyRepeatDelay().toLong() * 3

    private fun waitUntil(message: String, block: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS
        do {
            if (block()) return
            SystemClock.sleep(WAIT_POLL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(message)
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor =
            instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private data class KeyPoint(
        val center: PointF,
        val smallDownwardDrift: PointF,
    )

    private data class KeyPoints(
        val n: KeyPoint,
        val v: KeyPoint,
        val b: KeyPoint,
        val space: KeyPoint,
        val delete: KeyPoint,
        val shift: KeyPoint,
        val coveredVisualGap: CoveredVisualGap,
    )

    private data class CoveredVisualGap(
        val center: PointF,
        val expectedTexts: Set<String>,
    )

    private data class SwipeActions(
        val up: SwipeAction,
        val down: SwipeAction,
        val left: SwipeAction,
        val right: SwipeAction,
    )

    private data class InjectedPointer(
        val id: Int,
        val point: PointF,
    )

    private companion object {
        const val DENSE_MOVE_COUNT = 96
        const val STATIONARY_MOVE_RADIUS_PX = 1f
        const val REUSED_POINTER_ID = 7
        const val HELD_POINTER_ID = 11
        const val DIRECT_HELD_POINTER_ID = 13
        const val LAYOUT_CHANGE_POINTER_ID = 17
        const val TEST_SUBTYPE_ID = Long.MIN_VALUE
        const val DUPLICATE_POINTER_ID_1 = 3
        const val DUPLICATE_POINTER_ID_2 = 9
        const val DENSE_LONG_PRESS_DELAY_MS = 10_000
        const val TEST_LONG_PRESS_DELAY_MS = 100
        const val REPEAT_TEST_TEXT = "abcdefghijklmnopqrstuvwxyz"
        const val REQUIRED_STABLE_LAYOUT_POLLS = 4
        const val WAIT_TIMEOUT_MS = 10_000L
        const val WAIT_POLL_MS = 50L

        fun isStaleTestSubtype(subtype: Subtype): Boolean {
            return subtype.id == TEST_SUBTYPE_ID &&
                subtype.equalsExcludingId(Subtype.DEFAULT)
        }
    }
}
