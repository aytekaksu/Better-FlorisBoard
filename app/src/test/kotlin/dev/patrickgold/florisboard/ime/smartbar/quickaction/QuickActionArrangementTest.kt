/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private val settings
    get() = QuickAction.InsertKey(TextKeyData.SETTINGS)

private val selectAll
    get() = QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL)

private val symbols
    get() = QuickAction.InsertKey(TextKeyData.VIEW_SYMBOLS)

private fun arrangement(
    sticky: QuickAction? = null,
    dynamic: List<QuickAction> = emptyList(),
    hidden: List<QuickAction> = emptyList(),
) = QuickActionArrangement(
    stickyAction = sticky,
    dynamicActions = dynamic,
    hiddenActions = hidden,
)

class QuickActionArrangementTest :
    FunSpec({
        context("contains behavior") {
            withData(
                Triple(arrangement(), settings, false),
                Triple(arrangement(sticky = settings), settings, true),
                Triple(arrangement(dynamic = listOf(settings)), settings, true),
                Triple(arrangement(hidden = listOf(settings)), settings, true),
            ) { (arrangement, action, expectedContains) ->
                arrangement.contains(action) shouldBe expectedContains
            }
        }

        context("distinct behavior") {
            withData(
                arrangement() to arrangement(),
                arrangement(sticky = settings) to arrangement(sticky = settings),
                arrangement(sticky = settings, dynamic = listOf(settings)) to arrangement(sticky = settings),
                arrangement(sticky = settings, dynamic = listOf(settings, settings)) to arrangement(sticky = settings),
                arrangement(sticky = settings, dynamic = listOf(selectAll), hidden = listOf(symbols)) to
                    arrangement(sticky = settings, dynamic = listOf(selectAll), hidden = listOf(symbols)),
                arrangement(dynamic = listOf(selectAll), hidden = listOf(selectAll, symbols)) to
                    arrangement(dynamic = listOf(selectAll), hidden = listOf(symbols)),
                arrangement(dynamic = listOf(selectAll), hidden = listOf(symbols, selectAll)) to
                    arrangement(dynamic = listOf(selectAll), hidden = listOf(symbols)),
            ) { (beforeDistinct, afterDistinct) ->
                beforeDistinct.distinct() shouldBe afterDistinct
            }
        }

        test("overflow exposes only the unshown dynamic suffix in order") {
            val first = QuickAction.InsertText("first")
            val second = QuickAction.InsertText("second")
            val third = QuickAction.InsertText("third")
            val arrangement = QuickActionArrangement(
                stickyAction = QuickAction.InsertText("sticky"),
                dynamicActions = listOf(first, second, third),
                hiddenActions = listOf(QuickAction.InsertText("hidden")),
            )

            arrangement.copy(dynamicActions = listOf(first)).overflowActions(0) shouldBe listOf(first)
            arrangement.overflowActions(0) shouldBe listOf(first, second, third)
            arrangement.overflowActions(1) shouldBe listOf(second, third)
            arrangement.overflowActions(2) shouldBe listOf(third)
            arrangement.overflowActions(3) shouldBe emptyList<QuickAction>()
            arrangement.overflowActions(Int.MAX_VALUE) shouldBe emptyList<QuickAction>()
            arrangement.copy(dynamicActions = emptyList()).overflowActions(0) shouldBe emptyList<QuickAction>()
        }
    })
