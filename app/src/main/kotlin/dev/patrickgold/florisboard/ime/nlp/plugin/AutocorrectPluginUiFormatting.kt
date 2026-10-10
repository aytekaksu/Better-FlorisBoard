/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import java.math.BigDecimal
import java.math.RoundingMode

internal fun AutocorrectPluginUiItem.sliderSteps(): Int {
    if (step <= 0.0 || !step.isFinite()) return 0
    val division = BigDecimal.valueOf(maximum)
        .subtract(BigDecimal.valueOf(minimum))
        .divideAndRemainder(BigDecimal.valueOf(step))
    val intervals = division[0]
    return if (
        division[1].signum() == 0 &&
        intervals >= BigDecimal.ONE &&
        intervals <= BigDecimal.valueOf(1_001L)
    ) {
        intervals.toInt() - 1
    } else {
        0
    }
}

internal fun AutocorrectPluginUiItem.formattedSliderValue(value: Float): String {
    val normalizedValue = normalizedSliderDecimal(value)
    if (step <= 0.0 || !step.isFinite()) return normalizedValue.toFloat().toString()
    val precision = maxOf(
        minimum.decimalPrecision(),
        maximum.decimalPrecision(),
        step.decimalPrecision(),
    )
    return normalizedValue
        .setScale(precision, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

internal fun AutocorrectPluginUiItem.normalizedSliderValue(value: Float): Float =
    normalizedSliderDecimal(value).toFloat()

internal fun AutocorrectPluginUiItem.formattedCurrentSliderValue(): String? = value
    ?.toFloatOrNull()
    ?.takeIf(Float::isFinite)
    ?.let(::formattedSliderValue)

internal data class AppChoiceSupportingText(val label: String?, val summary: String?)

internal fun AutocorrectPluginUiItem.appChoiceSupportingText(): AppChoiceSupportingText {
    val selectedLabel = options.firstOrNull { it.value == value }?.label
        ?.takeIf(String::isNotBlank)
    val providerSummary = summary
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { it == selectedLabel }
    return AppChoiceSupportingText(selectedLabel, providerSummary)
}

private fun AutocorrectPluginUiItem.normalizedSliderDecimal(value: Float): BigDecimal {
    val minimum = BigDecimal.valueOf(minimum)
    val maximum = BigDecimal.valueOf(maximum)
    val boundedValue = BigDecimal.valueOf(value.toDouble()).max(minimum).min(maximum)
    if (step <= 0.0 || !step.isFinite()) return boundedValue

    val step = BigDecimal.valueOf(step)
    val steppedValue = minimum.add(
        boundedValue.subtract(minimum)
            .divide(step, 0, RoundingMode.HALF_UP)
            .multiply(step),
    ).max(minimum).min(maximum)
    return if (
        maximum.subtract(boundedValue).abs() <=
        steppedValue.subtract(boundedValue).abs()
    ) {
        maximum
    } else {
        steppedValue
    }
}

private fun Double.decimalPrecision() = BigDecimal.valueOf(this).stripTrailingZeros().scale().coerceAtLeast(0)
