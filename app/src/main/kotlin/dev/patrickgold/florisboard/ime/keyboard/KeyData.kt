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

package dev.patrickgold.florisboard.ime.keyboard

import androidx.compose.ui.unit.LayoutDirection
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.popup.PopupSet
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Basic interface for a key data object. Base for all key data objects across the IME, such as text, emojis and
 * selectors. The implementation is as abstract as possible, as different features require different implementations.
 */
interface AbstractKeyData {
    /**
     * Computes a [KeyData] object for this key data. Returns null if no computation is possible or if the key is
     * not relevant based on the result of [evaluator].
     *
     * @param evaluator The evaluator used to retrieve different states from the parent controller.
     *
     * @return A [KeyData] object or null if no computation is possible.
     */
    fun compute(evaluator: ComputingEvaluator): KeyData?

    /**
     * Returns the data described by this key as a string.
     *
     * @param isForDisplay Specifies if the returned string is intended to be displayed in a UI label (=true) or if
     *  it should be computed to be sent to an input connection (=false).
     *
     * @return The computed string for the key data object. Note: some objects may return an empty string here, meaning
     *  it is always required to check for the string's length before attempting to directly retrieve the first char.
     */
    fun asString(isForDisplay: Boolean): String
}

/**
 * Interface describing a basic key which can carry a character, an emoji, a special function etc. while being as
 * abstract as possible.
 *
 * @property type The type of the key.
 * @property code The Unicode code point of this key, or a special code from [KeyCode].
 * @property label The label of the key. This should always be a representative string for [code].
 * @property groupId The group which this key belongs to.
 * @property popup The popups for ths key. Can also dynamically be provided via popup extensions.
 */
interface KeyData : AbstractKeyData {
    val type: KeyType
    val code: Int
    val label: String
    val groupId: Int
    val popup: PopupSet<AbstractKeyData>?

    companion object {
        /**
         * Constant for the default group. If not otherwise specified, any key is automatically
         * assigned to this group.
         */
        const val GROUP_DEFAULT: Int = 0

        /**
         * Constant for the Left modifier key group. Any key belonging to this group will get the
         * popups specified for "~left" in the popup mapping.
         */
        const val GROUP_LEFT: Int = 1

        /**
         * Constant for the right modifier key group. Any key belonging to this group will get the
         * popups specified for "~right" in the popup mapping.
         */
        const val GROUP_RIGHT: Int = 2

        /**
         * Constant for the enter modifier key group. Any key belonging to this group will get the
         * popups specified for "~enter" in the popup mapping.
         */
        const val GROUP_ENTER: Int = 3

        /**
         * Constant for the enter modifier key group. Any key belonging to this group will get the
         * popups specified for "~kana" in the popup mapping.
         */
        const val GROUP_KANA: Int = 97
    }

    fun isSpaceKey(): Boolean {
        return type == KeyType.CHARACTER && (code == KeyCode.SPACE || code == KeyCode.CJK_SPACE
            || code == KeyCode.HALF_SPACE || code == KeyCode.KESHIDA)
    }
}

/** Returns a content-free key description suitable for diagnostics. */
internal fun KeyData.contentFreeString(): String {
    val className = this::class.simpleName ?: "KeyData"
    return "$className { type=$type groupId=$groupId }"
}

/**
 * Returns whether this key can participate in predictive word input on the given keyboard.
 */
fun KeyData.isWordInput(keyboardMode: KeyboardMode): Boolean {
    if (keyboardMode != KeyboardMode.CHARACTERS || isWordSeparatorSpace()) return false
    if (type != KeyType.CHARACTER && type != KeyType.NUMERIC) return false
    val codePoint = primaryCodePoint() ?: return false
    if (type == KeyType.NUMERIC) return Character.isDigit(codePoint)
    val category = Character.getType(codePoint)
    return Character.isAlphabetic(codePoint) ||
        Character.isDigit(codePoint) ||
        codePoint == '\''.code ||
        codePoint == '\u2019'.code ||
        codePoint == '\u200C'.code ||
        codePoint == '\u200D'.code ||
        category == Character.CONNECTOR_PUNCTUATION.toInt() ||
        category == Character.NON_SPACING_MARK.toInt() ||
        category == Character.ENCLOSING_MARK.toInt() ||
        category == Character.COMBINING_SPACING_MARK.toInt()
}

/** Returns whether this key is eligible for a provider's predictive touch adjustment. */
fun KeyData.isPredictiveInput(
    keyboardMode: KeyboardMode,
    predictedCodePoints: Set<Int>,
): Boolean {
    if (keyboardMode != KeyboardMode.CHARACTERS || isWordSeparatorSpace()) return false
    return (type == KeyType.CHARACTER || type == KeyType.NUMERIC) &&
        primaryCodePoint() in predictedCodePoints
}

/** Returns whether admitting this key makes prediction hints for the next key obsolete. */
internal fun KeyData.invalidatesPredictionHints() = type != KeyType.MODIFIER

/** Returns whether this key should be represented in an autocorrect provider's touch geometry. */
fun KeyData.isAutocorrectTraceInput(keyboardMode: KeyboardMode): Boolean {
    if (keyboardMode != KeyboardMode.CHARACTERS || isWordSeparatorSpace()) return false
    if (type != KeyType.CHARACTER && type != KeyType.NUMERIC) return false
    val codePoint = primaryCodePoint() ?: return false
    return type == KeyType.CHARACTER || type == KeyType.NUMERIC && Character.isDigit(codePoint)
}

internal fun KeyData.primaryCodePoint(): Int? {
    if (code > 0) return code
    val text = asString(isForDisplay = false)
    return text.takeIf(String::isNotEmpty)?.codePointAt(0)
}

internal fun codePointCaseAndBaseVariants(codePoint: Int): Set<Int> {
    if (!Character.isValidCodePoint(codePoint)) return emptySet()
    return buildSet {
        fun addCaseVariants(value: Int) {
            add(value)
            add(Character.toLowerCase(value))
            val upper = Character.toUpperCase(value)
            add(upper)
            add(Character.toLowerCase(upper))
        }

        addCaseVariants(codePoint)
        toList().forEach { value ->
            val base = Normalizer.normalize(
                String(Character.toChars(value)),
                Normalizer.Form.NFD,
            ).codePointAt(0)
            addCaseVariants(base)
        }
    }
}

private fun KeyData.isWordSeparatorSpace(): Boolean {
    return code == KeyCode.SPACE || code == KeyCode.CJK_SPACE
}

/**
 * Chooses [upper] in uppercase state, otherwise [lower]. Usually used for text keys, but both branches accept any
 * [AbstractKeyData]. In layout JSON, use `"$": "case_selector"`.
 */
@Serializable
@SerialName("case_selector")
class CaseSelector(
    val lower: AbstractKeyData,
    val upper: AbstractKeyData,
) : AbstractKeyData {
    override fun compute(evaluator: ComputingEvaluator): KeyData? {
        return (if (evaluator.state.isUppercase) { upper } else { lower }).compute(evaluator)
    }

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

/**
 * Selects by [InputShiftState]. Usually used for text keys; every branch accepts any [AbstractKeyData].
 * In layout JSON, use `"$": "shift_state_selector"`. All branches, including `default`, are optional.
 *
 * `unshifted` and `capsLock` fall back to `default`. `shiftedManual` and `shiftedAutomatic` fall back to
 * `shifted`, then `default`. If the selected branch and its fallbacks are null, no key is shown.
 */
@Serializable
@SerialName("shift_state_selector")
class ShiftStateSelector(
    val unshifted: AbstractKeyData? = null,
    val shifted: AbstractKeyData? = null,
    val shiftedManual: AbstractKeyData? = null,
    val shiftedAutomatic: AbstractKeyData? = null,
    val capsLock: AbstractKeyData? = null,
    val default: AbstractKeyData? = null,
) : AbstractKeyData {
    override fun compute(evaluator: ComputingEvaluator): KeyData? {
        return when (evaluator.state.inputShiftState) {
            InputShiftState.UNSHIFTED -> unshifted ?: default
            InputShiftState.SHIFTED_MANUAL -> shiftedManual ?: shifted ?: default
            InputShiftState.SHIFTED_AUTOMATIC -> shiftedAutomatic ?: shifted ?: default
            InputShiftState.CAPS_LOCK -> capsLock ?: default
        }?.compute(evaluator)
    }

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

/**
 * Selects by [KeyVariation]. Usually used for text keys; every branch accepts any [AbstractKeyData].
 * In layout JSON, use `"$": "variation_selector"`. All branches are optional.
 *
 * `email` handles [KeyVariation.EMAIL_ADDRESS]; `uri`, `normal`, and `password` handle matching variations.
 * [KeyVariation.ALL] uses only `default`. Other missing branches fall back to `default`; if both are null,
 * no key is shown.
 */
@Serializable
@SerialName("variation_selector")
data class VariationSelector(
    val default: AbstractKeyData? = null,
    val email: AbstractKeyData? = null,
    val uri: AbstractKeyData? = null,
    val normal: AbstractKeyData? = null,
    val password: AbstractKeyData? = null,
) : AbstractKeyData {
    override fun compute(evaluator: ComputingEvaluator): KeyData? {
        return when (evaluator.state.keyVariation) {
            KeyVariation.ALL -> default
            KeyVariation.EMAIL_ADDRESS -> email ?: default
            KeyVariation.NORMAL -> normal ?: default
            KeyVariation.PASSWORD -> password ?: default
            KeyVariation.URI -> uri ?: default
        }?.compute(evaluator)
    }

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

/**
 * Chooses [rtl] for RTL layout direction, otherwise [ltr]. Usually used for text keys, but both branches accept
 * any [AbstractKeyData]. In layout JSON, use `"$": "layout_direction_selector"`.
 */
@Serializable
@SerialName("layout_direction_selector")
class LayoutDirectionSelector(
    val ltr: AbstractKeyData,
    val rtl: AbstractKeyData,
) : AbstractKeyData {
    override fun compute(evaluator: ComputingEvaluator): KeyData? {
        val isRtl = evaluator.state.layoutDirection == LayoutDirection.Rtl
        return (if (isRtl) { rtl } else { ltr }).compute(evaluator)
    }

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

/**
 * Chooses [half] in half-width mode, otherwise [full]. A null branch hides the key in that mode.
 * Usually used for text keys; non-null branches accept any [AbstractKeyData]. In layout JSON, use
 * `"$": "char_width_selector"`.
 */
@Serializable
@SerialName("char_width_selector")
class CharWidthSelector(
    val full: AbstractKeyData?,
    val half: AbstractKeyData?,
) : AbstractKeyData {
    override fun compute(evaluator: ComputingEvaluator): KeyData? {
        val data = if (evaluator.state.isCharHalfWidth) { half } else { full }
        return data?.compute(evaluator)
    }

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}

/**
 * Chooses [kata] in katakana mode, otherwise [hira]. Usually used for text keys, but both branches accept any
 * [AbstractKeyData]. In layout JSON, use `"$": "kana_selector"`.
 */
@Serializable
@SerialName("kana_selector")
class KanaSelector(
    val hira: AbstractKeyData,
    val kata: AbstractKeyData,
) : AbstractKeyData {
    override fun compute(evaluator: ComputingEvaluator): KeyData? {
        val data = if (evaluator.state.isKanaKata) { kata } else { hira }
        return data.compute(evaluator)
    }

    override fun asString(isForDisplay: Boolean): String {
        return ""
    }
}
