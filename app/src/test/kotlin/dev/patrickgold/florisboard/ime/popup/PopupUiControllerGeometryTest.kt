/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.popup

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private data class OrderCase(
    val name: String,
    val size: Int,
    val priorities: Int,
    val initialIndex: Int,
    val expected: List<Int>,
)

private data class HitCase(
    val name: String,
    val anchorLeft: Boolean,
    val x: Float,
    val y: Float,
    val expected: Int?,
)

class PopupUiControllerGeometryTest : FunSpec({
    test("priority keys stay nearest the held key in their established order") {
        listOf(
            OrderCase("no priority", 3, 0, 1, listOf(0, 1, 2)),
            OrderCase("one priority", 3, 1, 1, listOf(0, -1, 1)),
            OrderCase("two from left edge", 3, 2, 0, listOf(-1, -2, 0)),
            OrderCase("two from right edge", 3, 2, 2, listOf(0, -2, -1)),
            OrderCase("three from left edge", 7, 3, 0, listOf(-1, -2, -3, 0, 1, 2, 3)),
            OrderCase("three around center", 7, 3, 3, listOf(0, 1, -3, -1, -2, 2, 3)),
            OrderCase("three from right edge", 7, 3, 6, listOf(0, 1, 2, 3, -3, -2, -1)),
            OrderCase("unsupported fourth priority", 5, 4, 2, listOf(0, 1, 2, 3, 4)),
        ).forEach { case ->
            withClue(case.name) {
                popupDisplayOrder(case.size, case.priorities, case.initialIndex).toList() shouldBe case.expected
            }
        }
    }

    test("both anchors retain top and bottom popup selection at cell and margin edges") {
        val cases = listOf(
            HitCase("left outer margin", true, -30f, 1f, 3),
            HitCase("left exact negative boundary below", true, -20f, 1f, 2),
            HitCase("left exact negative boundary above", true, -20f, -1f, -1),
            HitCase("left just inside negative boundary below", true, -19.9f, 1f, 3),
            HitCase("left just inside negative boundary above", true, -19.9f, -1f, 0),
            HitCase("left center below", true, 0f, 1f, 4),
            HitCase("left center above", true, 0f, -1f, 1),
            HitCase("left far edge accepted", true, 90f, 1f, 6),
            HitCase("left far edge rejected", true, 90.1f, 1f, null),
            HitCase("right outer margin", false, -50f, 1f, 3),
            HitCase("right exact negative boundary below", false, -40f, 1f, 2),
            HitCase("right exact negative boundary above", false, -20f, -1f, -1),
            HitCase("right just inside negative boundary below", false, -39.9f, 1f, 3),
            HitCase("right just inside negative boundary above", false, -19.9f, -1f, 0),
            HitCase("right center below", false, 0f, 1f, 5),
            HitCase("right center above", false, 0f, -1f, 1),
            HitCase("right far edge accepted", false, 70f, 1f, 6),
            HitCase("right far edge rejected", false, 70.1f, 1f, null),
            HitCase("top y edge accepted", true, 0f, -50f, 1),
            HitCase("top y edge rejected", true, 0f, -50.1f, null),
            HitCase("bottom y edge accepted", true, 0f, 45f, 4),
            HitCase("bottom y edge rejected", true, 0f, 45.1f, null),
        )
        cases.forEach { case ->
            withClue(case.name) {
                popupHitIndex(
                    x = case.x,
                    y = case.y,
                    keyWidth = 40f,
                    popupWidth = 20f,
                    popupHeight = 50f,
                    anchorLeft = case.anchorLeft,
                    anchorOffset = 1,
                    bottomRowCount = 4,
                    topRowCount = 3,
                ) shouldBe case.expected
            }
        }
    }

    test("small popups have one row even when dragged above the held key") {
        listOf(true, false).forEach { anchorLeft ->
            withClue(if (anchorLeft) "left anchor" else "right anchor") {
                popupHitIndex(
                    x = 0f,
                    y = -1f,
                    keyWidth = 40f,
                    popupWidth = 20f,
                    popupHeight = 50f,
                    anchorLeft = anchorLeft,
                    anchorOffset = 2,
                    bottomRowCount = 5,
                    topRowCount = 0,
                ) shouldBe 2
            }
        }
    }
})
