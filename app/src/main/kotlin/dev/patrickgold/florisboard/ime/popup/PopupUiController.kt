/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.popup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.DefaultComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.computeImageVector
import dev.patrickgold.florisboard.ime.keyboard.computeLabel
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyHintConfiguration
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.lib.FlorisRect
import dev.patrickgold.florisboard.lib.toIntOffset

@Composable
fun rememberPopupUiController(
    key1: Any?,
    key2: Any?,
    boundsProvider: (key: TextKey) -> FlorisRect,
    isSuitableForBasicPopup: (key: TextKey) -> Boolean,
    isSuitableForExtendedPopup: (key: TextKey) -> Boolean,
): PopupUiController {
    return remember(key1, key2) {
        PopupUiController(boundsProvider, isSuitableForBasicPopup, isSuitableForExtendedPopup)
    }
}

val ExceptionsForKeyCodes = listOf(
    KeyCode.ENTER,
    KeyCode.LANGUAGE_SWITCH,
    KeyCode.IME_UI_MODE_TEXT,
    KeyCode.IME_UI_MODE_MEDIA,
    KeyCode.IME_UI_MODE_CLIPBOARD,
    KeyCode.KANA_SWITCHER,
    KeyCode.CHAR_WIDTH_SWITCHER,
)

private val priorityOffsets = intArrayOf(0, 1, -1, 2, -2)

/** Maps each visible slot to a [PopupKeys] lookup index. */
internal fun popupDisplayOrder(size: Int, prioritizedCount: Int, initialIndex: Int): IntArray {
    val indices = IntArray(size)
    // Resolved popup sets have at most three priorities. Leave unsupported counts unchanged.
    if (prioritizedCount in 1..3) {
        for (priority in 1..prioritizedCount) {
            val offset = priorityOffsets.first { delta ->
                val index = initialIndex + delta
                index in indices.indices && indices[index] == 0
            }
            indices[initialIndex + offset] = -priority
        }
    }
    var prioritizedBefore = 0
    for (index in indices.indices) {
        if (indices[index] < 0) {
            prioritizedBefore++
        } else {
            indices[index] = index - prioritizedBefore
        }
    }
    return indices
}

internal fun popupHitIndex(
    x: Float,
    y: Float,
    keyWidth: Float,
    popupWidth: Float,
    popupHeight: Float,
    anchorLeft: Boolean,
    anchorOffset: Int,
    bottomRowCount: Int,
    topRowCount: Int,
): Int? {
    if (y < -popupHeight || y > 0.9f * popupHeight) return null

    val inset = (keyWidth - popupWidth) / 2f
    val reference = if (anchorLeft) inset else keyWidth - inset
    val before = if (anchorLeft) anchorOffset + 1 else bottomRowCount + 1 - anchorOffset
    val after = if (anchorLeft) bottomRowCount + 1 - anchorOffset else anchorOffset + 1
    if (x < reference - before * popupWidth || x > reference + after * popupWidth) return null

    val topRow = y < 0 && topRowCount > 0
    val rowSize = if (topRow) topRowCount else bottomRowCount
    val rowStart = if (topRow) 0 else topRowCount
    val shift = if (anchorLeft) anchorOffset else rowSize - 1 - anchorOffset
    val unitX = x / popupWidth
    val column = when {
        unitX < -shift -> 0
        unitX >= rowSize - shift -> rowSize - 1
        // Preserve the existing truncation at exact negative cell boundaries.
        unitX < 0 -> unitX.toInt() - 1 + shift
        else -> unitX.toInt() + shift
    }
    return rowStart + column
}

class PopupUiController(
    val boundsProvider: (key: TextKey) -> FlorisRect,
    val isSuitableForBasicPopup: (key: TextKey) -> Boolean,
    val isSuitableForExtendedPopup: (key: TextKey) -> Boolean,
) {
    private var baseRenderInfo by mutableStateOf<BaseRenderInfo?>(null)
    private var extRenderInfo by mutableStateOf<ExtRenderInfo?>(null)

    private var activeElementIndex by mutableIntStateOf(-1)
    var evaluator: ComputingEvaluator = DefaultComputingEvaluator
    var keyHintConfiguration: KeyHintConfiguration = KeyHintConfiguration.HINTS_DISABLED

    /** Is true if the extended popup is visible to the user, else false */
    val isShowingExtendedPopup: Boolean
        get() = extRenderInfo != null

    fun isSuitableForPopups(key: TextKey): Boolean {
        return isSuitableForBasicPopup(key) || isSuitableForExtendedPopup(key)
    }

    /**
     * Shows a preview popup for the passed [key]. Ignores show requests for keys which
     * key code is equal to or less than [KeyCode.SPACE].
     *
     * @param key Reference to the key currently controlling the popup.
     */
    fun show(key: TextKey) {
        if (!isSuitableForBasicPopup(key)) return

        baseRenderInfo = BaseRenderInfo(
            key = key,
            bounds = boundsProvider(key),
            shouldIndicateExtendedPopups = key.computedPopups.getPopupKeys(keyHintConfiguration).isNotEmpty(),
        )
    }

    /**
     * Extends the currently showing key preview popup if there are popup keys defined in the
     * key data of the passed [key]. Ignores extend requests for key views which key code
     * is equal to or less than [KeyCode.SPACE]. An exception is made for the codes defined in
     * [ExceptionsForKeyCodes], as they most likely have special keys bound to them.
     *
     * Layout of the extended key popup: (n = key.computedPopups.size)
     *   when n <= 5: single line, row0 only
     *     _ _ _ _ _
     *     K K K K K
     *   when n > 5 && n % 2 == 1: multi line, row0 has 1 more key than row1, empty space position
     *     is depending on the current anchor
     *     anchorLeft           anchorRight
     *     K K ... K _         _ K ... K K
     *     K K ... K K         K K ... K K
     *   when n > 5 && n % 2 == 0: multi line, both same length
     *     K K ... K K
     *     K K ... K K
     *
     * @param key Reference to the key currently controlling the popup.
     */
    fun extend(key: TextKey, size: Size) {
        if (!isSuitableForExtendedPopup(key)) return

        val popupKeys = key.computedPopups.getPopupKeys(keyHintConfiguration)
        val baseBounds = baseRenderInfo?.bounds ?: boundsProvider(key)
        val keyPopupDiffX = (key.visibleBounds.width - baseBounds.width) / 2.0f

        // Anchor left if keyView is in left half of keyboardView, else anchor right
        val anchorLeft = key.visibleBounds.left < size.width / 2
        // Determine key counts for each row
        val n = popupKeys.size
        if (n <= 0) return
        val row1count = if (n > 5) n / 2 else 0
        val row0count = n - row1count

        var anchorOffset = (row0count - 1) / 2
        val availableSpace = if (anchorLeft) {
            key.visibleBounds.left + keyPopupDiffX
        } else {
            size.width - (key.visibleBounds.left + keyPopupDiffX + baseBounds.width)
        }
        while (anchorOffset > 0 && !(availableSpace >= anchorOffset * baseBounds.width)) {
            anchorOffset--
        }

        val initUiIndex = row1count + if (anchorLeft) anchorOffset else row0count - 1 - anchorOffset
        val popupIndices = popupDisplayOrder(n, popupKeys.prioritizedCount, initUiIndex)
        val uiIndices = 0 until n

        val elements: List<MutableList<Element>> = if (row1count > 0) {
            listOf(mutableListOf(), mutableListOf())
        } else {
            listOf(mutableListOf())
        }
        for (uiIndex in uiIndices) {
            val rowIndex = if (uiIndex < row1count && row1count > 0) { 1 } else { 0 }
            val adjustedIndex = popupIndices[uiIndex]
            val keyData = popupKeys[adjustedIndex]
            elements[rowIndex].add(Element(
                data = keyData,
                label = evaluator.computeLabel(keyData),
                icon = evaluator.computeImageVector(keyData),
                orderedIndex = uiIndex,
            ))
        }

        // Calculate layout params
        val extWidth = row0count * baseBounds.width
        val extHeight = baseBounds.height * 0.4f * (if (row1count > 0) 2f else 1f)
        val anchorX = if (anchorLeft) {
            -anchorOffset * baseBounds.width
        } else {
            -extWidth + baseBounds.width + anchorOffset * baseBounds.width
        }
        val x = keyPopupDiffX + anchorX + key.visibleBounds.left
        val extraTop = if (row1count > 0) (baseBounds.height * 0.4f).toInt() else 0
        val y = -baseBounds.height - extraTop + key.visibleBounds.bottom
        val extBounds = FlorisRect.new(
            left = x, top = y, right = x + extWidth, bottom = y + extHeight,
        )

        extRenderInfo = ExtRenderInfo(
            elements = elements,
            baseBounds = baseBounds,
            bounds = extBounds,
            anchorLeft = anchorLeft,
            anchorOffset = anchorOffset,
            row0count = row0count,
            row1count = row1count,
        )
        activeElementIndex = initUiIndex
    }

    /**
     * Updates the current selected key in extended popup according to the passed [xEvent] and [yEvent].
     * This function does nothing if the extended popup is not showing and will return false.
     *
     * @param key Reference to the key currently controlling the popup.
     * @param xEvent The x coordinate of the MotionEvent.
     * @param yEvent The y coordinate of the MotionEvent.
     *
     * @return True if the pointer movement is within the elements bounds, false otherwise.
     */
    fun propagateMotionEvent(key: TextKey, xEvent: Float, yEvent: Float): Boolean {
        val extRenderInfo = extRenderInfo ?: return false
        val baseBounds = extRenderInfo.baseBounds
        activeElementIndex = popupHitIndex(
            x = xEvent - key.visibleBounds.left,
            y = yEvent - key.visibleBounds.top,
            keyWidth = key.visibleBounds.width,
            popupWidth = baseBounds.width,
            popupHeight = baseBounds.height,
            anchorLeft = extRenderInfo.anchorLeft,
            anchorOffset = extRenderInfo.anchorOffset,
            bottomRowCount = extRenderInfo.row0count,
            topRowCount = extRenderInfo.row1count,
        ) ?: return false
        return true
    }

    /** Selected extended-popup choice, or the original key when no choice is active. */
    fun getActiveKeyData(key: TextKey): KeyData {
        val extRenderInfo = extRenderInfo ?: return key.computedData
        val element = getElementOrNull(extRenderInfo.elements, activeElementIndex)
        return element?.data ?: key.computedData
    }

    fun hide() {
        baseRenderInfo = null
        extRenderInfo = null
        activeElementIndex = -1
    }

    private fun getElementOrNull(elements: List<List<Element>>, index: Int): Element? {
        if (index < 0) {
            return null
        }
        var cachedIndex = index
        elements.asReversed().forEach { row ->
            if (cachedIndex >= row.size) {
                cachedIndex -= row.size
            } else {
                return row[cachedIndex]
            }
        }
        return null
    }

    @Composable
    fun RenderPopups(): Unit = with(LocalDensity.current) {
        val attributes = mapOf(
            FlorisImeUi.Attr.Mode to evaluator.keyboard.mode.toString(),
            FlorisImeUi.Attr.ShiftState to evaluator.state.inputShiftState.toString(),
        )
        baseRenderInfo?.let { renderInfo ->
            PopupBaseBox(
                modifier = Modifier
                    .requiredSize(renderInfo.bounds.size.toDpSize())
                    .absoluteOffset { renderInfo.bounds.topLeft.toIntOffset() },
                attributes = attributes,
                key = renderInfo.key,
                shouldIndicateExtendedPopups = renderInfo.shouldIndicateExtendedPopups && extRenderInfo == null,
            )
        }
        extRenderInfo?.let { renderInfo ->
            val baseBounds = renderInfo.baseBounds
            val elemWidth = baseBounds.width
            val elemHeight = baseBounds.height * 0.4f
            PopupExtBox(
                modifier = Modifier
                    .requiredSize(renderInfo.bounds.size.toDpSize())
                    .absoluteOffset { renderInfo.bounds.topLeft.toIntOffset() },
                attributes = attributes,
                elements = renderInfo.elements,
                elemArrangement = if (renderInfo.anchorLeft) {
                    Arrangement.Start
                } else {
                    Arrangement.End
                },
                elemWidth = elemWidth.toDp(),
                elemHeight = elemHeight.toDp(),
                activeElementIndex = activeElementIndex,
            )
        }
    }

    data class BaseRenderInfo(
        val key: TextKey,
        val bounds: FlorisRect,
        val shouldIndicateExtendedPopups: Boolean,
    )

    data class ExtRenderInfo(
        val elements: List<List<Element>>,
        val baseBounds: FlorisRect,
        val bounds: FlorisRect,
        val anchorLeft: Boolean,
        val anchorOffset: Int,
        val row0count: Int,
        val row1count: Int,
    )

    data class Element(
        val data: KeyData,
        val label: String?,
        val icon: ImageVector?,
        val orderedIndex: Int,
    )
}
