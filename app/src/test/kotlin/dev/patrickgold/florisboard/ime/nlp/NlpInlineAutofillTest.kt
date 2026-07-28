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

package dev.patrickgold.florisboard.ime.nlp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NlpInlineAutofillTest : FunSpec({
    test("slow inflation remains pending until every callback answers") {
        runTest {
            val callbacks = mutableMapOf<Int, (String) -> Unit>()
            val waiting = async {
                awaitInlineSuggestionInflations(listOf(1, 2)) { input, complete ->
                    callbacks[input] = complete
                }
            }
            runCurrent()

            testScheduler.advanceTimeBy(5_000)
            waiting.isCompleted shouldBe false

            callbacks.getValue(2)("second")
            callbacks.getValue(1)("first")
            waiting.await() shouldBe listOf("first", "second")
        }
    }

    test("cancelled inflation ignores a late callback") {
        runTest {
            lateinit var complete: (String) -> Unit
            val waiting = async {
                awaitInlineSuggestionInflations(listOf(Unit)) { _, callback ->
                    complete = callback
                }
            }
            runCurrent()

            waiting.cancelAndJoin()
            complete("late")
            waiting.isCancelled shouldBe true
        }
    }
})
