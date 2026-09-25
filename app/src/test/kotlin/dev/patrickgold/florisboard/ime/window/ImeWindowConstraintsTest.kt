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

class ImeWindowConstraintsTest : FunSpec({
    val tolerance = 1e-3f.dp

    ImeFormFactor.Type.entries.forEach { type ->
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
