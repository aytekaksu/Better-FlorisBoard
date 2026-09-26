/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll
import kotlin.math.abs

class ImeWindowConstraintsTest : FunSpec({
    val tolerance = 1e-3f.dp

    val expectedSizing = mapOf(
        ImeFormFactor.Type.DESKTOP to ExpectedSizing(
            fixedHeights = SizeRange(0.dp, 0.dp, 0.dp), compactHeight = 0.dp,
            floatingWidths = SizeRange(0.dp, 0.dp, 0.dp),
            floatingHeights = SizeRange(0.dp, 0.dp, 0.dp),
            fixedMargins = KeyMargins(6.dp, 6.dp), floatingMargins = KeyMargins(2.dp, 5.dp),
        ),
        ImeFormFactor.Type.LARGE_TABLET to ExpectedSizing(
            fixedHeights = SizeRange(209.25.dp, 496.dp, 271.25.dp), compactHeight = 217.dp,
            floatingWidths = SizeRange(333.75.dp, 640.8.dp, 400.5.dp),
            floatingHeights = SizeRange(193.75.dp, 426.25.dp, 271.25.dp),
            fixedMargins = KeyMargins(6.dp, 6.dp), floatingMargins = KeyMargins(2.dp, 5.dp),
        ),
        ImeFormFactor.Type.TABLET_LANDSCAPE to ExpectedSizing(
            fixedHeights = SizeRange(216.dp, 512.dp, 280.dp), compactHeight = 224.dp,
            floatingWidths = SizeRange(212.5.dp, 408.dp, 255.dp),
            floatingHeights = SizeRange(240.dp, 440.dp, 280.dp),
            fixedMargins = KeyMargins(2.dp, 5.dp), floatingMargins = KeyMargins(2.dp, 5.dp),
        ),
        ImeFormFactor.Type.TABLET_PORTRAIT to ExpectedSizing(
            fixedHeights = SizeRange(222.7.dp, 497.8.dp, 288.2.dp), compactHeight = 230.56.dp,
            floatingWidths = SizeRange(320.dp, 560.dp, 360.dp),
            floatingHeights = SizeRange(235.8.dp, 458.5.dp, 288.2.dp),
            fixedMargins = KeyMargins(5.dp, 5.dp), floatingMargins = KeyMargins(2.dp, 5.dp),
        ),
        ImeFormFactor.Type.PHONE_LANDSCAPE to ExpectedSizing(
            fixedHeights = SizeRange(127.75.dp, 240.9.dp, 171.55.dp), compactHeight = 137.24.dp,
            floatingWidths = SizeRange(233.8.dp, 334.dp, 250.5.dp),
            floatingHeights = SizeRange(102.2.dp, 219.dp, 164.25.dp),
            fixedMargins = KeyMargins(2.dp, 5.dp), floatingMargins = KeyMargins(1.5.dp, 3.dp),
        ),
        ImeFormFactor.Type.PHONE_PORTRAIT to ExpectedSizing(
            fixedHeights = SizeRange(140.dp, 402.5.dp, 227.5.dp), compactHeight = 182.dp,
            floatingWidths = SizeRange(217.25.dp, 355.5.dp, 256.75.dp),
            floatingHeights = SizeRange(175.dp, 350.dp, 192.5.dp),
            fixedMargins = KeyMargins(2.dp, 5.dp), floatingMargins = KeyMargins(2.dp, 5.dp),
        ),
    )

    ImeFormFactor.Type.entries.forEach { type ->
        test("exact form-factor sizing for $type") {
            val rootInsets = rootInsetsFor(type, 2000.dp, 2000.dp)
            val expected = expectedSizing.getValue(type)
            val normal = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.NORMAL)
            val thumbs = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.THUMBS)
            val compact = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.COMPACT)
            val floating = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Floating.NORMAL)

            assertSoftly {
                normal.heights().shouldMatch(expected.fixedHeights)
                thumbs.heights().shouldMatch(expected.fixedHeights)
                compact.minKeyboardHeight.shouldBeCloseTo(expected.fixedHeights.min)
                compact.maxKeyboardHeight.shouldBeCloseTo(expected.fixedHeights.max)
                compact.defKeyboardHeight.shouldBeCloseTo(expected.compactHeight)
                floating.widths().shouldMatch(expected.floatingWidths)
                floating.heights().shouldMatch(expected.floatingHeights)
                normal.margins().shouldMatch(expected.fixedMargins)
                thumbs.margins().shouldMatch(expected.fixedMargins)
                compact.margins().shouldMatch(expected.fixedMargins)
                floating.margins().shouldMatch(expected.floatingMargins)
            }
        }

        listOf(0.dp, 1.dp).forEach { rootSize ->
            test("bounded sizing for $type in ${rootSize.value}dp root") {
                val rootInsets = rootInsetsFor(type, rootSize, rootSize)
                val expectedSize = if (type == ImeFormFactor.Type.DESKTOP) 0.dp else rootSize
                for (mode in ImeWindowMode.Fixed.entries) {
                    val constraints = ImeWindowConstraints.of(rootInsets, mode)
                    assertSoftly {
                        constraints.minKeyboardHeight shouldBe expectedSize
                        constraints.maxKeyboardHeight shouldBe expectedSize
                        constraints.defKeyboardHeight shouldBe expectedSize
                        constraints.defaultProps.shouldBeConstrainedTo(constraints, tolerance)
                    }
                }
                val floating = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Floating.NORMAL)
                assertSoftly {
                    floating.minKeyboardWidth shouldBe expectedSize
                    floating.maxKeyboardWidth shouldBe expectedSize
                    floating.defKeyboardWidth shouldBe expectedSize
                    floating.minKeyboardHeight shouldBe expectedSize
                    floating.maxKeyboardHeight shouldBe expectedSize
                    floating.defKeyboardHeight shouldBe expectedSize
                    floating.defaultProps.shouldBeConstrainedTo(floating, tolerance)
                }
            }
        }

        test("shared fixed defaults and compact padding for $type") {
            val rootInsets = ImeInsets.Root(
                boundsDp = DpRect(0.dp, 0.dp, 1000.dp, 1200.dp),
                boundsPx = IntRect(0, 0, 1000, 1200),
                formFactor = ImeFormFactor(ImeFormFactor.Zero.sizeClass, type),
            )
            val normal = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.NORMAL)
            val thumbs = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.THUMBS)
            val compact = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.COMPACT)
            val floating = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Floating.NORMAL)

            normal.defaultProps shouldBe ImeWindowProps.Fixed(normal.defKeyboardHeight, 0.dp, 0.dp, 0.dp)
            thumbs.defaultProps shouldBe ImeWindowProps.Fixed(thumbs.defKeyboardHeight, 0.dp, 0.dp, 0.dp)
            compact.minPaddingHorizontal shouldBe 50.dp
            compact.defPaddingHorizontal shouldBe 70.dp
            compact.maxKeyboardWidth shouldBe 950.dp
            compact.defKeyboardWidth shouldBe 930.dp
            compact.defaultProps.paddingLeft shouldBe 70.dp

            val wideDevice = type == ImeFormFactor.Type.DESKTOP || type == ImeFormFactor.Type.LARGE_TABLET
            val phoneLandscape = type == ImeFormFactor.Type.PHONE_LANDSCAPE
            normal.defKeyMarginV shouldBe if (wideDevice) 6.dp else 5.dp
            floating.defKeyMarginH shouldBe if (phoneLandscape) 1.5.dp else 2.dp
            floating.defKeyMarginV shouldBe if (phoneLandscape) 3.dp else 5.dp
        }
    }

    context("for all root insets and fixed modes") {
        test("default props are fully visible") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)
                val defaultProps = constraints.defaultProps

                assertSoftly {
                    defaultProps.shouldBeConstrainedTo(constraints, tolerance)
                }
            }
        }

        test("0.dp <= min <= def <= max keyboard width") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minKeyboardWidth)
                    constraints.minKeyboardWidth.shouldBeLessThanOrEqualTo(constraints.defKeyboardWidth)
                    constraints.defKeyboardWidth.shouldBeLessThanOrEqualTo(constraints.maxKeyboardWidth)
                }
            }
        }

        test("0.dp <= min <= def <= max keyboard height") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minKeyboardHeight)
                    constraints.minKeyboardHeight.shouldBeLessThanOrEqualTo(constraints.defKeyboardHeight)
                    constraints.defKeyboardHeight.shouldBeLessThanOrEqualTo(constraints.maxKeyboardHeight)
                }
            }
        }

        test("0.dp <= min <= def <= max padding horizontal") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minPaddingHorizontal)
                    constraints.minPaddingHorizontal.shouldBeLessThanOrEqualTo(constraints.defPaddingHorizontal)
                    constraints.defPaddingHorizontal.shouldBeLessThanOrEqualTo(constraints.maxPaddingHorizontal)
                }
            }
        }
    }

    context("for all root insets and floating modes") {
        test("default props are fully visible") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Floating>()) { rootInsets, floatingMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, floatingMode)
                val defaultProps = constraints.defaultProps

                assertSoftly {
                    defaultProps.shouldBeConstrainedTo(constraints, tolerance)
                }
            }
        }

        test("0.dp <= min <= def <= max keyboard width") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Floating>()) { rootInsets, floatingMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, floatingMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minKeyboardWidth)
                    constraints.minKeyboardWidth.shouldBeLessThanOrEqualTo(constraints.defKeyboardWidth)
                    constraints.defKeyboardWidth.shouldBeLessThanOrEqualTo(constraints.maxKeyboardWidth)
                }
            }
        }

        test("0.dp <= min <= def <= max keyboard height") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Floating>()) { rootInsets, floatingMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, floatingMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minKeyboardHeight)
                    constraints.minKeyboardHeight.shouldBeLessThanOrEqualTo(constraints.defKeyboardHeight)
                    constraints.defKeyboardHeight.shouldBeLessThanOrEqualTo(constraints.maxKeyboardHeight)
                }
            }
        }
    }
})

private data class SizeRange(val min: Dp, val max: Dp, val default: Dp)

private data class KeyMargins(val horizontal: Dp, val vertical: Dp)

private data class ExpectedSizing(
    val fixedHeights: SizeRange,
    val compactHeight: Dp,
    val floatingWidths: SizeRange,
    val floatingHeights: SizeRange,
    val fixedMargins: KeyMargins,
    val floatingMargins: KeyMargins,
)

private fun rootInsetsFor(type: ImeFormFactor.Type, width: Dp, height: Dp) = ImeInsets.Root(
    boundsDp = DpRect(0.dp, 0.dp, width, height),
    boundsPx = IntRect.Zero,
    formFactor = ImeFormFactor(ImeFormFactor.Zero.sizeClass, type),
)

private fun ImeWindowConstraints.heights() = SizeRange(minKeyboardHeight, maxKeyboardHeight, defKeyboardHeight)

private fun ImeWindowConstraints.widths() = SizeRange(minKeyboardWidth, maxKeyboardWidth, defKeyboardWidth)

private fun ImeWindowConstraints.margins() = KeyMargins(defKeyMarginH, defKeyMarginV)

private fun SizeRange.shouldMatch(expected: SizeRange) {
    min.shouldBeCloseTo(expected.min)
    max.shouldBeCloseTo(expected.max)
    default.shouldBeCloseTo(expected.default)
}

private fun KeyMargins.shouldMatch(expected: KeyMargins) {
    horizontal shouldBe expected.horizontal
    vertical shouldBe expected.vertical
}

private fun Dp.shouldBeCloseTo(expected: Dp) {
    abs(value - expected.value).shouldBeLessThanOrEqualTo(1e-3f)
}
