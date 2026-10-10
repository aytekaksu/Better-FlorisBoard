/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.media.emoji.Emoji
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.AutoTextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.MultiTextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyDataPrivacyTest :
    FunSpec({
        test("key data strings expose only their class type and group") {
            val text = TextKeyData(
                type = KeyType.FUNCTION,
                code = 123_456,
                label = "private-text-label",
                groupId = 41,
            )
            val auto = AutoTextKeyData(
                type = KeyType.NUMERIC,
                code = 234_567,
                label = "private-auto-label",
                groupId = 42,
            )
            val multi = MultiTextKeyData(
                type = KeyType.CHARACTER,
                codePoints = intArrayOf(345_678, 456_789),
                label = "private-multi-label",
                groupId = 43,
            )
            val emoji = Emoji(
                value = "private-emoji-value",
                name = "private-emoji-name",
                keywords = listOf("private-emoji-keyword"),
            )

            text.toString() shouldBe "TextKeyData { type=function groupId=41 }"
            auto.toString() shouldBe "AutoTextKeyData { type=numeric groupId=42 }"
            multi.toString() shouldBe "MultiTextKeyData { type=character groupId=43 }"
            emoji.toString() shouldBe "Emoji { type=character groupId=0 }"
        }

        test("text key strings use the content-free computed key representation") {
            val data = TextKeyData(
                type = KeyType.FUNCTION,
                code = 567_890,
                label = "private-computed-label",
                groupId = 44,
            )
            val key = TextKey(data)
            val keyboard = TextKeyboard(
                arrangement = arrayOf(arrayOf(key)),
                mode = KeyboardMode.CHARACTERS,
                extendedPopupMapping = null,
                extendedPopupMappingDefault = null,
            )
            val evaluator = object : ComputingEvaluator by DefaultComputingEvaluator {
                override val keyboard = keyboard
            }

            key.compute(evaluator)

            key.toString() shouldBe "TextKeyData { type=function groupId=44 }"
        }
    })
