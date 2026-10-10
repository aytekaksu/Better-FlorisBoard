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

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.patrickgold.florisboard.plusOrMinus
import dev.patrickgold.florisboard.shouldBeGreaterThanOrEqualTo
import dev.patrickgold.florisboard.shouldBeLessThanOrEqualTo
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

fun ImeWindowSpec.shouldBeFixedNormal() = this
    .shouldBeInstanceOf<ImeWindowSpec.Fixed>()
    .also { fixedMode shouldBe ImeWindowMode.Fixed.NORMAL }

fun ImeWindowSpec.shouldBeFixedCompact() = this
    .shouldBeInstanceOf<ImeWindowSpec.Fixed>()
    .also { fixedMode shouldBe ImeWindowMode.Fixed.COMPACT }

fun ImeWindowProps.Fixed.shouldBeConstrainedTo(constraints: ImeWindowConstraints.Fixed, tolerance: Dp) {
    val rootBounds = constraints.rootBounds
    withClue("keyboard height should be constrained") {
        keyboardHeight.shouldBeGreaterThanOrEqualTo(constraints.minKeyboardHeight)
        keyboardHeight.shouldBeLessThanOrEqualTo(constraints.maxKeyboardHeight)
    }
    withClue("padding left+right should not push window out of root bounds") {
        paddingLeft.shouldBeGreaterThanOrEqualTo(0.dp)
        paddingRight.shouldBeGreaterThanOrEqualTo(0.dp)
        (paddingLeft + paddingRight).shouldBeGreaterThanOrEqualTo(constraints.minPaddingHorizontal, tolerance)
        (paddingLeft + paddingRight).shouldBeLessThanOrEqualTo(constraints.maxPaddingHorizontal, tolerance)
    }
    withClue("padding bottom should not push window out of root bounds") {
        paddingBottom.shouldBeGreaterThanOrEqualTo(0.dp)
        (paddingBottom + keyboardHeight).shouldBeLessThanOrEqualTo(rootBounds.height, tolerance)
    }
}

fun ImeWindowProps.Floating.shouldBeConstrainedTo(constraints: ImeWindowConstraints.Floating, tolerance: Dp) {
    val rootBounds = constraints.rootBounds
    withClue("keyboard height should be constrained") {
        keyboardHeight.shouldBeGreaterThanOrEqualTo(constraints.minKeyboardHeight)
        keyboardHeight.shouldBeLessThanOrEqualTo(constraints.maxKeyboardHeight)
    }
    withClue("keyboard width should be constrained") {
        keyboardWidth.shouldBeGreaterThanOrEqualTo(constraints.minKeyboardWidth)
        keyboardWidth.shouldBeLessThanOrEqualTo(constraints.maxKeyboardWidth)
    }
    withClue("offset left should not push window out of root bounds") {
        offsetLeft.shouldBeGreaterThanOrEqualTo(0.dp)
        offsetLeft.shouldBeLessThanOrEqualTo(rootBounds.width - keyboardWidth, tolerance)
    }
    withClue("offset bottom should not push window out of root bounds") {
        offsetBottom.shouldBeGreaterThanOrEqualTo(0.dp)
        offsetBottom.shouldBeLessThanOrEqualTo(rootBounds.height - keyboardHeight, tolerance)
    }
}

internal enum class ExpectedDpChange {
    SAME,
    NOT_LESS,
    NOT_GREATER,
}

internal fun Dp.shouldMatch(before: Dp, change: ExpectedDpChange, tolerance: Dp, property: String) =
    withClue(property) {
        when (change) {
            ExpectedDpChange.SAME -> this shouldBe before.plusOrMinus(tolerance)
            ExpectedDpChange.NOT_LESS -> shouldBeGreaterThanOrEqualTo(before, tolerance)
            ExpectedDpChange.NOT_GREATER -> shouldBeLessThanOrEqualTo(before, tolerance)
        }
    }

// Compare the original offset: negating it can change signed-zero ordering.
internal fun Dp.shouldFollow(before: Dp, offset: Dp, tolerance: Dp, property: String, inverse: Boolean = false) {
    val negativeChange = if (inverse) ExpectedDpChange.NOT_LESS else ExpectedDpChange.NOT_GREATER
    val positiveChange = if (inverse) ExpectedDpChange.NOT_GREATER else ExpectedDpChange.NOT_LESS
    if (offset <= 0.dp) shouldMatch(before, negativeChange, tolerance, property)
    if (offset >= 0.dp) shouldMatch(before, positiveChange, tolerance, property)
}
