/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.width

private class FixedSizing(
    val minHeightFactor: Float,
    val maxHeightFactor: Float,
    val defHeightFactor: Float,
    val keyMarginH: Dp,
    val keyMarginV: Dp,
)

private fun ImeFormFactor.Type.fixedSizing(): FixedSizing = when (this) {
    ImeFormFactor.Type.DESKTOP -> FixedSizing(0.27f, 0.64f, 0.35f, 6.dp, 6.dp)
    ImeFormFactor.Type.LARGE_TABLET -> FixedSizing(0.27f, 0.64f, 0.35f, 6.dp, 6.dp)
    ImeFormFactor.Type.TABLET_LANDSCAPE -> FixedSizing(0.27f, 0.64f, 0.35f, 2.dp, 5.dp)
    ImeFormFactor.Type.TABLET_PORTRAIT -> FixedSizing(0.17f, 0.38f, 0.22f, 5.dp, 5.dp)
    ImeFormFactor.Type.PHONE_LANDSCAPE -> FixedSizing(0.35f, 0.66f, 0.47f, 2.dp, 5.dp)
    ImeFormFactor.Type.PHONE_PORTRAIT -> FixedSizing(0.16f, 0.46f, 0.26f, 2.dp, 5.dp)
}

private class FloatingSizing(
    val minWidthFactor: Float,
    val maxWidthFactor: Float,
    val defWidthFactor: Float,
    val minHeightFactor: Float,
    val maxHeightFactor: Float,
    val defHeightFactor: Float,
    val keyMarginH: Dp,
    val keyMarginV: Dp,
)

private fun ImeFormFactor.Type.floatingSizing(): FloatingSizing = when (this) {
    ImeFormFactor.Type.DESKTOP -> FloatingSizing(0.25f, 0.48f, 0.30f, 0.25f, 0.55f, 0.35f, 2.dp, 5.dp)
    ImeFormFactor.Type.LARGE_TABLET -> FloatingSizing(0.25f, 0.48f, 0.30f, 0.25f, 0.55f, 0.35f, 2.dp, 5.dp)
    ImeFormFactor.Type.TABLET_LANDSCAPE -> FloatingSizing(0.25f, 0.48f, 0.30f, 0.30f, 0.55f, 0.35f, 2.dp, 5.dp)
    ImeFormFactor.Type.TABLET_PORTRAIT -> FloatingSizing(0.40f, 0.70f, 0.45f, 0.18f, 0.35f, 0.22f, 2.dp, 5.dp)
    ImeFormFactor.Type.PHONE_LANDSCAPE -> FloatingSizing(0.28f, 0.40f, 0.30f, 0.28f, 0.60f, 0.45f, 1.5.dp, 3.dp)
    ImeFormFactor.Type.PHONE_PORTRAIT -> FloatingSizing(0.55f, 0.90f, 0.65f, 0.20f, 0.40f, 0.22f, 2.dp, 5.dp)
}

/**
 * The window constraints describe all relevant sizing minimums, maximums, defaults, and scaling factors
 * for given root bounds and window mode + sub-mode.
 *
 * From a definition standpoint, constraints follows a strict inheritance hierarchy, mirroring the window
 * mode + sub-mode model, with this class serving as the base class for all modes and sub-modes.
 *
 * All calculations should be wrapped in [calculation], to ensure they are lazily evaluated in-case a child
 * class overrides a property used in the calculation.
 *
 * All constraints for a specific property and mode + sub-mode should be designed in a way that the
 * condition `0dp <= min <= def <= max` always holds. Any potential floating point rounding errors must
 * be handled by the calculation, e.g. by using proper coerce{AtLeast,AtMost,In} rules.
 *
 * Additionally, all of the above must be valid for all non-negative root bounds, including zero bounds.
 * Negative bounds, or non-real bounds are undefined behavior and most likely lead to a crash.
 */
sealed class ImeWindowConstraints(rootInsets: ImeInsets.Root) {
    val rootBounds = rootInsets.boundsDp
    val formFactor = rootInsets.formFactor

    protected val baselineScreen = BaselineScreens.getValue(formFactor.typeGuess)

    abstract val minKeyboardWidth: Dp
    abstract val maxKeyboardWidth: Dp
    abstract val defKeyboardWidth: Dp

    abstract val minKeyboardHeight: Dp
    abstract val maxKeyboardHeight: Dp
    abstract val defKeyboardHeight: Dp

    abstract val defKeyMarginH: Dp
    abstract val defKeyMarginV: Dp

    open val baselineRowCount: Float = 4f
    open val smartbarDynamicScalingFactor = 0.20f
    open val smartbarStaticScalingFactor by calculation { 0.753f - smartbarDynamicScalingFactor }

    open val resizeHandleTouchSize: Dp = 48.dp
    open val resizeHandleTouchOffsetFloating: Dp by calculation { resizeHandleTouchSize / 2 }
    open val resizeHandleDrawSize: Dp = 32.dp
    open val resizeHandleDrawPadding: Dp by calculation { (resizeHandleTouchSize - resizeHandleDrawSize) / 2 }
    open val resizeHandleDrawThickness: Dp by calculation { resizeHandleDrawSize / 4 }
    open val resizeHandleDrawCornerRadius: Dp by calculation { resizeHandleDrawSize / 2 }

    open val dockToFixedHeight: Dp by calculation {
        when (formFactor.typeGuess) {
            ImeFormFactor.Type.DESKTOP,
            ImeFormFactor.Type.LARGE_TABLET,
            ImeFormFactor.Type.TABLET_LANDSCAPE,
            ImeFormFactor.Type.TABLET_PORTRAIT -> 80.dp
            ImeFormFactor.Type.PHONE_LANDSCAPE -> 50.dp
            ImeFormFactor.Type.PHONE_PORTRAIT -> 80.dp
        }
    }
    open val dockToFixedBorder: Dp = 2.dp

    abstract val defaultProps: ImeWindowProps

    protected fun <T> calculation(initializer: () -> T) = lazy(LazyThreadSafetyMode.PUBLICATION, initializer)

    sealed class Fixed(rootInsets: ImeInsets.Root) : ImeWindowConstraints(rootInsets) {
        private val sizing by calculation { formFactor.typeGuess.fixedSizing() }

        protected open val desiredMinPaddingHorizontal = 0.dp
        protected open val desiredDefPaddingHorizontal = 0.dp
        open val minPaddingHorizontal by calculation { rootBounds.width - maxKeyboardWidth }
        open val maxPaddingHorizontal by calculation { rootBounds.width - minKeyboardWidth }
        open val defPaddingHorizontal by calculation { rootBounds.width - defKeyboardWidth }

        override val minKeyboardWidth by calculation {
            when (formFactor.typeGuess) {
                ImeFormFactor.Type.TABLET_PORTRAIT -> min(rootBounds.width - desiredMinPaddingHorizontal, 400.dp)
                else -> min(rootBounds.width - desiredMinPaddingHorizontal, 250.dp)
            }.coerceIn(0.dp, rootBounds.width)
        }
        override val maxKeyboardWidth by calculation {
            (rootBounds.width - desiredMinPaddingHorizontal).coerceAtLeast(minKeyboardWidth)
        }
        override val defKeyboardWidth by calculation {
            (rootBounds.width - desiredDefPaddingHorizontal).coerceAtLeast(minKeyboardWidth)
        }

        override val minKeyboardHeight by calculation {
            (baselineScreen.height * sizing.minHeightFactor).coerceAtMost(rootBounds.height)
        }
        override val maxKeyboardHeight by calculation {
            (baselineScreen.height * sizing.maxHeightFactor).coerceIn(minKeyboardHeight, rootBounds.height)
        }
        override val defKeyboardHeight by calculation {
            (baselineScreen.height * sizing.defHeightFactor).coerceIn(minKeyboardHeight, maxKeyboardHeight)
        }

        override val defKeyMarginH by calculation { sizing.keyMarginH }
        override val defKeyMarginV by calculation { sizing.keyMarginV }

        open val snapToCenterWidth: Dp by lazy {
            when (formFactor.typeGuess) {
                ImeFormFactor.Type.DESKTOP -> 80.dp
                ImeFormFactor.Type.LARGE_TABLET,
                ImeFormFactor.Type.TABLET_LANDSCAPE,
                ImeFormFactor.Type.TABLET_PORTRAIT -> 64.dp
                ImeFormFactor.Type.PHONE_LANDSCAPE -> 48.dp
                ImeFormFactor.Type.PHONE_PORTRAIT -> 32.dp
            }
        }

        override val defaultProps by calculation {
            ImeWindowProps.Fixed(
                keyboardHeight = defKeyboardHeight,
                paddingLeft = 0.dp,
                paddingRight = 0.dp,
                paddingBottom = 0.dp,
            )
        }

        class Normal(rootInsets: ImeInsets.Root) : Fixed(rootInsets)

        class Compact(rootInsets: ImeInsets.Root) : Fixed(rootInsets) {
            override val defKeyboardHeight by calculation {
                (super.defKeyboardHeight * 0.8f).coerceAtLeast(minKeyboardHeight)
            }

            override val desiredMinPaddingHorizontal = 50.dp
            override val desiredDefPaddingHorizontal = 70.dp

            override val defaultProps by calculation {
                ImeWindowProps.Fixed(
                    keyboardHeight = defKeyboardHeight,
                    paddingLeft = defPaddingHorizontal,
                    paddingRight = 0.dp,
                    paddingBottom = super.defKeyboardHeight - defKeyboardHeight,
                )
            }
        }

        class Thumbs(rootInsets: ImeInsets.Root) : Fixed(rootInsets)
    }

    sealed class Floating(rootInsets: ImeInsets.Root) : ImeWindowConstraints(rootInsets) {
        private val sizing by calculation { formFactor.typeGuess.floatingSizing() }

        override val minKeyboardWidth by calculation {
            (baselineScreen.width * sizing.minWidthFactor).coerceAtMost(rootBounds.width)
        }
        override val maxKeyboardWidth by calculation {
            (baselineScreen.width * sizing.maxWidthFactor).coerceIn(minKeyboardWidth, rootBounds.width)
        }
        override val defKeyboardWidth by calculation {
            (baselineScreen.width * sizing.defWidthFactor).coerceIn(minKeyboardWidth, maxKeyboardWidth)
        }

        override val minKeyboardHeight by calculation {
            (baselineScreen.height * sizing.minHeightFactor).coerceAtMost(rootBounds.height)
        }
        override val maxKeyboardHeight by calculation {
            (baselineScreen.height * sizing.maxHeightFactor).coerceIn(minKeyboardHeight, rootBounds.height)
        }
        override val defKeyboardHeight by calculation {
            (baselineScreen.height * sizing.defHeightFactor).coerceIn(minKeyboardHeight, maxKeyboardHeight)
        }

        override val defKeyMarginH by calculation { sizing.keyMarginH }
        override val defKeyMarginV by calculation { sizing.keyMarginV }

        abstract override val defaultProps: ImeWindowProps.Floating

        class Normal(rootInsets: ImeInsets.Root) : Floating(rootInsets) {
            override val defaultProps by calculation {
                ImeWindowProps.Floating(
                    keyboardHeight = defKeyboardHeight,
                    keyboardWidth = defKeyboardWidth,
                    offsetLeft = 60.dp.coerceAtMost(rootBounds.width - defKeyboardWidth),
                    offsetBottom = 60.dp.coerceAtMost(rootBounds.height - defKeyboardHeight),
                )
            }
        }
    }

    companion object {
        val BaselineScreens = mapOf(
            ImeFormFactor.Type.PHONE_PORTRAIT to DpSize(width = 395.dp, height = 875.dp),
            ImeFormFactor.Type.PHONE_LANDSCAPE to DpSize(width = 835.dp, height = 365.dp),
            ImeFormFactor.Type.TABLET_PORTRAIT to DpSize(width = 800.dp, height = 1310.dp),
            ImeFormFactor.Type.TABLET_LANDSCAPE to DpSize(width = 850.dp, height = 800.dp),
            ImeFormFactor.Type.LARGE_TABLET to DpSize(width = 1335.dp, height = 775.dp),
            ImeFormFactor.Type.DESKTOP to DpSize(width = 0.dp, height = 0.dp),
        )

        /**
         * Constructs a new fixed constraints instance inheriting from given [rootInsets] and [fixedMode].
         */
        fun of(rootInsets: ImeInsets.Root, fixedMode: ImeWindowMode.Fixed): Fixed {
            return when (fixedMode) {
                ImeWindowMode.Fixed.NORMAL -> Fixed.Normal(rootInsets)
                ImeWindowMode.Fixed.COMPACT -> Fixed.Compact(rootInsets)
                ImeWindowMode.Fixed.THUMBS -> Fixed.Thumbs(rootInsets)
            }
        }

        /**
         * Constructs a new floating constraints instance inheriting from given [rootInsets] and [floatingMode].
         */
        fun of(rootInsets: ImeInsets.Root, floatingMode: ImeWindowMode.Floating): Floating {
            return when (floatingMode) {
                ImeWindowMode.Floating.NORMAL -> Floating.Normal(rootInsets)
            }
        }
    }
}
