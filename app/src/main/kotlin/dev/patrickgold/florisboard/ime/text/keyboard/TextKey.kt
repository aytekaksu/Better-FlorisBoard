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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.computeImageVector
import dev.patrickgold.florisboard.ime.keyboard.computeLabel
import dev.patrickgold.florisboard.ime.popup.MutablePopupSet
import dev.patrickgold.florisboard.ime.popup.PopupSet
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.lib.FlorisRect
import dev.patrickgold.florisboard.lib.lowercase

/** One computed key, including its render state and keyboard-local bounds. */
class TextKey(val data: AbstractKeyData) {
    var isEnabled: Boolean by mutableStateOf(true)

    /** Draws the key as pressed. */
    var isPressed: Boolean by mutableStateOf(false)

    /** False omits the key from layout and drawing, like View.GONE. */
    var isVisible: Boolean by mutableStateOf(true)

    /** Touch bounds in parent-keyboard coordinates. */
    val touchBounds: FlorisRect = FlorisRect.empty()

    /** Visible bounds in parent-keyboard coordinates. */
    val visibleBounds: FlorisRect = FlorisRect.empty()

    /** Computed shrink weight for a crowded row; zero prevents shrinking. */
    var flayShrink: Float = 0f

    /** Computed grow weight for spare row space; zero opts out of proportional growth. */
    var flayGrow: Float = 0f

    /**
     * Requested width relative to the desired key width: 1 is full width; hidden keys use 0.
     * [compute] owns the three sizing factors; layout uses them to calculate actual bounds.
     */
    var flayWidthFactor: Float = 0f

    // Cached rendering values, set by computeLabelsAndDrawables.
    var label: String? = null
    var hintedLabel: String? = null
    var foregroundImageVector: ImageVector? = null

    var computedData: KeyData = TextKeyData.UNSPECIFIED
        private set
    val computedPopups = MutablePopupSet()
    var computedSymbolHint: KeyData? = null
    var computedNumberHint: KeyData? = null

    fun compute(evaluator: ComputingEvaluator) {
        val keyboard = evaluator.keyboard
        val keyboardMode = keyboard.mode
        val computed = data.compute(evaluator)

        if (computed == null || !evaluator.evaluateVisible(computed)) {
            computedData = TextKeyData.UNSPECIFIED
            computedPopups.clear()
            isEnabled = false
            isVisible = false

            flayShrink = 0.0f
            flayGrow = 0.0f
            flayWidthFactor = 0.0f
        } else {
            computedData = computed
            computedPopups.clear()
            mergePopups(computed, evaluator, computedPopups::merge)
            if (keyboardMode == KeyboardMode.CHARACTERS || keyboardMode == KeyboardMode.NUMERIC_ADVANCED ||
                keyboardMode == KeyboardMode.SYMBOLS || keyboardMode == KeyboardMode.SYMBOLS2) {
                val computedLabel = computed.label.lowercase(evaluator.subtype.primaryLocale)
                val extLabel = when (computed.groupId) {
                    KeyData.GROUP_ENTER -> "~enter"
                    KeyData.GROUP_LEFT -> "~left"
                    KeyData.GROUP_RIGHT -> "~right"
                    KeyData.GROUP_KANA -> "~kana"
                    else -> computedLabel
                }
                val popupSet = when (evaluator.state.keyVariation) {
                    KeyVariation.PASSWORD -> popupSetFor(keyboard, KeyVariation.PASSWORD, extLabel)
                        ?: popupSetFor(keyboard, KeyVariation.NORMAL, extLabel)
                    KeyVariation.NORMAL -> popupSetFor(keyboard, KeyVariation.NORMAL, extLabel)
                    KeyVariation.EMAIL_ADDRESS -> popupSetFor(keyboard, KeyVariation.EMAIL_ADDRESS, extLabel)
                        ?: popupSetFor(keyboard, KeyVariation.URI, extLabel)
                    KeyVariation.URI -> popupSetFor(keyboard, KeyVariation.URI, extLabel)
                    KeyVariation.ALL -> null
                } ?: popupSetFor(keyboard, KeyVariation.ALL, extLabel)
                val keySpecificPopupSet = if (extLabel != computedLabel) {
                    popupSetFor(keyboard, KeyVariation.ALL, computedLabel)
                } else {
                    null
                }
                computedPopups.apply {
                    keySpecificPopupSet?.let { merge(it, evaluator) }
                    popupSet?.let { merge(it, evaluator) }
                }
                if (computed.type == KeyType.CHARACTER) {
                    addComputedHints(computed.code, evaluator, keyboard)
                }
            }
            isEnabled = evaluator.evaluateEnabled(computed)
            isVisible = true

            flayShrink = when (keyboardMode) {
                KeyboardMode.NUMERIC,
                KeyboardMode.NUMERIC_ADVANCED,
                KeyboardMode.PHONE,
                KeyboardMode.PHONE2 -> 1.0f
                else -> when (computed.code) {
                    KeyCode.SHIFT,
                    KeyCode.DELETE -> 1.5f
                    KeyCode.VIEW_CHARACTERS,
                    KeyCode.VIEW_SYMBOLS,
                    KeyCode.VIEW_SYMBOLS2,
                    KeyCode.ENTER -> 0.0f
                    else -> 1.0f
                }
            }
            flayGrow = when (keyboardMode) {
                KeyboardMode.NUMERIC,
                KeyboardMode.PHONE,
                KeyboardMode.PHONE2 -> 0.0f
                KeyboardMode.NUMERIC_ADVANCED -> when (computed.type) {
                    KeyType.NUMERIC -> 1.0f
                    else -> 0.0f
                }
                else -> when (computed.code) {
                    KeyCode.SPACE, KeyCode.CJK_SPACE -> 1.0f
                    else -> 0.0f
                }
            }
            flayWidthFactor = when (keyboardMode) {
                KeyboardMode.NUMERIC,
                KeyboardMode.PHONE,
                KeyboardMode.PHONE2 -> 2.68f
                KeyboardMode.NUMERIC_ADVANCED -> when (computed.code) {
                    44, 46 -> 1.00f
                    KeyCode.VIEW_SYMBOLS, 61 -> 1.26f
                    else -> 1.56f
                }
                else -> when (computed.code) {
                    KeyCode.SHIFT,
                    KeyCode.DELETE,
                    KeyCode.VIEW_CHARACTERS,
                    KeyCode.VIEW_SYMBOLS,
                    KeyCode.VIEW_SYMBOLS2,
                    KeyCode.ENTER -> 1.56f
                    else -> 1.00f
                }
            }
        }
    }

    private fun addComputedHints(
        keyCode: Int,
        evaluator: ComputingEvaluator,
        keyboard: TextKeyboard,
    ) {
        val symbolHint = computedSymbolHint
        if (symbolHint != null) {
            val evaluatedSymbolHint = symbolHint.compute(evaluator)
            if (symbolHint.code != keyCode) {
                computedPopups.symbolHint = evaluatedSymbolHint
                mergePopups(evaluatedSymbolHint, evaluator, computedPopups::mergeSymbolHint)
                val hintSpecificPopupSet = popupSetFor(keyboard, KeyVariation.ALL, symbolHint.label)
                hintSpecificPopupSet?.let { computedPopups.mergeSymbolHint(it, evaluator) }
            }
        }
        val numericHint = computedNumberHint
        if (numericHint != null) {
            val evaluatedNumberHint = numericHint.compute(evaluator)
            if (numericHint.code != keyCode) {
                computedPopups.numberHint = evaluatedNumberHint
                mergePopups(evaluatedNumberHint, evaluator, computedPopups::mergeNumberHint)
                val hintSpecificPopupSet = popupSetFor(keyboard, KeyVariation.ALL, numericHint.label)
                hintSpecificPopupSet?.let { computedPopups.mergeNumberHint(it, evaluator) }
            }
        }
    }

    private fun mergePopups(
        keyData: KeyData?,
        evaluator: ComputingEvaluator,
        merge: (popups: PopupSet<AbstractKeyData>, evaluator: ComputingEvaluator) -> Unit,
    ) {
        keyData?.popup?.let { merge(it, evaluator) }
    }

    private fun popupSetFor(
        keyboard: TextKeyboard,
        variation: KeyVariation,
        label: String,
    ): PopupSet<AbstractKeyData>? = keyboard.extendedPopupMapping?.get(variation)?.get(label)
        ?: keyboard.extendedPopupMappingDefault?.get(variation)?.get(label)

    /**
     * Computes the label, hintedLabel and iconResId for [computedData] based on given [evaluator].
     */
    fun computeLabelsAndDrawables(evaluator: ComputingEvaluator) {
        label = evaluator.computeLabel(computedData)
        hintedLabel = null
        foregroundImageVector = evaluator.computeImageVector(computedData)

        val data = computedData
        if (data.type == KeyType.NUMERIC && evaluator.keyboard.mode == KeyboardMode.PHONE) {
            hintedLabel = when (data.code) {
                48 /* 0 */ -> "+"
                49 /* 1 */ -> ""
                50 /* 2 */ -> "ABC"
                51 /* 3 */ -> "DEF"
                52 /* 4 */ -> "GHI"
                53 /* 5 */ -> "JKL"
                54 /* 6 */ -> "MNO"
                55 /* 7 */ -> "PQRS"
                56 /* 8 */ -> "TUV"
                57 /* 9 */ -> "WXYZ"
                else -> null
            }
        } else if (!data.isSpaceKey() || data.type == KeyType.NUMERIC) {
            val prefs by FlorisPreferenceStore
            computedPopups.getPopupKeys(prefs.keyboard.keyHintConfiguration()).hint.let { hintData ->
                if (hintData?.isSpaceKey() == false) {
                    hintedLabel = hintData.asString(isForDisplay = true)
                } else {
                    hintedLabel = null
                }
            }
        }
    }

    override fun toString(): String {
        return computedData.toString()
    }
}
