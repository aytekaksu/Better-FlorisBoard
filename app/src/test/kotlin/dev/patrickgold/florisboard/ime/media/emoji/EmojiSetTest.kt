/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
