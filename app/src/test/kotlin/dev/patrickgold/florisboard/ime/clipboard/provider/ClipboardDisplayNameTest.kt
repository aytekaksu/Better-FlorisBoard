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

package dev.patrickgold.florisboard.ime.clipboard.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipboardDisplayNameTest :
    FunSpec({
        test("replaces invisible and direction-changing format characters") {
            normalizeClipboardMediaDisplayName("invoice\u202Egnp.exe") shouldBe
                "invoice_gnp.exe"
            normalizeClipboardMediaDisplayName("family\u200Bphoto\u200D.png") shouldBe
                "family_photo_.png"
        }

        test("replaces line separators and unpaired surrogates") {
            normalizeClipboardMediaDisplayName("line\u2028break\u2029name.png") shouldBe
                "line_break_name.png"
            normalizeClipboardMediaDisplayName("bad\uD800name\uDC00.png") shouldBe
                "bad_name_.png"
        }

        test("preserves normal Unicode and valid supplementary characters") {
            normalizeClipboardMediaDisplayName(" Résumé 東京 \uD83D\uDDBC.png ") shouldBe
                "Résumé 東京 \uD83D\uDDBC.png"
        }

        test("bounds long names without splitting characters or losing the extension") {
            val maximumLength = ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH
            normalizeClipboardMediaDisplayName("a".repeat(maximumLength) + ".jpeg") shouldBe
                "a".repeat(maximumLength - ".jpeg".length) + ".jpeg"
            normalizeClipboardMediaDisplayName(
                "a".repeat(maximumLength - 1) + "\uD83D\uDDBC.png",
            ) shouldBe "a".repeat(maximumLength - ".png".length) + ".png"
            normalizeClipboardMediaDisplayName("a".repeat(maximumLength + 1)) shouldBe
                "a".repeat(maximumLength)
        }

        test("keeps the existing empty and control-character behavior") {
            normalizeClipboardMediaDisplayName(" \u0000vector.svg ") shouldBe "_vector.svg"
            normalizeClipboardMediaDisplayName("   ") shouldBe null
            normalizeClipboardMediaDisplayName(null) shouldBe null
        }
    })
