/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PreferenceStoreInitializationStateTest :
    FunSpec({
        test("app bootstrap state gates preference-backed UI") {
            ApplicationBootstrapState.LOADING.keepsSplashVisible shouldBe true
            ApplicationBootstrapState.READY.keepsSplashVisible shouldBe false
            ApplicationBootstrapState.FAILED.keepsSplashVisible shouldBe false

            ApplicationBootstrapState.LOADING.canRenderPreferenceBackedUi shouldBe false
            ApplicationBootstrapState.READY.canRenderPreferenceBackedUi shouldBe true
            ApplicationBootstrapState.FAILED.canRenderPreferenceBackedUi shouldBe false

            ApplicationBootstrapState.LOADING.isTerminalFailure shouldBe false
            ApplicationBootstrapState.READY.isTerminalFailure shouldBe false
            ApplicationBootstrapState.FAILED.isTerminalFailure shouldBe true
        }

        test("runtime failure remains distinct from preference initialization") {
            val preferenceState = PreferenceStoreInitializationState.READY
            val bootstrapState = ApplicationBootstrapState.FAILED

            preferenceState shouldBe PreferenceStoreInitializationState.READY
            bootstrapState.canRenderPreferenceBackedUi shouldBe false
        }

        test("terminal failure reaches an existing dependency once") {
            var failures = 0
            val latch = TerminalFailureLatch<Unit> { failures += 1 }

            latch.register(Unit)
            latch.fail()
            latch.fail()

            failures shouldBe 1
        }

        test("terminal failure reaches a dependency registered later") {
            var failures = 0
            val latch = TerminalFailureLatch<Unit> { failures += 1 }

            latch.fail()
            latch.register(Unit)

            failures shouldBe 1
        }
    })
