/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CandidateAppearanceTest : FunSpec({
    test("key-matched candidate appearance preserves the fork default") {
        val prefs by jetprefDataStoreOf(FlorisPreferenceModel::class)

        prefs.suggestion.matchKeyAppearance.get() shouldBe true
    }

    test("disabled key matching restores candidate theme styling") {
        resolveCandidateAppearance(
            matchKeyAppearance = false,
            displayMode = CandidatesDisplayMode.CLASSIC,
        ) shouldBe CandidateAppearance(
            useKeyStyle = false,
            fontSizeScale = 1.0f,
            useKeyColoredClassicSeparator = false,
        )
    }

    test("enabled classic appearance uses key typography and separator") {
        resolveCandidateAppearance(
            matchKeyAppearance = true,
            displayMode = CandidatesDisplayMode.CLASSIC,
        ) shouldBe CandidateAppearance(
            useKeyStyle = true,
            fontSizeScale = 1.125f,
            useKeyColoredClassicSeparator = true,
        )
    }

    test("enabled non-classic appearance uses key typography without key separator") {
        resolveCandidateAppearance(
            matchKeyAppearance = true,
            displayMode = CandidatesDisplayMode.DYNAMIC_SCROLLABLE,
        ) shouldBe CandidateAppearance(
            useKeyStyle = true,
            fontSizeScale = 1.125f,
            useKeyColoredClassicSeparator = false,
        )
    }

    test("classic display keeps the first three candidates") {
        candidatesForDisplay(
            candidates = listOf("one", "two", "three", "four"),
            displayMode = CandidatesDisplayMode.CLASSIC,
        ) shouldBe listOf("one", "two", "three")
    }

    test("dynamic displays keep the complete candidate list") {
        val candidates = listOf("one", "two", "three", "four")

        candidatesForDisplay(candidates, CandidatesDisplayMode.DYNAMIC) shouldBe candidates
        candidatesForDisplay(candidates, CandidatesDisplayMode.DYNAMIC_SCROLLABLE) shouldBe candidates
    }
})
