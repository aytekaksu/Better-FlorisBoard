/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.media.emoji

@JvmInline
value class EmojiSet(val emojis: List<Emoji>) {
    init {
        require(emojis.isNotEmpty()) { "Cannot create an EmojiSet with no emojis specified." }
    }

    fun base(withSkinTone: EmojiSkinTone = EmojiSkinTone.DEFAULT): Emoji {
        if (emojis.size == 1) return emojis[0] // Fast compute
        for (emoji in emojis) {
            if (emoji.skinTone == withSkinTone) {
                return emoji
            }
        }
        return emojis[0] // Fallback
    }

    fun variations(excluding: Emoji): List<Emoji> {
        if (emojis.size == 1) return emptyList() // Fast compute
        return emojis.filterNot { it == excluding }
    }
}
