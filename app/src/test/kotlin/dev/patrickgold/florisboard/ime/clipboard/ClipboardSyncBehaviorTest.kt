/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipboardSyncBehaviorTest :
    FunSpec({
        test("the Android clipboard always drives a non-internal clipboard") {
            ClipboardSyncBehavior.entries.forEach { configured ->
                resolveSyncToFlorisBehavior(
                    useInternalClipboard = false,
                    configuredBehavior = configured,
                ) shouldBe ClipboardSyncBehavior.ALL_EVENTS
            }
        }

        test("an internal clipboard retains every configured sync mode") {
            ClipboardSyncBehavior.entries.forEach { configured ->
                resolveSyncToFlorisBehavior(
                    useInternalClipboard = true,
                    configuredBehavior = configured,
                ) shouldBe configured
            }
        }

        test("clear-only sync still requires system clipboard reads") {
            ClipboardSyncBehavior.NO_EVENTS.requiresSystemClipboardRead shouldBe false
            ClipboardSyncBehavior.ONLY_CLEAR_EVENTS.requiresSystemClipboardRead shouldBe true
            ClipboardSyncBehavior.ONLY_SET_EVENTS.requiresSystemClipboardRead shouldBe true
            ClipboardSyncBehavior.ALL_EVENTS.requiresSystemClipboardRead shouldBe true
        }
    })
