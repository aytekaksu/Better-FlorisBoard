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
