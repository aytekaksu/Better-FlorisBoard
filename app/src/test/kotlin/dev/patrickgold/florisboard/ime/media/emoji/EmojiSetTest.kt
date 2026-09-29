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

class EmojiSetTest : FunSpec({
    test("mixed-tone handshakes remain available beside the preferred base") {
        val default = emoji("🤝")
        val light = emoji("🤝🏻")
        val dark = emoji("🤝🏿")
        val lightDark = emoji("🫱🏻‍🫲🏿")
        val darkLight = emoji("🫱🏿‍🫲🏻")
        val set = EmojiSet(listOf(default, light, dark, lightDark, darkLight))

        set.base(EmojiSkinTone.LIGHT_SKIN_TONE) shouldBe light
        set.variations(excluding = light) shouldBe
            listOf(default, dark, lightDark, darkLight)
    }

    test("an unavailable preferred tone leaves the fallback base out of the choices") {
        val default = emoji("🤝")
        val light = emoji("🤝🏻")
        val dark = emoji("🤝🏿")
        val set = EmojiSet(listOf(default, light, dark))

        set.base(EmojiSkinTone.MEDIUM_LIGHT_SKIN_TONE) shouldBe default
        set.variations(excluding = default) shouldBe listOf(light, dark)
    }
})

private fun emoji(value: String) = Emoji(value, "", emptyList())
