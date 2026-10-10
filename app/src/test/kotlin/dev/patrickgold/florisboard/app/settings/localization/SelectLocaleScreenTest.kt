/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.localization

import dev.patrickgold.florisboard.lib.FlorisLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class SelectLocaleScreenTest :
    FunSpec({
        val english = FlorisLocale.from("en", "US")
        val turkish = FlorisLocale.from("tr", "TR")

        test("search uses case-insensitive matching without changing locale text") {
            english.displayName(turkish) shouldStartWith "İngilizce"
            english.matchesSearchTerm("ing", turkish) shouldBe true
        }

        test("search matches tags and ignores surrounding whitespace") {
            english.matchesSearchTerm(" EN-us ", turkish) shouldBe true
            english.matchesSearchTerm("en_US", turkish) shouldBe true
            english.matchesSearchTerm("  ", turkish) shouldBe true
            english.matchesSearchTerm("not-a-locale", turkish) shouldBe false
        }
    })
