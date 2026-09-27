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

import android.view.inputmethod.EditorInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ImeActionResourceNameTest : FunSpec({
    test("IME actions keep their Android resource names with flags") {
        val names = mapOf(
            EditorInfo.IME_ACTION_GO to "ime_action_go",
            EditorInfo.IME_ACTION_SEARCH to "ime_action_search",
            EditorInfo.IME_ACTION_SEND to "ime_action_send",
            EditorInfo.IME_ACTION_NEXT to "ime_action_next",
            EditorInfo.IME_ACTION_DONE to "ime_action_done",
            EditorInfo.IME_ACTION_PREVIOUS to "ime_action_previous",
            EditorInfo.IME_ACTION_UNSPECIFIED to "ime_action_default",
        )
        names.forEach { (action, name) ->
            imeActionResourceName(action) shouldBe name
            imeActionResourceName(action or EditorInfo.IME_FLAG_NO_EXTRACT_UI) shouldBe name
        }
        imeActionResourceName(EditorInfo.IME_ACTION_NONE) shouldBe null
    }
})
