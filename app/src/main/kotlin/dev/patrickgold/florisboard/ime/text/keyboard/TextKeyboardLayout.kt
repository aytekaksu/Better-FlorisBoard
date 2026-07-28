/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.glideTypingManager
import dev.patrickgold.florisboard.ime.editor.OperationScope
import dev.patrickgold.florisboard.ime.editor.OperationUnit
import dev.patrickgold.florisboard.ime.editor.SelectionDragState
import dev.patrickgold.florisboard.ime.input.InputEventDispatcher
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarMode
import dev.patrickgold.florisboard.ime.keyboard.invalidatesPredictionHints
import dev.patrickgold.florisboard.ime.keyboard.manualSelectionEndpointIsStart
import dev.patrickgold.florisboard.ime.keyboard.setManualSelectionEndpoint
import dev.patrickgold.florisboard.ime.nlp.plugin.PredictionHintLease
import dev.patrickgold.florisboard.ime.popup.ExceptionsForKeyCodes
import dev.patrickgold.florisboard.ime.popup.PopupUiController
import dev.patrickgold.florisboard.ime.popup.rememberPopupUiController
import dev.patrickgold.florisboard.ime.text.gestures.GlideTypingGesture
import dev.patrickgold.florisboard.ime.text.gestures.SwipeActivationArea
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.gestures.SwipeGesture
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyHintPlacement
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.ime.window.LocalWindowController
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.FlorisRect
import dev.patrickgold.florisboard.lib.Pointer
import dev.patrickgold.florisboard.lib.PointerMap
import dev.patrickgold.florisboard.lib.toIntOffset
import dev.patrickgold.jetpref.datastore.model.collectAsState
import org.florisboard.lib.android.isOrientationLandscape
import org.florisboard.lib.compose.DisposableLifecycleEffect
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggText
import org.florisboard.lib.snygg.ui.isSnyggThemeElementDefined
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery
import kotlin.math.abs
import kotlin.math.sqrt

private enum class KeyActivationSource(
    val emitsPressFeedback: Boolean,
) {
    PHYSICAL_DOWN(emitsPressFeedback = true),
    POINTER_MOVE(emitsPressFeedback = false),
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextKeyboardLayout(
    modifier: Modifier = Modifier,
    evaluator: ComputingEvaluator,
): Unit = with(LocalDensity.current) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val glideTypingManager by context.glideTypingManager()

    val keyboard = evaluator.keyboard as TextKeyboard
    val glideEnabledInternal by prefs.glide.enabled.collectAsState()
    val glideEnabled = glideEnabledInternal && evaluator.editorInfo.isRichInputEditor &&
        evaluator.state.keyVariation != KeyVariation.PASSWORD
    val glideShowTrail by prefs.glide.showTrail.collectAsState()
    val glideTrailStyle = rememberSnyggThemeQuery(FlorisImeUi.GlideTrail.elementName)
    val glideTrailColor = glideTrailStyle.foreground(default = Color.Green)

    val controller = remember(keyboard) {
        TextKeyboardLayoutController(context, keyboard)
    }
    DisposableEffect(controller, configuration.smallestScreenWidthDp, density) {
        controller.updateTouchThresholds(
            smallestScreenWidthDp = configuration.smallestScreenWidthDp,
            density = density,
        )
        onDispose { }
    }
    fun resetAllKeys() {
        try {
            glideTypingManager.cancelPendingInput()
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try {
                controller.onTouchEventInternal(event)
                controller.popupUiController.hide()
            } finally {
                event.recycle()
            }
        } catch (_: Throwable) {
            // Ignore
        }
    }

    DisposableEffect(controller, glideTypingManager) {
        controller.glideTypingDetector.registerListener(controller)
        glideTypingManager.attachDetector(controller.glideTypingDetector)
        onDispose {
            resetAllKeys()
            controller.glideTypingDetector.unregisterListener(controller)
            glideTypingManager.detachDetector(controller.glideTypingDetector)
        }
    }

    DisposableLifecycleEffect(
        onResume = { /* Do nothing */ },
        onPause = { resetAllKeys() },
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.keyboardUiHeight())
            .onGloballyPositioned { coords ->
                controller.size = coords.size.toSize()
            }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                        -> {
                        controller.onTouchEvent(event)
                        return@pointerInteropFilter true
                    }
                }
                return@pointerInteropFilter false
            }
            .drawWithContent {
                drawContent()
                if (glideEnabled && glideShowTrail) {
                    val targetDist = 3.0f
                    val radius = 20.0f

                    val radiusReductionFactor = 0.99f
                    if (controller.fadingGlideRadius > 0) {
                        controller.drawGlideTrail(
                            this,
                            controller.fadingGlide,
                            targetDist,
                            controller.fadingGlideRadius,
                            radiusReductionFactor,
                            glideTrailColor,
                        )
                    }
                    if (controller.isGliding && controller.glideDataForDrawing.isNotEmpty()) {
                        controller.drawGlideTrail(
                            this, controller.glideDataForDrawing, targetDist, radius,
                            radiusReductionFactor, glideTrailColor,
                        )
                    }
                }
            },
    ) {
        // FIXME (when rewriting TextKeyboardLayout): constrains.maxWidth is not stable!
        val keyboardWidth = constraints.maxWidth.toFloat()
        val keyboardHeight = constraints.maxHeight.toFloat()
        val keyboardRowBaseHeight = FlorisImeSizing.keyboardRowBaseHeight

        val windowController = LocalWindowController.current
        val windowSpec by windowController.activeWindowSpec.collectAsState()
        val keyMarginH by remember { derivedStateOf { windowSpec.keyMarginH.toPx() } }
        val keyMarginV by remember { derivedStateOf { windowSpec.keyMarginV.toPx() } }

        val desiredKey = remember(
            keyboard, keyboardWidth, keyboardHeight, keyMarginH, keyMarginV,
            keyboardRowBaseHeight, evaluator.version,
        ) {
            TextKey(data = TextKeyData.UNSPECIFIED).also { desiredKey ->
                desiredKey.touchBounds.apply {
                    width = keyboardWidth / 10f
                    height = when (keyboard.mode) {
                        KeyboardMode.CHARACTERS,
                        KeyboardMode.NUMERIC_ADVANCED,
                        KeyboardMode.SYMBOLS,
                        KeyboardMode.SYMBOLS2 -> {
                            (keyboardHeight / keyboard.rowCount)
                                .coerceAtMost(keyboardRowBaseHeight.toPx() * 1.12f)
                        }
                        else -> keyboardRowBaseHeight.toPx()
                    }
                }
                desiredKey.visibleBounds.applyFrom(desiredKey.touchBounds).deflateBy(keyMarginH, keyMarginV)
                keyboard.layout(keyboardWidth, keyboardHeight, desiredKey, true)
            }
        }
        val autocorrectInputLayout = remember(
            keyboard,
            desiredKey,
            keyboardWidth,
            keyboardHeight,
        ) {
            keyboard.snapshotAutocorrectInputLayout(keyboardWidth, keyboardHeight)
        }
        SideEffect {
            controller.autocorrectInputLayout = autocorrectInputLayout
        }

        val desiredKeyHack = rememberUpdatedState(desiredKey) // TODO quick'n'dirty hack
        val popupUiController = rememberPopupUiController(
            key1 = keyboard,
            key2 = Unit, // TODO quick'n'dirty hack
            boundsProvider = { key ->
                val keyPopupWidth: Float
                val keyPopupHeight: Float
                when {
                    configuration.isOrientationLandscape() -> {
                        keyPopupWidth = desiredKeyHack.value.visibleBounds.width * 1.0f
                        keyPopupHeight = desiredKeyHack.value.visibleBounds.height * 3.0f
                    }
                    else -> {
                        keyPopupWidth = desiredKeyHack.value.visibleBounds.width * 1.1f
                        keyPopupHeight = desiredKeyHack.value.visibleBounds.height * 2.5f
                    }
                }
                val keyPopupDiffX = (key.visibleBounds.width - keyPopupWidth) / 2.0f
                FlorisRect.new().apply {
                    left = key.visibleBounds.left + keyPopupDiffX
                    top = key.visibleBounds.bottom - keyPopupHeight
                    right = left + keyPopupWidth
                    bottom = top + keyPopupHeight
                }
            },
            isSuitableForBasicPopup = { key ->
                if (key is TextKey) {
                    val keyCode = key.computedData.code
                    val keyType = key.computedData.type
                    val numeric = keyboard.mode == KeyboardMode.NUMERIC ||
                        keyboard.mode == KeyboardMode.PHONE || keyboard.mode == KeyboardMode.PHONE2 ||
                        keyboard.mode == KeyboardMode.NUMERIC_ADVANCED && keyType == KeyType.NUMERIC
                    keyCode > KeyCode.SPACE && keyCode != KeyCode.CJK_SPACE && !numeric
                } else {
                    true
                }
            },
            isSuitableForExtendedPopup = { key ->
                if (key is TextKey) {
                    val keyCode = key.computedData.code
                    keyCode > KeyCode.SPACE && keyCode != KeyCode.CJK_SPACE || ExceptionsForKeyCodes.contains(keyCode)
                } else {
                    true
                }
            },
        )
        popupUiController.evaluator = evaluator
        popupUiController.keyHintConfiguration = prefs.keyboard.keyHintConfiguration()
        controller.popupUiController = popupUiController
        val configureGlide = glideEnabled && keyboard.mode == KeyboardMode.CHARACTERS
        DisposableEffect(controller, glideTypingManager, configureGlide) {
            if (!configureGlide) {
                resetAllKeys()
            }
            onDispose { }
        }
        DisposableEffect(controller, glideTypingManager, autocorrectInputLayout, configureGlide) {
            if (configureGlide) {
                val layoutChanged = glideTypingManager.setLayout(
                    keys = keyboard.keys().asSequence().toList(),
                    inputLayout = autocorrectInputLayout,
                )
                if (layoutChanged) {
                    controller.glideTypingDetector.cancel()
                }
            }
            onDispose { }
        }
        val debugShowTouchBoundaries by prefs.devtools.showKeyTouchBoundaries.collectAsState()
        val keyHintPlacement by prefs.keyboard.keyHintPlacement.collectAsState()
        for (textKey in keyboard.keys()) {
            TextKeyButton(
                textKey, evaluator, desiredKey,
                debugShowTouchBoundaries, keyHintPlacement,
            )
        }

        popupUiController.RenderPopups()
    }
}

@Composable
private fun TextKeyButton(
    key: TextKey,
    evaluator: ComputingEvaluator,
    desiredKey: TextKey,
    debugShowTouchBoundaries: Boolean,
    keyHintPlacement: KeyHintPlacement,
) = with(LocalDensity.current) {
    val attributes = mapOf(
        FlorisImeUi.Attr.Code to key.computedData.code,
        FlorisImeUi.Attr.Mode to evaluator.keyboard.mode.toString(),
        FlorisImeUi.Attr.ShiftState to evaluator.state.inputShiftState.toString(),
    )
    val selector = when {
        !key.isEnabled -> SnyggSelector.DISABLED
        key.isPressed -> SnyggSelector.PRESSED
        else -> SnyggSelector.NONE
    }
    val size = remember(key, desiredKey) {
        key.visibleBounds.size.toDpSize()
    }
    SnyggBox(
        FlorisImeUi.Key.elementName,
        attributes = attributes,
        selector = selector,
        modifier = Modifier
            .requiredSize(size)
            .absoluteOffset { key.visibleBounds.topLeft.toIntOffset() },
    ) {
        val isTelPadKey = key.computedData.type == KeyType.NUMERIC && evaluator.keyboard.mode == KeyboardMode.PHONE
        key.label?.let { label ->
            val autoFitLabel = key.computedData.code == KeyCode.VIEW_NUMERIC_ADVANCED
            var customLabel = label
            if (key.computedData.code == KeyCode.SPACE) {
                val prefs by FlorisPreferenceStore
                val spaceBarMode by prefs.keyboard.spaceBarMode.collectAsState()
                when (spaceBarMode) {
                    SpaceBarMode.NOTHING -> return@let
                    SpaceBarMode.CURRENT_LANGUAGE -> {}
                    SpaceBarMode.SPACE_BAR_KEY -> customLabel = "␣"
                }
            }
            SnyggText(
                modifier = (if (autoFitLabel) {
                    Modifier.sizeIn(
                        maxWidth = size.width * 0.72f,
                        maxHeight = size.height * 0.72f,
                    )
                } else {
                    Modifier.wrapContentSize()
                })
                    .align(if (isTelPadKey) BiasAlignment(-0.5f, 0f) else Alignment.Center),
                text = customLabel,
                textAlign = if (autoFitLabel) TextAlign.Center else null,
                maxLines = if (autoFitLabel) 2 else null,
                overflow = if (autoFitLabel) TextOverflow.Clip else null,
                autoSize = if (autoFitLabel) {
                    TextAutoSize.StepBased(
                        minFontSize = 1.sp,
                        maxFontSize = size.height.value.sp,
                        stepSize = 0.5.sp,
                    )
                } else {
                    null
                },
            )
        }
        key.hintedLabel?.let { hintedLabel ->
            SnyggText(
                elementName = FlorisImeUi.KeyHint.elementName,
                attributes = attributes,
                selector = selector,
                modifier = Modifier
                    .wrapContentSize()
                    .align(if (isTelPadKey) BiasAlignment(0.5f, 0f) else Alignment.TopEnd)
                    .padding(
                        horizontal = if (keyHintPlacement == KeyHintPlacement.INSET && !isTelPadKey) {
                            (key.visibleBounds.width / 12f).toDp()
                        } else {
                            0.dp
                        },
                    ),
                text = hintedLabel,
            )
        }
        key.foregroundImageVector?.let { imageVector ->
            val iconElementName = FlorisImeUi.KeyIcon.elementName
            SnyggIcon(
                elementName = iconElementName,
                attributes = attributes,
                selector = selector,
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(
                        if (isSnyggThemeElementDefined(iconElementName)) {
                            Modifier
                        } else {
                            Modifier.scale(1.1f)
                        },
                    ),
                imageVector = imageVector,
                contentDescription = null,
            )
        }
    }
    if (debugShowTouchBoundaries) {
        Box(
            modifier = Modifier
                .requiredSize(key.touchBounds.size.toDpSize())
                .absoluteOffset { key.touchBounds.topLeft.toIntOffset() }
                .border(Dp.Hairline, Color.Red),
        )
    }
}

private class TextKeyboardLayoutController(
    private val context: Context,
    private val keyboard: TextKeyboard,
) : SwipeGesture.Listener, GlideTypingGesture.Listener {
    private val prefs by FlorisPreferenceStore
    private val autocorrectPluginManager by context.autocorrectPluginManager()
    private val editorInstance by context.editorInstance()
    private val glideTypingManager by context.glideTypingManager()
    private val keyboardManager by context.keyboardManager()

    private val inputEventDispatcher get() = keyboardManager.inputEventDispatcher
    private val inputFeedbackController get() = FlorisImeService.inputFeedbackController()
    private val keyHintConfiguration = prefs.keyboard.keyHintConfiguration()
    private var touchSlop = 0f
    private var keyHysteresisDistance = 0f
    private val pointerMap: PointerMap<TouchPointer> = PointerMap { TouchPointer() }
    lateinit var popupUiController: PopupUiController

    var isGliding by mutableStateOf(false)

    val glideTypingDetector = GlideTypingGesture.Detector(context)
    val glideDataForDrawing = mutableStateListOf<Pair<GlideTypingGesture.Detector.Position, Long>>()
    val fadingGlide = mutableStateListOf<Pair<GlideTypingGesture.Detector.Position, Long>>()
    var fadingGlideRadius by mutableFloatStateOf(0.0f)
    private var fadingGlideAnimator: ValueAnimator? = null
    private val swipeGestureDetector = SwipeGesture.Detector(this)

    var size = Size.Zero
    var autocorrectInputLayout = AutocorrectInputLayoutSnapshot(
        mode = keyboard.mode,
        width = 0f,
        height = 0f,
        keys = emptyList(),
    )
        set(value) {
            if (field != value) {
                field = value
                autocorrectPluginManager.onInputLayoutChanged(value)
            }
        }

    val isGlideEnabled: Boolean get() = prefs.glide.enabled.get() && editorInstance.activeInfo.isRichInputEditor &&
        keyboardManager.activeState.keyVariation != KeyVariation.PASSWORD

    fun updateTouchThresholds(
        smallestScreenWidthDp: Int,
        density: Float,
    ) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        keyHysteresisDistance = if (smallestScreenWidthDp >= 600) {
            maxOf(touchSlop, 40f * density)
        } else {
            touchSlop
        }
    }

    fun onTouchEvent(event: MotionEvent) {
        onTouchEventInternal(event)
    }

    fun onTouchEventInternal(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && pointerMap.size > 0) {
            for (pointer in pointerMap) {
                swipeGestureDetector.onTouchCancel(event, pointer)
                onTouchCancelInternal(event, pointer)
            }
            pointerMap.clear()
        }
        swipeGestureDetector.onTouchEvent(event)
        if (isGlideEnabled && keyboard.mode == KeyboardMode.CHARACTERS) {
            val glidePointer = pointerMap.findById(glideTypingDetector.activePointerId)
            val isNotBlocked = glidePointer?.hasTriggeredLongPress != true
            val pointerIndex = event.findPointerIndex(glideTypingDetector.activePointerId)
            val currentKey = pointerIndex.takeIf { it >= 0 }?.let {
                keyboard.getKeyForPos(event.getX(it), event.getY(it))
            }
            val initialKeyData = glidePointer?.initialKeyData
                ?.takeIf { glidePointer.startedOnVisibleKey }
            if (isNotBlocked && glideTypingDetector.onTouchEvent(event, initialKeyData, currentKey)) {
                for (pointer in pointerMap) {
                    if (pointer.activeKey != null) {
                        onTouchCancelInternal(event, pointer)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    pointerMap.clear()
                }
                isGliding = true
                return
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val pointer = pointerMap.add(pointerId, pointerIndex)
                if (pointer != null) {
                    pointer.leasePredictionHints()
                    swipeGestureDetector.onTouchDown(event, pointer)
                    onTouchDownInternal(event, pointer, KeyActivationSource.PHYSICAL_DOWN)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val oldPointer = pointerMap.findById(pointerId)
                if (oldPointer != null) {
                    swipeGestureDetector.onTouchCancel(event, oldPointer)
                    onTouchCancelInternal(event, oldPointer)
                    pointerMap.removeById(oldPointer.id)
                }
                // Commit active text keys before admitting the additional pointer.
                for (pointer in pointerMap) {
                    if (pointer.activeKeyData?.shouldCommitBeforeAdditionalPointer() == true) {
                        swipeGestureDetector.onTouchCancel(event, pointer)
                        onTouchUpInternal(event, pointer)
                    }
                }
                val pointer = pointerMap.add(pointerId, pointerIndex)
                if (pointer != null) {
                    pointer.leasePredictionHints()
                    swipeGestureDetector.onTouchDown(event, pointer)
                    onTouchDownInternal(event, pointer, KeyActivationSource.PHYSICAL_DOWN)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(pointerIndex)
                    val pointer = pointerMap.findById(pointerId)
                    if (pointer != null) {
                        pointer.index = pointerIndex
                        val alwaysTriggerOnMove = (pointer.hasTriggeredGestureMove
                            && (pointer.initialKeyData?.code == KeyCode.DELETE
                            && prefs.gestures.deleteKeySwipeLeft.get().let {
                                it == SwipeAction.DELETE_CHARACTERS_PRECISELY || it == SwipeAction.SELECT_CHARACTERS_PRECISELY
                            }
                            || pointer.initialKeyData?.code == KeyCode.SPACE
                            || pointer.initialKeyData?.code == KeyCode.CJK_SPACE))
                        if (swipeGestureDetector.onTouchMove(event, pointer, alwaysTriggerOnMove) || pointer.hasTriggeredGestureMove) {
                            if (!pointer.hasTriggeredGestureMove) {
                                pointer.hasTriggeredGestureMove = true
                                cancelPressedKey(pointer)
                            }
                        } else {
                            onTouchMoveInternal(event, pointer)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val pointer = pointerMap.findById(pointerId)
                if (pointer != null) {
                    pointer.index = pointerIndex
                    if (swipeGestureDetector.onTouchUp(event, pointer) || pointer.hasTriggeredGestureMove) {
                        if (
                            pointer.hasTriggeredGestureMove &&
                            pointer.initialKeyData?.code == KeyCode.DELETE &&
                            shouldCommitDeleteSwipeSelection(prefs.gestures.deleteKeySwipeLeft.get())
                        ) {
                            commitDeleteSwipeSelection()
                        }
                        onTouchCancelInternal(event, pointer)
                    } else {
                        onTouchUpInternal(event, pointer)
                    }
                    pointerMap.removeById(pointer.id)
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                for (pointer in pointerMap) {
                    if (pointer.id == pointerId) {
                        pointer.index = pointerIndex
                        if (swipeGestureDetector.onTouchUp(event, pointer) || pointer.hasTriggeredGestureMove) {
                            if (pointer.hasTriggeredGestureMove &&
                                pointer.initialKeyData?.code == KeyCode.DELETE &&
                                shouldCommitDeleteSwipeSelection(prefs.gestures.deleteKeySwipeLeft.get())) {
                                commitDeleteSwipeSelection()
                            }
                            onTouchCancelInternal(event, pointer)
                        } else {
                            onTouchUpInternal(event, pointer)
                        }
                    } else {
                        swipeGestureDetector.onTouchCancel(event, pointer)
                        onTouchCancelInternal(event, pointer)
                    }
                }
                pointerMap.clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                for (pointer in pointerMap) {
                    swipeGestureDetector.onTouchCancel(event, pointer)
                    onTouchCancelInternal(event, pointer)
                }
                pointerMap.clear()
            }
        }
    }

    private fun onTouchDownInternal(
        event: MotionEvent,
        pointer: TouchPointer,
        source: KeyActivationSource,
        keyOverride: TextKey? = null,
        dataOverride: KeyData? = null,
    ) {
        val x = event.getX(pointer.index)
        val y = event.getY(pointer.index)
        if (pointer.initialKeyData == null) {
            pointer.startedOnVisibleKey = keyboard.getVisibleKeyForPos(x, y) != null
        }
        val key = keyOverride ?: keyboard.getKeyForPos(
            x,
            y,
            pointer.boostedCodePoints,
        )
        if (
            key != null &&
            key.isEnabled &&
            !(source == KeyActivationSource.POINTER_MOVE && key.computedData.type == KeyType.MODIFIER)
        ) {
            val downData = dataOverride ?: key.computedData
            val hasPopupLongPress = popupUiController.isSuitableForPopups(key) &&
                key.computedPopups.getPopupKeys(keyHintConfiguration).isNotEmpty()
            val allowTransferredPopupLongPress = hasPopupLongPress && when (downData.code) {
                KeyCode.SPACE,
                KeyCode.CJK_SPACE,
                KeyCode.SHIFT,
                KeyCode.LANGUAGE_SWITCH,
                    -> false
                else -> true
            }
            val pressedKeyInfo = inputEventDispatcher.sendDown(
                data = downData,
                allowLongPress = source == KeyActivationSource.PHYSICAL_DOWN ||
                    allowTransferredPopupLongPress,
                allowRepeat = source == KeyActivationSource.PHYSICAL_DOWN,
                onLongPress = onLongPress@ {
                    pointer.hasTriggeredLongPress = true
                    when (downData.code) {
                        KeyCode.SPACE, KeyCode.CJK_SPACE -> {
                            when (prefs.gestures.spaceBarLongPress.get()) {
                                SwipeAction.NO_ACTION,
                                SwipeAction.INSERT_SPACE -> {
                                }
                                else -> {
                                    keyboardManager.executeSwipeAction(prefs.gestures.spaceBarLongPress.get())
                                }
                            }
                            true
                        }
                        KeyCode.SHIFT -> {
                            if (inputEventDispatcher.isUninterruptedEventSequence(downData)) {
                                inputEventDispatcher.sendDownUp(TextKeyData.CAPS_LOCK)
                                if (source == KeyActivationSource.PHYSICAL_DOWN) {
                                    inputFeedbackController?.keyLongPress(downData)
                                }
                            }
                            // We always return false here to prevent blockade for the up touch event
                            false
                        }
                        KeyCode.LANGUAGE_SWITCH -> {
                            inputEventDispatcher.sendDownUp(TextKeyData.SYSTEM_INPUT_METHOD_PICKER)
                            true
                        }
                        else -> {
                            if (hasPopupLongPress) {
                                popupUiController.extend(key, size)
                                if (source == KeyActivationSource.PHYSICAL_DOWN) {
                                    inputFeedbackController?.keyLongPress(downData)
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }
                },
            )
            if (pressedKeyInfo == null) {
                pointer.activeKey = null
                pointer.activeKeyData = null
                pointer.pressedKeyInfo = null
                pointer.activeInputLayout = null
                return
            }
            if (downData.invalidatesPredictionHints()) {
                autocorrectPluginManager.consumePredictionHints(pointer.predictionHintLease)
            }
            if (pointer.initialKeyData == null) {
                pointer.initialKeyData = downData
            }
            pointer.activeKeyData = downData
            pointer.pressedKeyInfo = pressedKeyInfo
            if (prefs.keyboard.popupEnabled.get() && popupUiController.isSuitableForPopups(key)) {
                popupUiController.show(key)
            }
            if (source.emitsPressFeedback) {
                inputFeedbackController?.keyPress(downData)
            }
            key.isPressed = true
            pointer.activeKey = key
            pointer.activeInputLayout = autocorrectInputLayout
            pointer.activeKeyX = x
            pointer.activeKeyY = y
        } else {
            pointer.activeKey = null
            pointer.activeKeyData = null
            pointer.pressedKeyInfo = null
            pointer.activeInputLayout = null
        }
    }

    private fun TouchPointer.leasePredictionHints() {
        predictionHintLease = autocorrectPluginManager.leaseBoostedCodePoints()
        boostedCodePoints = predictionHintLease.codePoints
    }

    private fun onTouchMoveInternal(event: MotionEvent, pointer: TouchPointer) {
        val activeKey = pointer.activeKey ?: return
        val x = event.getX(pointer.index)
        val y = event.getY(pointer.index)
        pointer.activeKeyX = x
        pointer.activeKeyY = y
        if (
            popupUiController.isShowingExtendedPopup &&
            !popupUiController.propagateMotionEvent(activeKey, x, y)
        ) {
            onTouchCancelInternal(event, pointer)
            onTouchDownInternal(event, pointer, KeyActivationSource.POINTER_MOVE)
        } else if (!popupUiController.isShowingExtendedPopup) {
            val hysteresisDistance = if (pointer.activeKeyData?.type == KeyType.MODIFIER) {
                touchSlop
            } else {
                keyHysteresisDistance
            }
            val candidateKey = keyboard.getKeyForPos(x, y, pointer.boostedCodePoints)
            when (
                resolveKeyMoveAction(
                    activeKey = activeKey,
                    candidateKey = candidateKey,
                    pointerX = x,
                    pointerY = y,
                    hysteresisDistance = hysteresisDistance,
                )
            ) {
                KeyMoveAction.KEEP -> Unit
                KeyMoveAction.CANCEL -> onTouchCancelInternal(event, pointer)
                KeyMoveAction.TRANSFER -> {
                    val targetData = candidateKey?.computedData
                    onTouchCancelInternal(event, pointer)
                    onTouchDownInternal(
                        event = event,
                        pointer = pointer,
                        source = KeyActivationSource.POINTER_MOVE,
                        keyOverride = candidateKey,
                        dataOverride = targetData,
                    )
                }
            }
        }
    }

    private fun onTouchUpInternal(event: MotionEvent, pointer: TouchPointer) {
        if (pointer.index in 0 until event.pointerCount) {
            pointer.activeKeyX = event.getX(pointer.index)
            pointer.activeKeyY = event.getY(pointer.index)
        }
        if (pointer.hasTriggeredMassSelection) {
            pointer.hasTriggeredMassSelection = false
            inputEventDispatcher.dispatchInputEvent {
                editorInstance.massSelection.end()
            }
        }
        commitActiveKey(pointer)
    }

    private fun commitActiveKey(pointer: TouchPointer) {
        val activeKey = pointer.activeKey
        val downData = pointer.activeKeyData
        val pressedKeyInfo = pointer.pressedKeyInfo
        if (activeKey != null && downData != null && pressedKeyInfo != null) {
            val ownsPressedKey = inputEventDispatcher.isPressed(downData, pressedKeyInfo)
            if (ownsPressedKey || !inputEventDispatcher.isPressed(downData.code)) {
                activeKey.isPressed = false
            }
            val hasPopups = popupUiController.isSuitableForPopups(activeKey)
            val outputData = if (pointer.hasTriggeredGestureMove) {
                null
            } else if (hasPopups) {
                popupUiController.getActiveKeyData(activeKey)
            } else {
                activeKey.computedData
            }
            when {
                outputData == null -> {
                    if (ownsPressedKey) {
                        inputEventDispatcher.sendCancel(downData, pressedKeyInfo)
                    }
                }
                outputData == downData -> {
                    if (ownsPressedKey) {
                        if (!pressedKeyInfo.blockUp) {
                            val inputTouch = captureInputTouch(downData, pointer)
                            inputEventDispatcher.dispatchInputEvent {
                                recordInputTouch(inputTouch)
                            }
                        }
                        inputEventDispatcher.sendUp(downData, pressedKeyInfo)
                    }
                }
                else -> {
                    if (
                        ownsPressedKey &&
                        inputEventDispatcher.sendCancel(downData, pressedKeyInfo)
                    ) {
                        val inputTouch = captureInputTouch(outputData, pointer)
                        inputEventDispatcher.dispatchInputEvent {
                            recordInputTouch(inputTouch)
                        }
                        inputEventDispatcher.sendDownUp(outputData)
                    }
                }
            }
            if (hasPopups) {
                popupUiController.hide()
            }
        }
        pointer.activeKey = null
        pointer.activeKeyData = null
        pointer.pressedKeyInfo = null
        pointer.activeInputLayout = null
        pointer.hasTriggeredGestureMove = false
    }

    private fun captureInputTouch(data: KeyData, pointer: TouchPointer): InputTouch {
        val inputLayout = pointer.activeInputLayout
        return InputTouch(
            data = data,
            inputLayout = inputLayout,
            isLayoutCompatible = inputLayout?.isTraceCompatibleWith(autocorrectInputLayout) == true,
            x = pointer.activeKeyX,
            y = pointer.activeKeyY,
            isPrivateSession = keyboardManager.activeState.isIncognitoMode,
        )
    }

    private fun recordInputTouch(inputTouch: InputTouch) {
        val inputLayout = inputTouch.inputLayout
        if (inputLayout == null || !inputTouch.isLayoutCompatible) {
            autocorrectPluginManager.invalidateInputTrace()
            return
        }
        autocorrectPluginManager.recordInputTouch(
            data = inputTouch.data,
            inputLayout = inputLayout,
            x = inputTouch.x,
            y = inputTouch.y,
            isPrivateSession = inputTouch.isPrivateSession,
        )
    }

    private fun onTouchCancelInternal(event: MotionEvent, pointer: TouchPointer) {
        if (pointer.hasTriggeredMassSelection) {
            pointer.hasTriggeredMassSelection = false
            inputEventDispatcher.dispatchInputEvent {
                editorInstance.massSelection.end()
            }
        }

        val activeKey = pointer.activeKey
        cancelPressedKey(pointer)
        if (activeKey != null) {
            if (popupUiController.isSuitableForPopups(activeKey)) {
                popupUiController.hide()
            }
            pointer.activeKey = null
        }
        pointer.hasTriggeredGestureMove = false
    }

    private fun cancelPressedKey(pointer: TouchPointer) {
        val activeKeyData = pointer.activeKeyData
        val pressedKeyInfo = pointer.pressedKeyInfo
        if (activeKeyData != null && pressedKeyInfo != null) {
            val ownsPressedKey = inputEventDispatcher.isPressed(activeKeyData, pressedKeyInfo)
            if (ownsPressedKey || !inputEventDispatcher.isPressed(activeKeyData.code)) {
                pointer.activeKey?.isPressed = false
            }
            if (ownsPressedKey) {
                inputEventDispatcher.sendCancel(activeKeyData, pressedKeyInfo)
            }
        } else {
            pointer.activeKey?.isPressed = false
        }
        pointer.activeKeyData = null
        pointer.pressedKeyInfo = null
        pointer.activeInputLayout = null
    }

    override fun onSwipe(event: SwipeGesture.Event): Boolean {
        val pointer = pointerMap.findById(event.pointerId) ?: return false
        if (!pointer.startedOnVisibleKey) {
            return when (prefs.gestures.swipeActivationArea.get()) {
                SwipeActivationArea.KEYS_ONLY -> false
                SwipeActivationArea.ENTIRE_KEYBOARD -> handleKeyboardSwipe(event, pointer)
            }
        }
        val initialKeyData = pointer.initialKeyData ?: return false
        val activeKey = pointer.activeKey
        val activeKeyData = pointer.activeKeyData
        return when (initialKeyData.code) {
            KeyCode.DELETE -> handleDeleteSwipe(event)
            KeyCode.SPACE, KeyCode.CJK_SPACE -> handleSpaceSwipe(event)
            else -> when {
                (initialKeyData.code == KeyCode.SHIFT && activeKeyData?.code == KeyCode.SPACE ||
                    initialKeyData.code == KeyCode.SHIFT && activeKeyData?.code == KeyCode.CJK_SPACE) &&
                    event.type == SwipeGesture.Type.TOUCH_MOVE -> handleSpaceSwipe(event)
                initialKeyData.code == KeyCode.SHIFT && activeKeyData?.code != KeyCode.SHIFT &&
                    event.type == SwipeGesture.Type.TOUCH_UP -> {
                    commitActiveKey(pointer)
                    true
                }
                initialKeyData.code > KeyCode.SPACE && !popupUiController.isShowingExtendedPopup ->
                    handleKeyboardSwipe(event, pointer)
                else -> false
            }
        }
    }

    private fun handleKeyboardSwipe(event: SwipeGesture.Event, pointer: TouchPointer): Boolean {
        if (
            isGlideEnabled && pointer.startedOnVisibleKey ||
            pointer.hasTriggeredGestureMove ||
            event.type != SwipeGesture.Type.TOUCH_UP
        ) {
            return false
        }
        val swipeAction = when (event.direction) {
            SwipeGesture.Direction.UP -> prefs.gestures.swipeUp.get()
            SwipeGesture.Direction.DOWN -> prefs.gestures.swipeDown.get()
            SwipeGesture.Direction.LEFT -> prefs.gestures.swipeLeft.get()
            SwipeGesture.Direction.RIGHT -> prefs.gestures.swipeRight.get()
            else -> SwipeAction.NO_ACTION
        }
        return if (swipeAction != SwipeAction.NO_ACTION) {
            keyboardManager.executeSwipeAction(swipeAction)
            true
        } else {
            false
        }
    }

    private fun handleDeleteSwipe(event: SwipeGesture.Event): Boolean {
        if (editorInstance.activeInfo.isRawInputEditor) return false

        return when (event.type) {
            SwipeGesture.Type.TOUCH_MOVE -> when (prefs.gestures.deleteKeySwipeLeft.get()) {
                SwipeAction.DELETE_CHARACTERS_PRECISELY, SwipeAction.SELECT_CHARACTERS_PRECISELY -> {
                    if (abs(event.relUnitCountX) > 0) {
                        inputFeedbackController?.gestureMovingSwipe(TextKeyData.DELETE)
                    }
                    updateDeleteSwipeSelection(
                        n = -event.absUnitCountX - 1,
                        unit = OperationUnit.CHARACTERS,
                        isShiftPressed = inputEventDispatcher.isPressed(KeyCode.SHIFT),
                    )
                    true
                }
                SwipeAction.DELETE_WORDS_PRECISELY, SwipeAction.SELECT_WORDS_PRECISELY -> {
                    if (abs(event.relUnitCountX) > 0) {
                        inputFeedbackController?.gestureMovingSwipe(TextKeyData.DELETE)
                    }
                    updateDeleteSwipeSelection(
                        n = -event.absUnitCountX / 2 - 1,
                        unit = OperationUnit.WORDS,
                        isShiftPressed = inputEventDispatcher.isPressed(KeyCode.SHIFT),
                    )
                    true
                }
                else -> false
            }
            SwipeGesture.Type.TOUCH_UP -> {
                if (event.direction == SwipeGesture.Direction.LEFT &&
                    prefs.gestures.deleteKeySwipeLeft.get() == SwipeAction.DELETE_WORD
                ) {
                    keyboardManager.executeSwipeAction(prefs.gestures.deleteKeySwipeLeft.get())
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun commitDeleteSwipeSelection() {
        inputEventDispatcher.dispatchInputEvent {
            if (editorInstance.activeContent.selection.isSelectionMode) {
                editorInstance.deleteBackwards(OperationUnit.CHARACTERS)
            }
        }
    }

    private fun updateDeleteSwipeSelection(
        n: Int,
        unit: OperationUnit,
        isShiftPressed: Boolean,
    ) {
        inputEventDispatcher.dispatchInputEvent {
            if (editorInstance.activeContent.selection.isValid) {
                editorInstance.setSelectionSurrounding(
                    n = n,
                    unit = unit,
                    scope = if (isShiftPressed) {
                        OperationScope.AFTER_CURSOR
                    } else {
                        OperationScope.BEFORE_CURSOR
                    },
                )
            }
        }
    }

    private fun handleSpaceSwipe(event: SwipeGesture.Event): Boolean {
        val pointer = pointerMap.findById(event.pointerId) ?: return false
        val action: SwipeAction
        val cursorAction: SwipeAction
        val directionSign: Int
        when (event.direction) {
            SwipeGesture.Direction.LEFT -> {
                action = prefs.gestures.spaceBarSwipeLeft.get()
                cursorAction = SwipeAction.MOVE_CURSOR_LEFT
                directionSign = -1
            }
            SwipeGesture.Direction.RIGHT -> {
                action = prefs.gestures.spaceBarSwipeRight.get()
                cursorAction = SwipeAction.MOVE_CURSOR_RIGHT
                directionSign = 1
            }
            else -> {
                return if (
                    event.type == SwipeGesture.Type.TOUCH_UP &&
                    event.absUnitCountY < -6
                ) {
                    keyboardManager.executeSwipeAction(prefs.gestures.spaceBarSwipeUp.get())
                    true
                } else {
                    false
                }
            }
        }
        return when {
            event.type == SwipeGesture.Type.TOUCH_UP && action != cursorAction -> {
                if (action == SwipeAction.NO_ACTION) {
                    false
                } else {
                    keyboardManager.executeSwipeAction(action)
                    true
                }
            }
            event.type == SwipeGesture.Type.TOUCH_MOVE && action == cursorAction -> {
                val units = abs(event.relUnitCountX)
                val count = if (pointer.hasTriggeredGestureMove) units else units - 1
                if (count > 0) {
                    inputFeedbackController?.gestureMovingSwipe(TextKeyData.SPACE)
                    val beginMassSelection = !pointer.hasTriggeredMassSelection
                    pointer.hasTriggeredMassSelection = true
                    moveCursorFromSpacebar(
                        pointer,
                        directionSign * count,
                        beginMassSelection,
                    )
                }
                true
            }
            else -> action != SwipeAction.NO_ACTION && action != cursorAction
        }
    }

    private fun moveCursorFromSpacebar(
        pointer: TouchPointer,
        steps: Int,
        beginMassSelection: Boolean,
    ) {
        val select = keyboardManager.activeState.isManualSelectionMode ||
            inputEventDispatcher.isPressed(KeyCode.SHIFT)
        val isManualSelectionMode = keyboardManager.activeState.isManualSelectionMode
        val establishedMovingStart = keyboardManager.activeState
            .takeIf { isManualSelectionMode }
            ?.manualSelectionEndpointIsStart
        val selectionDragSession = pointer.selectionDragSession
        inputEventDispatcher.dispatchInputEvent {
            if (beginMassSelection) {
                editorInstance.massSelection.begin()
            }
            val directMovementSucceeded = if (select) {
                val state = selectionDragSession.state ?: SelectionDragState.create(
                    editorInstance.activeContent.selection,
                    steps,
                    establishedMovingStart,
                )
                if (state != null) {
                    val next = editorInstance.moveSelectionBy(state, steps)
                    if (next != null) {
                        selectionDragSession.state = next
                        if (isManualSelectionMode) {
                            keyboardManager.activeState.batchEdit {
                                it.setManualSelectionEndpoint(next.isMovingSelectionStart)
                            }
                        }
                    }
                    next != null
                } else {
                    false
                }
            } else {
                selectionDragSession.state = null
                editorInstance.moveCursorBy(steps)
            }
            if (editorInstance.activeInfo.isRawInputEditor && !directMovementSucceeded) {
                val arrowCode = if (steps < 0) KeyCode.ARROW_LEFT else KeyCode.ARROW_RIGHT
                keyboardManager.handleArrow(
                    code = arrowCode,
                    count = abs(steps),
                    isShiftPressedOverride = select,
                )
            }
        }
    }

    override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
        if (isGlideEnabled && prefs.glide.showTrail.get()) {
            glideDataForDrawing.add(point to SystemClock.uptimeMillis())
        }
    }

    override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
        onGlideCancelled()
    }

    override fun onGlideCancelled() {
        fadingGlideAnimator?.cancel()
        val shouldAnimate = finishGlideDrawingState(
            showTrail = prefs.glide.showTrail.get(),
            activePoints = glideDataForDrawing,
            fadingPoints = fadingGlide,
        )
        if (shouldAnimate) {
            val animator = ValueAnimator.ofFloat(20.0f, 0.0f)
            animator.interpolator = AccelerateInterpolator()
            animator.duration = prefs.glide.trailDuration.get().toLong()
            animator.addUpdateListener {
                fadingGlideRadius = it.animatedValue as Float
            }
            fadingGlideAnimator = animator
            animator.start()
        } else {
            fadingGlideAnimator = null
            fadingGlideRadius = 0.0f
        }
        isGliding = false
    }

    fun drawGlideTrail(
        drawScope: ContentDrawScope,
        gestureData: MutableList<Pair<GlideTypingGesture.Detector.Position, Long>>,
        targetDist: Float,
        initialRadius: Float,
        radiusReductionFactor: Float,
        color: Color,
    ) {
        var radius = initialRadius
        var drawnPoints = 0
        var prevX = gestureData.lastOrNull()?.first?.x ?: 0.0f
        var prevY = gestureData.lastOrNull()?.first?.y ?: 0.0f
        val time = SystemClock.uptimeMillis()

        outer@ for (i in gestureData.size - 1 downTo 1) {
            if (time - gestureData[i - 1].second > prefs.glide.trailDuration.get()) break

            val dx = prevX - gestureData[i - 1].first.x
            val dy = prevY - gestureData[i - 1].first.y
            val dist = sqrt(dx * dx + dy * dy)

            val numPoints = (dist / targetDist).toInt()
            for (j in 0 until numPoints) {
                radius *= radiusReductionFactor
                val intermediateX =
                    gestureData[i].first.x * (1 - j.toFloat() / numPoints) + gestureData[i - 1].first.x * (j.toFloat() / numPoints)
                val intermediateY =
                    gestureData[i].first.y * (1 - j.toFloat() / numPoints) + gestureData[i - 1].first.y * (j.toFloat() / numPoints)
                drawScope.drawCircle(color, radius, center = Offset(intermediateX, intermediateY))
                drawnPoints += 1
                prevX = intermediateX
                prevY = intermediateY
            }
        }
    }

    private class TouchPointer : Pointer() {
        var initialKeyData: KeyData? = null
        var activeKey: TextKey? = null
        var hasTriggeredGestureMove: Boolean = false
        var hasTriggeredLongPress: Boolean = false
        var hasTriggeredMassSelection: Boolean = false
        var selectionDragSession = SelectionDragSession()
        var activeKeyData: KeyData? = null
        var pressedKeyInfo: InputEventDispatcher.PressedKeyInfo? = null
        var activeInputLayout: AutocorrectInputLayoutSnapshot? = null
        var predictionHintLease: PredictionHintLease = PredictionHintLease.Empty
        var boostedCodePoints: Set<Int> = emptySet()
        var startedOnVisibleKey: Boolean = false
        var activeKeyX: Float = 0f
        var activeKeyY: Float = 0f

        override fun reset() {
            super.reset()
            initialKeyData = null
            activeKey = null
            hasTriggeredGestureMove = false
            hasTriggeredLongPress = false
            hasTriggeredMassSelection = false
            selectionDragSession = SelectionDragSession()
            activeKeyData = null
            pressedKeyInfo = null
            activeInputLayout = null
            predictionHintLease = PredictionHintLease.Empty
            boostedCodePoints = emptySet()
            startedOnVisibleKey = false
            activeKeyX = 0f
            activeKeyY = 0f
        }

        override fun toString(): String {
            return "${TouchPointer::class.simpleName} { id=$id, index=$index, initialKeyData=$initialKeyData, activeKey=$activeKey }"
        }
    }

    private class SelectionDragSession(
        var state: SelectionDragState? = null,
    )

    private data class InputTouch(
        val data: KeyData,
        val inputLayout: AutocorrectInputLayoutSnapshot?,
        val isLayoutCompatible: Boolean,
        val x: Float,
        val y: Float,
        val isPrivateSession: Boolean,
    )
}
