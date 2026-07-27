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

package dev.patrickgold.florisboard.ime.text.keyboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class TextKeyboardLayoutStateTest : FunSpec({
    context("spacebar movement fallback") {
        test("raw cursor movement falls back when direct movement is unavailable") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = true,
                directMovementSucceeded = false,
            ) shouldBe true
        }

        test("raw selection movement falls back when direct movement is unavailable") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = true,
                directMovementSucceeded = false,
            ) shouldBe true
        }

        test("rich cursor boundary does not send a fallback command") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = false,
                directMovementSucceeded = true,
            ) shouldBe false
        }

        test("rich selection boundary does not send a fallback command") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = false,
                directMovementSucceeded = true,
            ) shouldBe false
        }

        test("rich direct movement failure does not send a fallback command") {
            shouldFallbackSpacebarMovementToArrow(
                isRawInputEditor = false,
                directMovementSucceeded = false,
            ) shouldBe false
        }
    }

    context("glide drawing state") {
        test("points are collected only while glide trail drawing is enabled") {
            shouldCollectGlideDrawingPoint(isGlideEnabled = true, showTrail = true) shouldBe true
            shouldCollectGlideDrawingPoint(isGlideEnabled = true, showTrail = false) shouldBe false
            shouldCollectGlideDrawingPoint(isGlideEnabled = false, showTrail = true) shouldBe false
        }

        test("completed visible trail moves active points into the fading trail") {
            val activePoints = mutableListOf(1, 2)
            val fadingPoints = mutableListOf(0)

            finishGlideDrawingState(
                showTrail = true,
                activePoints = activePoints,
                fadingPoints = fadingPoints,
            ) shouldBe true

            activePoints.shouldBeEmpty()
            fadingPoints shouldBe listOf(1, 2)
        }

        test("completed hidden trail clears active and stale fading points") {
            val activePoints = mutableListOf(1, 2)
            val fadingPoints = mutableListOf(0)

            finishGlideDrawingState(
                showTrail = false,
                activePoints = activePoints,
                fadingPoints = fadingPoints,
            ) shouldBe false

            activePoints.shouldBeEmpty()
            fadingPoints.shouldBeEmpty()
        }
    }
})
