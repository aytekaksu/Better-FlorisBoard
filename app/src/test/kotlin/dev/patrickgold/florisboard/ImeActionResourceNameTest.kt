/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
