/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.setup

import androidx.compose.runtime.saveable.SaverScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SetupStepStateTest : FunSpec({
    test("manual selection is limited to reached steps and resets at automatic step") {
        val state = SetupStepState.new(initial = 1)
        state.select(3)
        state.current shouldBe 1

        state.updateAutomatic(2)
        state.current shouldBe 2
        state.select(1)
        state.current shouldBe 1
        state.updateAutomatic(4)
        state.current shouldBe 1

        state.select(4)
        state.manual shouldBe -1
        state.current shouldBe 4
    }

    test("saver restores automatic and manual progress") {
        val state = SetupStepState.new(initial = 1)
        state.updateAutomatic(4)
        state.select(2)

        val saved = requireNotNull(with(SetupStepState.Saver) {
            with(object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }) { save(state) }
        })
        saved shouldBe listOf(4, 2)

        val restored = requireNotNull(SetupStepState.Saver.restore(saved))
        restored.automatic shouldBe 4
        restored.manual shouldBe 2
        restored.current shouldBe 2
    }
})
