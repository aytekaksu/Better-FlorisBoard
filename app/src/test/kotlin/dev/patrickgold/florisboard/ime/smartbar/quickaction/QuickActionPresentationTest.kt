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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun resources(displayName: Int, tooltip: Int) = QuickActionStringResources(displayName, tooltip)

class QuickActionPresentationTest :
    FunSpec({
        test("every supported key action has a valid name and tooltip") {
            val defaultActions = QuickActionArrangement.Default.run {
                listOfNotNull(stickyAction) + dynamicActions + hiddenActions
            }
            val supportedCodes = buildSet {
                defaultActions.filterIsInstance<QuickAction.InsertKey>().forEach { add(it.data.code) }
                add(KeyCode.TOGGLE_ACTIONS_OVERFLOW)
                add(KeyCode.AUTOCORRECT_PLUGIN_UI)
                add(KeyCode.DRAG_MARKER)
                add(KeyCode.NOOP)
            }

            supportedCodes.size shouldBe 27
            supportedCodes.forEach { code ->
                val result = quickActionStringResources(code, showDragAndDropHelpers = true)
                result.displayName shouldNotBe R.string.general__invalid_fatal
                result.tooltip shouldNotBe R.string.general__invalid_fatal
            }
        }

        test("aliases, debug markers, and fallbacks use the intended resources") {
            quickActionStringResources(KeyCode.FORWARD_DELETE, false) shouldBe resources(
                R.string.quick_action__forward_delete,
                R.string.quick_action__forward_delete__tooltip,
            )
            quickActionStringResources(KeyCode.TOGGLE_AUTOCORRECT, false) shouldBe
                quickActionStringResources(KeyCode.AUTOCORRECT_PLUGIN_UI, false)
            quickActionStringResources(KeyCode.DRAG_MARKER, true) shouldBe resources(
                R.string.quick_action__drag_marker,
                R.string.quick_action__drag_marker__tooltip,
            )
            quickActionStringResources(KeyCode.DRAG_MARKER, false) shouldBe resources(
                R.string.general__empty_string,
                R.string.general__empty_string,
            )
            quickActionStringResources(Int.MAX_VALUE, false) shouldBe resources(
                R.string.general__invalid_fatal,
                R.string.general__invalid_fatal,
            )
        }

        test("inserted text previews handle empty blank and supplementary input") {
            listOf("" to "?", " " to "?", "abc" to "a", "😀rest" to "😀").forEach { (text, expected) ->
                QuickAction.InsertText(text).previewText() shouldBe expected
            }
        }
    })
