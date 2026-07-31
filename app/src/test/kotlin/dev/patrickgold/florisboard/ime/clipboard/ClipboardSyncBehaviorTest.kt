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
