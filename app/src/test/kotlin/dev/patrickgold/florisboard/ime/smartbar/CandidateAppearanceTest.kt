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
