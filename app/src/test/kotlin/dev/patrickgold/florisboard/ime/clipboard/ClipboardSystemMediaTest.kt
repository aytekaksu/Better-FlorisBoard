/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ClipboardSystemMediaTest :
    FunSpec({
        test("collects every distinct owned item") {
            val items = listOf(null, "image", "video", "image")

            collectOwnedSystemMedia(items.size) { index ->
                listOfNotNull(items[index])
            } shouldBe
                setOf("image", "video")
        }

        test("rejects partial ownership when an item cannot be inspected") {
            collectOwnedSystemMedia(3) { index ->
                if (index == 1) error("unavailable")
                listOf("owned-$index")
            }.shouldBeNull()
        }

        test("collects every owned representation from each item") {
            collectOwnedSystemMedia(2) { index ->
                when (index) {
                    0 -> listOf("direct", "intent")
                    else -> listOf("intent", "other")
                }
            } shouldBe setOf("direct", "intent", "other")
        }

        test("rejects unreasonable item counts without inspecting items") {
            var inspected = false

            collectOwnedSystemMedia(10_001) {
                inspected = true
                listOf("owned")
            }.shouldBeNull()
            inspected shouldBe false
        }
    })
