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

package dev.patrickgold.florisboard.ime.text.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.PointF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.unit.IntRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeJsonConfig
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exercises the installed IME's real Compose touch surface and editor connection.
 *
 * This deliberately injects more MOVE events than the old buffered dispatcher could retain. Key
 * coordinates come from the active production keyboard, so the test follows layout sizing, spacing,
 * orientation, and window-position preferences instead of assuming a particular screen geometry. A
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
            val candidate = evaluator.keyboard as? TextKeyboard
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
                ?.keys()
                ?.asSequence()
                ?.filter { it.computedData.code in requiredCodes }
                ?.toList()
                .orEmpty()
            val keyboardHeight = candidate?.layoutHeight() ?: 0f
            val layoutSignature = if (
                candidate != null &&
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
                keyboard = candidate!!
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

    private fun switchKeyboardModeAndWait(mode: KeyboardMode, requiredCodes: Set<Int>) {
        val keyboardManager by instrumentation.targetContext.keyboardManager()
        instrumentation.runOnMainSync { keyboardManager.activeState.keyboardMode = mode }
        waitUntil("keyboard mode $mode did not settle") {
            val candidate = keyboardManager.activeEvaluator.value.keyboard as? TextKeyboard
            val codes = candidate?.keys()?.asSequence()
                ?.filter { !it.visibleBounds.isEmpty() }
                ?.map { it.computedData.code }
                ?.toSet()
                .orEmpty()
            if (candidate?.mode == mode && codes.containsAll(requiredCodes)) {
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

    private fun keyboardOrigin(keyboardHeight: Float = keyboard.layoutHeight()) = PointF(
        windowBounds.left.toFloat(),
        windowBounds.bottom - keyboardHeight,
    )

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
            val candidate = keyboardManager.activeEvaluator.value.keyboard as? TextKeyboard
            val sourceText = String(Character.toChars(code))
            val key = candidate?.keys()?.asSequence()?.firstOrNull {
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
            val signature = if (candidate != null && key != null && bounds != null) {
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
                keyboard = candidate!!
                windowBounds = bounds!!
                center = PointF(
                    bounds.left + key!!.visibleBounds.center.x,
                    bounds.bottom - candidate.layoutHeight() + key.visibleBounds.center.y,
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
        waitUntil("editor text did not become <$expected>; actual=<${readEditorText()}>") {
            readEditorText() == expected
        }
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
