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
