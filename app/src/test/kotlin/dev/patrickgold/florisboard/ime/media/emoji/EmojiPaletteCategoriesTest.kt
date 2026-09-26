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
