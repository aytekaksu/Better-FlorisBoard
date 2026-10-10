/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.plusOrMinus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class QuickActionLayoutTest : FunSpec({
    test("overflow tiles grow vertically with the system font scale") {
        quickActionOverflowTileAspectRatio(1f) shouldBe 0.85f
        quickActionOverflowTileAspectRatio(1.3f) shouldBe 0.85f / 1.3f
        quickActionOverflowTileAspectRatio(0.85f) shouldBe 0.85f
    }

    test("landscape tiles retain a readable minimum width") {
        val tolerance = 0.001.dp
        quickActionOverflowMinimumWidth(36.dp, isLandscape = false) shouldBe
            79.2.dp.plusOrMinus(tolerance)
        quickActionOverflowMinimumWidth(36.dp, isLandscape = true) shouldBe
            105.6.dp.plusOrMinus(tolerance)
        quickActionOverflowMinimumWidth(52.dp, isLandscape = true) shouldBe
            114.4.dp.plusOrMinus(tolerance)
    }
})
