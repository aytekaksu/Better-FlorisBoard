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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class ClipboardMediaUriTest :
    FunSpec({
        val authority = "dev.patrickgold.florisboard.beta.provider.clipboard"

        test("archive references accept only the exact canonical media URI") {
            val image = ArchiveClipboardMediaRef.parse(
                raw = "content://$authority/clips/images/42",
                sourceAuthority = authority,
                expectedType = ItemType.IMAGE,
            )
            val video = ArchiveClipboardMediaRef.parse(
                raw = "content://$authority/clips/videos/73",
                sourceAuthority = authority,
                expectedType = ItemType.VIDEO,
            )

            image?.sourceId shouldBe 42L
            image?.type shouldBe ItemType.IMAGE
            video?.sourceId shouldBe 73L
            video?.type shouldBe ItemType.VIDEO
        }

        test("archive references reject every noncanonical shape") {
            listOf(
                "content://foreign.provider.clipboard/clips/images/42",
                "CONTENT://$authority/clips/images/42",
                "content://$authority/clips/image/42",
                "content://$authority/clips/videos/42",
                "content://$authority/clips/images",
                "content://$authority/clips/images/",
                "content://$authority/clips/images/0",
                "content://$authority/clips/images/00",
                "content://$authority/clips/images/042",
                "content://$authority/clips/images/+42",
                "content://$authority/clips/images/-42",
                "content://$authority/clips/images/42/",
                "content://$authority/clips/images/42/extra",
                "content://$authority/clips/images/%34%32",
                "content://$authority/clips/images/42?query=1",
                "content://$authority/clips/images/42#fragment",
                "content://user@$authority/clips/images/42",
                "content://$authority/clips/images/9223372036854775808",
                "file://$authority/clips/images/42",
                "",
            ).forEach { raw ->
                ArchiveClipboardMediaRef.parse(
                    raw = raw,
                    sourceAuthority = authority,
                    expectedType = ItemType.IMAGE,
                ).shouldBeNull()
            }
            ArchiveClipboardMediaRef.parse(
                raw = "content://$authority/clips/images/42",
                sourceAuthority = authority,
                expectedType = ItemType.TEXT,
            ).shouldBeNull()
        }

        test("canonical parsing rejects overlong media IDs before numeric conversion") {
            ArchiveClipboardMediaRef.parse(
                raw = "content://$authority/clips/images/${"9".repeat(20)}",
                sourceAuthority = authority,
                expectedType = ItemType.IMAGE,
            ).shouldBeNull()
        }

        test("archive reference summaries redact source IDs") {
            val marker = "98437561"
            val ref = ArchiveClipboardMediaRef.parse(
                raw = "content://$authority/clips/images/$marker",
                sourceAuthority = authority,
                expectedType = ItemType.IMAGE,
            )

            ref.toString() shouldNotContain marker
        }

        test("clipboard authority follows the source package exactly") {
            clipboardMediaAuthority("dev.example.keyboard") shouldBe
                "dev.example.keyboard.provider.clipboard"
        }

        test("provider metadata inputs stay bounded") {
            clipboardMediaProjectionIsAllowed(null) shouldBe true
            clipboardMediaProjectionIsAllowed(arrayOf("_id", "_size")) shouldBe true
            clipboardMediaProjectionIsAllowed(
                arrayOf("_id", "_size", "_display_name", "orientation", "_id"),
            ) shouldBe false
            clipboardMediaProjectionIsAllowed(arrayOf("_id", "unknown")) shouldBe false
            clipboardMediaStreamTypeFilterIsAllowed("image/*") shouldBe true
            clipboardMediaStreamTypeFilterIsAllowed("x".repeat(128)) shouldBe false
        }

        test("clipboard timestamps allow only bounded clock skew") {
            val now = 10_000L
            isValidClipboardTimestamp(0L, now) shouldBe true
            isValidClipboardTimestamp(now + MAX_CLIPBOARD_FUTURE_SKEW_MS, now) shouldBe true
            isValidClipboardTimestamp(now + MAX_CLIPBOARD_FUTURE_SKEW_MS + 1L, now) shouldBe false
            isValidClipboardTimestamp(-1L, now) shouldBe false
        }
    })
