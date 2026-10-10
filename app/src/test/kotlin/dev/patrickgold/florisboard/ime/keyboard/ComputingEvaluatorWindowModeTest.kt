/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.window.ImeWindowMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ComputingEvaluatorWindowModeTest : FunSpec({
    test("floating-window icon keeps the enabled fallback without an IME") {
        floatingWindowIconResource(null) shouldBe R.drawable.ic_floating_keyboard
        floatingWindowIconResource(ImeWindowMode.FIXED) shouldBe R.drawable.ic_floating_keyboard
        floatingWindowIconResource(ImeWindowMode.FLOATING) shouldBe R.drawable.ic_floating_keyboard_disable
    }
})
