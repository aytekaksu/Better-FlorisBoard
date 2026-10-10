/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.floats.FloatToleranceMatcher
import io.kotest.matchers.floats.gt
import io.kotest.matchers.floats.gte
import io.kotest.matchers.floats.lt
import io.kotest.matchers.floats.lte
import io.kotest.matchers.shouldBe

fun Dp.shouldBeLessThan(other: Dp, tolerance: Dp = 0.dp): Dp {
    this.value shouldBe lt((other + tolerance).value)
    return this
}

fun Dp.shouldBeLessThanOrEqualTo(other: Dp, tolerance: Dp = 0.dp): Dp {
    this.value shouldBe lte((other + tolerance).value)
    return this
}

fun Dp.shouldBeGreaterThan(other: Dp, tolerance: Dp = 0.dp): Dp {
    this.value shouldBe gt((other - tolerance).value)
    return this
}

fun Dp.shouldBeGreaterThanOrEqualTo(other: Dp, tolerance: Dp = 0.dp): Dp {
    this.value shouldBe gte((other - tolerance).value)
    return this
}

infix fun Dp.plusOrMinus(tolerance: Dp): DpToleranceMatcher = DpToleranceMatcher(this, tolerance)

class DpToleranceMatcher(expected: Dp, tolerance: Dp) : Matcher<Dp> {
    val matcher = FloatToleranceMatcher(expected.value, tolerance.value)

    override fun test(value: Dp): MatcherResult {
        return matcher.test(value.value)
    }
}
