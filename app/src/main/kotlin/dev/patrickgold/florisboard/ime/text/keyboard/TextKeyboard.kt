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

import dev.patrickgold.florisboard.ime.keyboard.Key
import dev.patrickgold.florisboard.ime.keyboard.Keyboard
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.isAutocorrectTraceInput
import dev.patrickgold.florisboard.ime.keyboard.isPredictiveInput
import dev.patrickgold.florisboard.ime.keyboard.isWordInput
import dev.patrickgold.florisboard.ime.keyboard.primaryCodePoint
import dev.patrickgold.florisboard.ime.popup.PopupMapping
import dev.patrickgold.florisboard.lib.FlorisRect
import org.florisboard.autocorrect.api.AutocorrectKeyGeometry
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import kotlin.math.abs
import kotlin.math.sqrt

class TextKeyboard(
    val arrangement: Array<Array<TextKey>>,
    override val mode: KeyboardMode,
    val extendedPopupMapping: PopupMapping?,
    val extendedPopupMappingDefault: PopupMapping?,
) : Keyboard() {
    val rowCount: Int
        get() = arrangement.size

    val keyCount: Int
        get() = arrangement.sumOf { it.size }

    override fun getKeyForPos(pointerX: Float, pointerY: Float): TextKey? {
        for (key in keys()) {
            if (key.touchBounds.contains(pointerX, pointerY)) {
                return key
            }
        }
        return null
    }

    fun getVisibleKeyForPos(pointerX: Float, pointerY: Float): TextKey? {
        return keys().asSequence().firstOrNull { key ->
            key.isEnabled && key.isVisible && key.visibleBounds.contains(pointerX, pointerY)
        }
    }

    fun getKeyForPos(
        pointerX: Float,
        pointerY: Float,
        boostedCodePoints: Set<Int>,
    ): TextKey? {
        val regularKey = getKeyForPos(pointerX, pointerY)
        if (
            mode != KeyboardMode.CHARACTERS ||
            boostedCodePoints.isEmpty() ||
            regularKey != null &&
            (!regularKey.isEnabled || !regularKey.computedData.isWordInput(mode))
        ) {
            return regularKey
        }

        var primaryKey: TextKey? = null
        var minDistance = Float.POSITIVE_INFINITY
        for (key in keys()) {
            val keyCodePoint = key.computedData.primaryCodePoint()
            if (
                !key.isEnabled ||
                keyCodePoint == null ||
                !key.computedData.isPredictiveInput(mode, boostedCodePoints)
            ) {
                continue
            }
            val bounds = key.hitTestBounds()
            val distance = bounds.distanceToEdge(pointerX, pointerY)
            if (distance > bounds.minDimension * 0.5f) {
                continue
            }
            if (
                distance < minDistance ||
                distance == minDistance &&
                (primaryKey == null ||
                    keyCodePoint > (primaryKey.computedData.primaryCodePoint() ?: Int.MIN_VALUE))
            ) {
                primaryKey = key
                minDistance = distance
            }
        }

        if (regularKey != null && regularKey.isEnabled) {
            val distance = regularKey.hitTestBounds().distanceToCenter(pointerX, pointerY)
            if (
                distance < minDistance ||
                distance == minDistance &&
                (primaryKey == null ||
                    (regularKey.computedData.primaryCodePoint() ?: Int.MIN_VALUE) >
                    (primaryKey.computedData.primaryCodePoint() ?: Int.MIN_VALUE))
            ) {
                primaryKey = regularKey
            }
        }
        return primaryKey ?: regularKey
    }

    override fun layout(
        keyboardWidth: Float,
        keyboardHeight: Float,
        desiredKey: Key,
        extendTouchBoundariesDownwards: Boolean,
    ) {
        if (arrangement.isEmpty()) return

        val desiredTouchBounds = desiredKey.touchBounds
        val desiredVisibleBounds = desiredKey.visibleBounds
        if (desiredTouchBounds.isEmpty() || desiredVisibleBounds.isEmpty()) return
        if (keyboardWidth.isNaN() || keyboardHeight.isNaN()) return
        val rowMarginH = abs(desiredTouchBounds.width - desiredVisibleBounds.width)
        val rowMarginV = (keyboardHeight - desiredTouchBounds.height * rowCount.toFloat()) / (rowCount - 1).coerceAtLeast(1).toFloat()

        for ((r, row) in rows().withIndex()) {
            val posY = (desiredTouchBounds.height + rowMarginV) * r
            val availableWidth = (keyboardWidth - rowMarginH) / desiredTouchBounds.width
            var requestedWidth = 0.0f
            var shrinkSum = 0.0f
            var growSum = 0.0f
            for (key in row) {
                requestedWidth += key.flayWidthFactor
                shrinkSum += key.flayShrink
                growSum += key.flayGrow
            }
            if (requestedWidth <= availableWidth) {
                // Requested with is smaller or equal to the available with, so we can grow
                val additionalWidth = availableWidth - requestedWidth
                var posX = rowMarginH / 2.0f
                for ((k, key) in row.withIndex()) {
                    val keyWidth = desiredTouchBounds.width * when (growSum) {
                        0.0f -> when (k) {
                            0, row.size - 1 -> key.flayWidthFactor + additionalWidth / 2.0f
                            else -> key.flayWidthFactor
                        }
                        else -> key.flayWidthFactor + additionalWidth * (key.flayGrow / growSum)
                    }
                    key.touchBounds.apply {
                        left = posX
                        top = posY
                        right = posX + keyWidth
                        bottom = posY + desiredTouchBounds.height
                    }
                    key.visibleBounds.apply {
                        left = key.touchBounds.left + abs(desiredTouchBounds.left - desiredVisibleBounds.left) + when {
                            growSum == 0.0f && k == 0 -> ((additionalWidth / 2.0f) * desiredTouchBounds.width)
                            else -> 0.0f
                        }
                        top = key.touchBounds.top + abs(desiredTouchBounds.top - desiredVisibleBounds.top)
                        right = key.touchBounds.right - abs(desiredTouchBounds.right - desiredVisibleBounds.right) - when {
                            growSum == 0.0f && k == row.size - 1 -> ((additionalWidth / 2.0f) * desiredTouchBounds.width)
                            else -> 0.0f
                        }
                        bottom = key.touchBounds.bottom - abs(desiredTouchBounds.bottom - desiredVisibleBounds.bottom)
                    }
                    posX += keyWidth
                    // After-adjust touch bounds for the row margin
                    key.touchBounds.apply {
                        if (k == 0) {
                            left = 0.0f
                        } else if (k == row.size - 1) {
                            right = keyboardWidth
                        }
                        if (extendTouchBoundariesDownwards && r + 1 == arrangement.size) {
                            bottom += height
                        }
                    }
                }
            } else {
                // Requested size too big, must shrink.
                val clippingWidth = requestedWidth - availableWidth
                var posX = rowMarginH / 2.0f
                for ((k, key) in row.withIndex()) {
                    val keyWidth = desiredTouchBounds.width * if (key.flayShrink == 0.0f) {
                        key.flayWidthFactor
                    } else {
                        key.flayWidthFactor - clippingWidth * (key.flayShrink / shrinkSum)
                    }
                    key.touchBounds.apply {
                        left = posX
                        top = posY
                        right = posX + keyWidth
                        bottom = posY + desiredTouchBounds.height
                    }
                    key.visibleBounds.apply {
                        left = key.touchBounds.left + abs(desiredTouchBounds.left - desiredVisibleBounds.left)
                        top = key.touchBounds.top + abs(desiredTouchBounds.top - desiredVisibleBounds.top)
                        right = key.touchBounds.right - abs(desiredTouchBounds.right - desiredVisibleBounds.right)
                        bottom = key.touchBounds.bottom - abs(desiredTouchBounds.bottom - desiredVisibleBounds.bottom)
                    }
                    posX += keyWidth
                    // After-adjust touch bounds for the row margin
                    key.touchBounds.apply {
                        if (k == 0) {
                            left = 0.0f
                        } else if (k == row.size - 1) {
                            right = keyboardWidth
                        }
                        if (extendTouchBoundariesDownwards && r + 1 == arrangement.size) {
                            bottom += height
                        }
                    }
                }
            }
        }
    }

    override fun keys(): Iterator<TextKey> {
        return TextKeyboardIterator(arrangement)
    }

    fun rows(): Iterator<Array<TextKey>> {
        return arrangement.iterator()
    }

    class TextKeyboardIterator internal constructor(
        private val arrangement: Array<Array<TextKey>>
    ) : Iterator<TextKey> {
        private var rowIndex: Int = 0
        private var keyIndex: Int = 0

        override fun hasNext(): Boolean {
            return rowIndex < arrangement.size && keyIndex < arrangement[rowIndex].size
        }

        override fun next(): TextKey {
            val next = arrangement[rowIndex][keyIndex]
            if (keyIndex + 1 == arrangement[rowIndex].size) {
                rowIndex++
                keyIndex = 0
            } else {
                keyIndex++
            }
            return next
        }
    }
}

internal fun TextKey.hitTestBounds(): FlorisRect {
    return visibleBounds.takeUnless { it.isEmpty() } ?: touchBounds
}

internal data class AutocorrectInputLayoutSnapshot(
    val mode: KeyboardMode,
    val width: Float,
    val height: Float,
    val keys: List<AutocorrectKeyGeometry>,
)

internal fun AutocorrectInputLayoutSnapshot.isTraceCompatibleWith(
    other: AutocorrectInputLayoutSnapshot,
): Boolean {
    if (
        mode != other.mode ||
        width != other.width ||
        height != other.height ||
        keys.size != other.keys.size
    ) {
        return false
    }
    return keys.indices.all { index ->
        val first = keys[index]
        val second = other.keys[index]
        first.left == second.left &&
            first.top == second.top &&
            first.right == second.right &&
            first.bottom == second.bottom &&
            first.text.equals(second.text, ignoreCase = true)
    }
}

internal fun TextKeyboard.snapshotAutocorrectInputLayout(
    width: Float,
    height: Float,
): AutocorrectInputLayoutSnapshot {
    val keys = if (width > 0f && height > 0f) {
        keys().asSequence().mapNotNull { key ->
            val data = key.computedData
            val bounds = key.hitTestBounds()
            if (
                !key.isEnabled ||
                !key.isVisible ||
                bounds.isEmpty() ||
                !data.isAutocorrectTraceInput(mode)
            ) {
                return@mapNotNull null
            }
            AutocorrectKeyGeometry(
                text = data.asString(isForDisplay = false),
                left = bounds.left / width,
                top = bounds.top / height,
                right = bounds.right / width,
                bottom = bounds.bottom / height,
            )
        }.take(AutocorrectPluginContract.MAX_TRACE_KEY_COUNT).toList()
    } else {
        emptyList()
    }
    return AutocorrectInputLayoutSnapshot(mode, width, height, keys)
}

internal fun TextKey.containsWithHysteresis(x: Float, y: Float, distance: Float): Boolean {
    return hitTestBounds().distanceToEdge(x, y) < distance
}

private fun FlorisRect.distanceToEdge(x: Float, y: Float): Float {
    val dx = maxOf(left - x, 0f, x - right)
    val dy = maxOf(top - y, 0f, y - bottom)
    return sqrt(dx * dx + dy * dy)
}

private fun FlorisRect.distanceToCenter(x: Float, y: Float): Float {
    val dx = x - center.x
    val dy = y - center.y
    return sqrt(dx * dx + dy * dy)
}
