/*
 * Copyright (C) 2020-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.popup

import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.text.key.KeyHintConfiguration
import dev.patrickgold.florisboard.ime.text.key.KeyHintMode
import kotlinx.serialization.Serializable

/**
 * Popup definitions for one key. [main] is normally shown before [relevant]; the active
 * [KeyHintConfiguration] can change that order. Layout hints are added at runtime, not in popup JSON.
 */
@Serializable
open class PopupSet<T : AbstractKeyData>(open val main: T? = null, open val relevant: List<T> = listOf()) {
    private val popupKeys: PopupKeys<T> by lazy {
        PopupKeys(null, listOfNotNull(main), relevant)
    }

    open fun getPopupKeys(keyHintConfiguration: KeyHintConfiguration): PopupKeys<T> = popupKeys
}

class MutablePopupSet : PopupSet<KeyData>() {
    private val mutableRelevant = arrayListOf<KeyData>()
    private val symbolPopups = arrayListOf<KeyData>()
    private val numberPopups = arrayListOf<KeyData>()
    private val configCache = mutableMapOf<KeyHintConfiguration, PopupKeys<KeyData>>()

    override var main: KeyData? = null
        private set

    override val relevant: List<KeyData>
        get() = mutableRelevant

    var symbolHint: KeyData? = null
        set(value) {
            field = value
            configCache.clear()
        }

    var numberHint: KeyData? = null
        set(value) {
            field = value
            configCache.clear()
        }

    fun clear() {
        symbolHint = null
        numberHint = null
        main = null
        mutableRelevant.clear()
        symbolPopups.clear()
        numberPopups.clear()
        configCache.clear()
    }

    override fun getPopupKeys(keyHintConfiguration: KeyHintConfiguration): PopupKeys<KeyData> =
        configCache.getOrPut(keyHintConfiguration) {
            resolvePopupKeys(keyHintConfiguration)
        }

    private fun resolvePopupKeys(keyHintConfiguration: KeyHintConfiguration): PopupKeys<KeyData> {
        val localMain = main
        val localRelevant = relevant
        val symbol = symbolHint.takeUnless { keyHintConfiguration.symbolHintMode == KeyHintMode.DISABLED }
        val number = numberHint.takeUnless { keyHintConfiguration.numberHintMode == KeyHintMode.DISABLED }
        val promoteRelevant = localMain == null && localRelevant.isNotEmpty() &&
            shouldPromoteRelevant(keyHintConfiguration, symbol, number)
        val primary = localMain ?: localRelevant.firstOrNull().takeIf { promoteRelevant }
        val prioritized = when {
            symbol == null -> prioritizeSingle(number, keyHintConfiguration.numberHintMode, localMain, primary)
            number == null -> prioritizeSingle(symbol, keyHintConfiguration.symbolHintMode, localMain, primary)
            else -> prioritizeBoth(symbol, number, keyHintConfiguration, localMain, primary)
        }
        val hintPopups = mergedHintPopups(keyHintConfiguration, symbol, number)
        val remainingRelevant = if (promoteRelevant) localRelevant.drop(1) else localRelevant
        return PopupKeys(symbol ?: number, prioritized, remainingRelevant + hintPopups)
    }

    private fun shouldPromoteRelevant(config: KeyHintConfiguration, symbol: KeyData?, number: KeyData?) = when {
        symbol != null && number != null ->
            config.symbolHintMode == KeyHintMode.ACCENT_PRIORITY &&
                config.numberHintMode == KeyHintMode.ACCENT_PRIORITY

        symbol != null -> config.symbolHintMode == KeyHintMode.ACCENT_PRIORITY

        number != null -> config.numberHintMode == KeyHintMode.ACCENT_PRIORITY

        else -> false
    }

    private fun prioritizeSingle(hint: KeyData?, mode: KeyHintMode, main: KeyData?, primary: KeyData?) = when (mode) {
        KeyHintMode.HINT_PRIORITY -> listOfNotNull(hint, main)
        else -> listOfNotNull(primary, hint)
    }

    private fun prioritizeBoth(
        symbol: KeyData,
        number: KeyData,
        config: KeyHintConfiguration,
        main: KeyData?,
        primary: KeyData?,
    ) = when {
        config.symbolHintMode == KeyHintMode.HINT_PRIORITY -> {
            if (config.numberHintMode == KeyHintMode.HINT_PRIORITY) {
                listOfNotNull(symbol, number, main)
            } else {
                listOfNotNull(symbol, main, number)
            }
        }

        config.numberHintMode == KeyHintMode.HINT_PRIORITY -> listOfNotNull(number, main, symbol)

        config.symbolHintMode == KeyHintMode.ACCENT_PRIORITY &&
            config.numberHintMode == KeyHintMode.ACCENT_PRIORITY -> listOfNotNull(primary, symbol, number)

        config.symbolHintMode == KeyHintMode.ACCENT_PRIORITY -> listOfNotNull(main, number, symbol)

        else -> listOfNotNull(main, symbol, number)
    }

    private fun mergedHintPopups(config: KeyHintConfiguration, symbol: KeyData?, number: KeyData?): List<KeyData> =
        when {
            !config.mergeHintPopups -> emptyList()
            symbol != null && number != null -> symbolPopups + numberPopups
            symbol != null -> symbolPopups
            number != null -> numberPopups
            else -> emptyList()
        }

    fun merge(other: PopupSet<AbstractKeyData>, evaluator: ComputingEvaluator) {
        mergeInternal(other, evaluator, mutableRelevant, useMain = true)
    }

    fun mergeSymbolHint(hintPopups: PopupSet<AbstractKeyData>, evaluator: ComputingEvaluator) {
        mergeInternal(hintPopups, evaluator, symbolPopups)
    }

    fun mergeNumberHint(hintPopups: PopupSet<AbstractKeyData>, evaluator: ComputingEvaluator) {
        mergeInternal(hintPopups, evaluator, numberPopups)
    }

    private fun mergeInternal(
        other: PopupSet<AbstractKeyData>,
        evaluator: ComputingEvaluator,
        targetList: MutableList<KeyData>,
        useMain: Boolean = false,
    ) {
        configCache.clear()
        other.relevant.mapNotNullTo(targetList) { it.compute(evaluator) }
        other.main?.compute(evaluator)?.let { data ->
            if (useMain && main == null) {
                main = data
            } else {
                targetList.add(data)
            }
        }
    }
}

/**
 * Resolved popup keys. Negative indexes select prioritized keys (`-1` is first); nonnegative
 * indexes select the remaining keys. [hint] is drawn on the base key and also occurs in the popup.
 */
class PopupKeys<T>(val hint: T?, private val prioritized: List<T>, private val other: List<T>) {
    companion object {
        const val FIRST_PRIORITIZED = -1
        const val SECOND_PRIORITIZED = -2
        const val THIRD_PRIORITIZED = -3
    }

    val size: Int
        get() = prioritized.size + other.size

    val prioritizedCount: Int
        get() = prioritized.size

    fun isNotEmpty() = prioritized.isNotEmpty() || other.isNotEmpty()

    operator fun get(index: Int) = if (index < 0) prioritized[-index - 1] else other[index]
}
