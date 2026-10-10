/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.media.emoji

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EmojiPaletteCategoriesTest : FunSpec({
    test("history controls the visible tab and page order") {
        val categoriesWithoutHistory = listOf(
            EmojiCategory.SMILEYS_EMOTION,
            EmojiCategory.PEOPLE_BODY,
            EmojiCategory.ANIMALS_NATURE,
            EmojiCategory.FOOD_DRINK,
            EmojiCategory.TRAVEL_PLACES,
            EmojiCategory.ACTIVITIES,
            EmojiCategory.OBJECTS,
            EmojiCategory.SYMBOLS,
            EmojiCategory.FLAGS,
        )
        val categoriesWithHistory = listOf(EmojiCategory.RECENTLY_USED) + categoriesWithoutHistory

        visibleEmojiCategories(true) shouldBe categoriesWithHistory
        visibleEmojiCategories(false) shouldBe categoriesWithoutHistory
        for (historyEnabled in listOf(false, true)) {
            val visible = visibleEmojiCategories(historyEnabled)
            visible.size shouldBe if (historyEnabled) 10 else 9
            for ((pageIndex, category) in visible.withIndex()) {
                visible.indexOf(category) shouldBe pageIndex
            }
        }
    }
})
