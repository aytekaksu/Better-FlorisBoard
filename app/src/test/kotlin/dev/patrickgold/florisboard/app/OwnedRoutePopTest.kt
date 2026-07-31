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

package dev.patrickgold.florisboard.app

import androidx.lifecycle.Lifecycle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

class OwnedRoutePopTest :
    FunSpec({
        test("only the exact resumed owner may pop") {
            val owner = EqualEntry(1)
            var popCalls = 0

            popOwnedRoute(owner, owner, true) {
                popCalls++
                true
            } shouldBe OwnedRoutePopResult.POPPED
            popCalls shouldBe 1

            popOwnedRoute(EqualEntry(1), owner, true) {
                popCalls++
                true
            } shouldBe OwnedRoutePopResult.NOT_CURRENT
            popOwnedRoute(owner, owner, false) {
                popCalls++
                true
            } shouldBe OwnedRoutePopResult.NOT_CURRENT
            popCalls shouldBe 1
        }

        test("a failed owner pop is distinct from lost ownership") {
            val owner = Any()

            popOwnedRoute(owner, owner, true) { false } shouldBe
                OwnedRoutePopResult.FAILED
        }

        test("a current owner waits for resume before popping") {
            val owner = Any()
            var popCalls = 0

            popOwnedRouteWhenResumed(
                current = { owner },
                owner = owner,
                ownerStates = flowOf(Lifecycle.State.STARTED, Lifecycle.State.RESUMED),
                pop = {
                    popCalls++
                    true
                },
            ) shouldBe OwnedRoutePopResult.POPPED
            popCalls shouldBe 1
        }

        test("ownership is checked again after waiting for resume") {
            val owner = Any()
            val replacement = Any()
            var current: Any = owner
            var popCalls = 0

            popOwnedRouteWhenResumed(
                current = { current },
                owner = owner,
                ownerStates = flow {
                    emit(Lifecycle.State.STARTED)
                    current = replacement
                    emit(Lifecycle.State.RESUMED)
                },
                pop = {
                    popCalls++
                    true
                },
            ) shouldBe OwnedRoutePopResult.NOT_CURRENT
            popCalls shouldBe 0
        }
    }) {
    private data class EqualEntry(val id: Int)
}
