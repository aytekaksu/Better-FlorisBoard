/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import android.view.KeyEvent
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NavigationMovementTest : FunSpec({
    test("navigation codes preserve direction, selection edge, and page or line modifier") {
        val expected = mapOf(
            KeyCode.ARROW_LEFT to Triple(KeyEvent.KEYCODE_DPAD_LEFT, true, false),
            KeyCode.ARROW_RIGHT to Triple(KeyEvent.KEYCODE_DPAD_RIGHT, false, false),
            KeyCode.ARROW_UP to Triple(KeyEvent.KEYCODE_DPAD_UP, true, false),
            KeyCode.ARROW_DOWN to Triple(KeyEvent.KEYCODE_DPAD_DOWN, false, false),
            KeyCode.MOVE_START_OF_PAGE to Triple(KeyEvent.KEYCODE_DPAD_UP, true, true),
            KeyCode.MOVE_END_OF_PAGE to Triple(KeyEvent.KEYCODE_DPAD_DOWN, false, true),
            KeyCode.MOVE_START_OF_LINE to Triple(KeyEvent.KEYCODE_DPAD_LEFT, true, true),
            KeyCode.MOVE_END_OF_LINE to Triple(KeyEvent.KEYCODE_DPAD_RIGHT, false, true),
        )

        navigationMovements.keys shouldBe (KeyCode.MOVE_END_OF_LINE..KeyCode.ARROW_LEFT).toSet()
        expected.forEach { (code, triple) ->
            val movement = requireNotNull(navigationMovements[code])
            Triple(movement.dpadKeyCode, movement.movesSelectionStart, movement.alt) shouldBe triple
        }
        navigationMovements[KeyCode.DELETE] shouldBe null
    }
})
