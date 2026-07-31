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

package dev.patrickgold.florisboard.app.settings.advanced

import dev.patrickgold.florisboard.ime.clipboard.provider.ArchiveClipboardMediaRef
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.MAX_ARCHIVE_MEDIA_MIME_CANDIDATES
import dev.patrickgold.florisboard.ime.clipboard.provider.MAX_ARCHIVE_MEDIA_MIME_CANDIDATE_LENGTH
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class ClipboardRestorePayloadTest :
    FunSpec({
        val testRoot = Files.createTempDirectory("clipboard-restore-payload")

        afterSpec {
            testRoot.toFile().deleteRecursively()
        }

        test("prepares legacy defaults and shares same-type media references") {
            val root = testRoot.workspace("valid-shared")
            root.writeIndex(
                ItemType.TEXT,
                """
                    [
                      {
                        "id": 91,
                        "type": "TEXT",
                        "text": "private text",
                        "uri": null,
                        "creationTimestampMs": 10,
                        "isPinned": true,
                        "mimeTypes": ["text/plain"]
                      }
                    ]
                """.trimIndent(),
            )
            root.writeIndex(
                ItemType.IMAGE,
                """
                    [
                      {
                        "id": 92,
                        "type": "IMAGE",
                        "text": null,
                        "uri": "$IMAGE_42",
                        "creationTimestampMs": 11,
                        "isPinned": false,
                        "mimeTypes": ["image/png"],
                        "isSensitive": true,
                        "isRemoteDevice": true
                      },
                      {
                        "id": 93,
                        "type": "IMAGE",
                        "text": "optional caption",
                        "uri": "$IMAGE_42",
                        "creationTimestampMs": 12,
                        "isPinned": false,
                        "mimeTypes": ["image/webp"]
                      }
                    ]
                """.trimIndent(),
            )
            root.writeMedia(42, byteArrayOf(1, 2, 3))

            val result = ClipboardRestorePayload.prepare(
                stagedRoot = root,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.TEXT, ItemType.IMAGE),
                limits = ClipboardRestorePayloadLimits.Default.copy(maxTotalMediaBytes = 3),
            ) as ClipboardRestorePayloadResult.Valid

            result.payload.selectedTypes shouldBe setOf(ItemType.TEXT, ItemType.IMAGE)
            result.payload.items.size shouldBe 3
            result.payload.media.size shouldBe 1
            result.payload.media.single().byteCount shouldBe 3L
            result.payload.media.single().mimeTypes shouldBe listOf("image/png", "image/webp")
            result.payload.media.single().displayName shouldBe null
            result.payload.items(ItemType.TEXT).single().let { text ->
                text.isSensitive shouldBe false
                text.isRemoteDevice shouldBe false
                text.mediaRef shouldBe null
            }
            result.payload.items(ItemType.IMAGE).let { images ->
                images.size shouldBe 2
                images[0].mediaRef shouldBe images[1].mediaRef
                images[0].mediaRef?.sourceId shouldBe 42L
                images[0].isSensitive shouldBe true
                images[0].isRemoteDevice shouldBe true
            }
        }

        test("validates only selected indexes") {
            val root = testRoot.workspace("selected-only")
            root.writeIndex(ItemType.TEXT, textItemJson(text = "selected"))
            root.writeIndex(ItemType.IMAGE, "{ definitely not JSON")
            root.writeIndex(
                ItemType.VIDEO,
                videoItemJson(uri = "content://wrong.authority/clips/videos/42"),
            )

            val result = ClipboardRestorePayload.prepare(
                stagedRoot = root,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.TEXT),
            ) as ClipboardRestorePayloadResult.Valid

            result.payload.items.size shouldBe 1
            result.payload.media shouldBe emptyList()
        }

        test("bounded media reference inspection rejects unsafe selected indexes") {
            val invalidUtf8Root = testRoot.workspace("reference-invalid-utf8")
            val invalidUtf8Path = invalidUtf8Root.indexPath(ItemType.IMAGE)
            Files.createDirectories(invalidUtf8Path.parent)
            Files.write(invalidUtf8Path, byteArrayOf(0x5b, 0xc3.toByte(), 0x28, 0x5d))
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = invalidUtf8Root,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.INVALID_UTF8,
            )

            val oversizedStringRoot = testRoot.workspace("reference-oversized-string")
            oversizedStringRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(uri = IMAGE_42, text = "a".repeat(1_000_001)),
            )
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = oversizedStringRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )

            val malformedSecondRoot = testRoot.workspace("reference-malformed-second")
            malformedSecondRoot.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42))
            malformedSecondRoot.writeIndex(ItemType.VIDEO, "[{ definitely not JSON")
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = malformedSecondRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE, ItemType.VIDEO),
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.INVALID_JSON,
            )
        }

        test("media reference inspection rejects invalid sources authorities and types") {
            val validRoot = testRoot.workspace("reference-invalid-source")
            validRoot.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42))
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = validRoot,
                sourcePackageName = "keyboard",
                selectedTypes = setOf(ItemType.IMAGE),
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.INVALID_SOURCE,
            )

            val wrongAuthorityRoot = testRoot.workspace("reference-wrong-authority")
            wrongAuthorityRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson("content://wrong.provider.clipboard/clips/images/42"),
            )
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = wrongAuthorityRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.INVALID_MEDIA_REFERENCE,
            )

            val wrongTypeRoot = testRoot.workspace("reference-wrong-type")
            wrongTypeRoot.writeIndex(ItemType.IMAGE, videoItemJson(VIDEO_42))
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = wrongTypeRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.INVALID_ITEM,
            )
        }

        test("media reference inspection counts items across selected indexes") {
            val root = testRoot.workspace("reference-aggregate-item-limit")
            root.writeIndex(
                ItemType.IMAGE,
                mediaItemsJson(ItemType.IMAGE, IMAGE_42, count = 5_000),
            )
            root.writeIndex(
                ItemType.VIDEO,
                mediaItemsJson(
                    ItemType.VIDEO,
                    "content://$SOURCE_AUTHORITY/clips/videos/43",
                    count = 5_001,
                ),
            )

            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = root,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE, ItemType.VIDEO),
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )
        }

        test("media reference inspection shares semantic aggregate limits with prepare") {
            val textRoot = testRoot.workspace("reference-aggregate-text-limit")
            textRoot.writeIndex(
                ItemType.IMAGE,
                mediaItemsJson(ItemType.IMAGE, IMAGE_42, count = 2, text = "four"),
            )
            textRoot.writeMedia(42, byteArrayOf(1))
            val textLimits = ClipboardRestorePayloadLimits.Default.copy(
                maxTextChars = 4,
                maxTotalTextChars = 7,
            )
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = textRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
                limits = textLimits,
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )
            ClipboardRestorePayload.prepare(
                stagedRoot = textRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
                limits = textLimits,
            ) shouldBe ClipboardRestorePayloadResult.Invalid(
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )

            val mimeRoot = testRoot.workspace("reference-aggregate-mime-limit")
            mimeRoot.writeIndex(
                ItemType.IMAGE,
                mediaItemsJson(ItemType.IMAGE, IMAGE_42, count = 2),
            )
            mimeRoot.writeMedia(42, byteArrayOf(1))
            val mimeCountLimits =
                ClipboardRestorePayloadLimits.Default.copy(maxTotalMimeTypes = 1)
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = mimeRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
                limits = mimeCountLimits,
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )
            ClipboardRestorePayload.prepare(
                stagedRoot = mimeRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
                limits = mimeCountLimits,
            ) shouldBe ClipboardRestorePayloadResult.Invalid(
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )

            val mimeCharLimits =
                ClipboardRestorePayloadLimits.Default.copy(maxTotalMimeTypeChars = 17)
            ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = mimeRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
                limits = mimeCharLimits,
            ) shouldBe ClipboardMediaReferenceInspectionResult.Invalid(
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )
            ClipboardRestorePayload.prepare(
                stagedRoot = mimeRoot,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE),
                limits = mimeCharLimits,
            ) shouldBe ClipboardRestorePayloadResult.Invalid(
                ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
            )
        }

        test("media reference inspection preserves canonical item types") {
            val root = testRoot.workspace("typed-reference-inspection")
            root.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42))
            root.writeIndex(
                ItemType.VIDEO,
                videoItemJson("content://$SOURCE_AUTHORITY/clips/videos/43"),
            )

            val result = ClipboardRestorePayload.inspectMediaReferences(
                stagedRoot = root,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.IMAGE, ItemType.VIDEO),
            ) as ClipboardMediaReferenceInspectionResult.Valid

            result.references.map { it.type to it.sourceId }.toSet() shouldBe setOf(
                ItemType.IMAGE to 42L,
                ItemType.VIDEO to 43L,
            )
        }

        test("rejects malformed UTF-8 before JSON decoding") {
            val root = testRoot.workspace("invalid-utf8")
            val path = root.indexPath(ItemType.TEXT)
            Files.createDirectories(path.parent)
            Files.write(path, byteArrayOf(0x5b, 0xc3.toByte(), 0x28, 0x5d))

            root.prepare(ItemType.TEXT) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_UTF8)
        }

        test("cancellation interrupts a multi-buffer UTF-8 scan") {
            val root = testRoot.workspace("cancelled-utf8-scan")
            root.writeIndex(
                ItemType.TEXT,
                textItemJson(text = "a".repeat(20_000)),
            )
            val activeChecks = AtomicInteger()

            shouldThrow<CancellationException> {
                ClipboardRestorePayload.prepare(
                    stagedRoot = root,
                    sourcePackageName = SOURCE_PACKAGE,
                    selectedTypes = setOf(ItemType.TEXT),
                    checkActive = {
                        if (activeChecks.incrementAndGet() == 4) {
                            throw CancellationException("synthetic cancellation")
                        }
                    },
                )
            }

            activeChecks.get() shouldBe 4
        }

        test("cancellation interrupts bounded media reference inspection") {
            val root = testRoot.workspace("cancelled-reference-inspection")
            root.writeIndex(
                ItemType.IMAGE,
                imageItemJson(uri = IMAGE_42, text = "a".repeat(20_000)),
            )
            val activeChecks = AtomicInteger()

            shouldThrow<CancellationException> {
                ClipboardRestorePayload.inspectMediaReferences(
                    stagedRoot = root,
                    sourcePackageName = SOURCE_PACKAGE,
                    selectedTypes = setOf(ItemType.IMAGE),
                    checkActive = {
                        if (activeChecks.incrementAndGet() == 4) {
                            throw CancellationException("synthetic cancellation")
                        }
                    },
                )
            }

            activeChecks.get() shouldBe 4
        }

        test("cancellation interrupts incremental item decoding") {
            val root = testRoot.workspace("cancelled-sequence-decode")
            root.writeIndex(
                ItemType.TEXT,
                (1..3).joinToString(prefix = "[", postfix = "]") { id ->
                    """
                        {
                          "id": $id,
                          "type": "TEXT",
                          "text": "value",
                          "uri": null,
                          "creationTimestampMs": $id,
                          "isPinned": false,
                          "mimeTypes": ["text/plain"]
                        }
                    """.trimIndent()
                },
            )
            val activeChecks = AtomicInteger()

            shouldThrow<CancellationException> {
                ClipboardRestorePayload.prepare(
                    stagedRoot = root,
                    sourcePackageName = SOURCE_PACKAGE,
                    selectedTypes = setOf(ItemType.TEXT),
                    checkActive = {
                        if (activeChecks.incrementAndGet() == 6) {
                            throw CancellationException("synthetic cancellation")
                        }
                    },
                )
            }

            activeChecks.get() shouldBe 6
        }

        test("uses strict JSON while retaining legacy optional defaults") {
            val malformedRoot = testRoot.workspace("malformed-json")
            malformedRoot.writeIndex(ItemType.TEXT, "[")
            malformedRoot.prepare(ItemType.TEXT) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_JSON)

            val unknownRoot = testRoot.workspace("unknown-json-field")
            unknownRoot.writeIndex(
                ItemType.TEXT,
                textItemJson(text = "value").replace("\"mimeTypes\"", "\"unknown\":1,\"mimeTypes\""),
            )
            unknownRoot.prepare(ItemType.TEXT) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_JSON)

            val legacyRoot = testRoot.workspace("legacy-defaults")
            legacyRoot.writeIndex(ItemType.TEXT, textItemJson(text = "value"))
            val valid = legacyRoot.prepare(ItemType.TEXT) as ClipboardRestorePayloadResult.Valid
            valid.payload.items.single().isSensitive shouldBe false
            valid.payload.items.single().isRemoteDevice shouldBe false
        }

        test("normalizes bounded media display names and preserves legacy absence") {
            val namedRoot = testRoot.workspace("display-name")
            namedRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(
                    uri = IMAGE_42,
                    displayNameJson = "\" \\u0000vector.svg \"",
                ),
            )
            namedRoot.writeMedia(42, byteArrayOf(1))

            val named = namedRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid
            named.payload.media.single().displayName shouldBe "_vector.svg"

            val blankRoot = testRoot.workspace("blank-display-name")
            blankRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(
                    uri = IMAGE_42,
                    displayNameJson = "\"   \"",
                ),
            )
            blankRoot.writeMedia(42, byteArrayOf(1))
            (blankRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid)
                .payload.media.single().displayName shouldBe null

            val legacyRoot = testRoot.workspace("legacy-display-name")
            legacyRoot.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42))
            legacyRoot.writeMedia(42, byteArrayOf(1))
            (legacyRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid)
                .payload.media.single().displayName shouldBe null
        }

        test("bounds display names before decoding and forbids them on text items") {
            val oversizedRoot = testRoot.workspace("oversized-display-name")
            oversizedRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(
                    uri = IMAGE_42,
                    displayNameJson =
                        "\"${"a".repeat(ClipboardFileStorage.MAX_DISPLAY_NAME_LENGTH + 1)}\"",
                ),
            )
            oversizedRoot.writeMedia(42, byteArrayOf(1))
            oversizedRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)

            val textRoot = testRoot.workspace("text-display-name")
            textRoot.writeIndex(
                ItemType.TEXT,
                textItemJson(
                    text = "value",
                    displayNameJson = "\"notes.txt\"",
                ),
            )
            textRoot.prepare(ItemType.TEXT) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)
        }

        test("uses one consistent display name for shared media") {
            val compatibleRoot = testRoot.workspace("shared-display-name")
            compatibleRoot.writeIndex(
                ItemType.IMAGE,
                """
                    [
                      {
                        "id": 1,
                        "type": "IMAGE",
                        "text": null,
                        "uri": "$IMAGE_42",
                        "creationTimestampMs": 1,
                        "isPinned": false,
                        "mimeTypes": ["image/png"]
                      },
                      {
                        "id": 2,
                        "type": "IMAGE",
                        "text": null,
                        "uri": "$IMAGE_42",
                        "creationTimestampMs": 2,
                        "isPinned": false,
                        "mimeTypes": ["image/png"],
                        "displayName": "gallery.png"
                      }
                    ]
                """.trimIndent(),
            )
            compatibleRoot.writeMedia(42, byteArrayOf(1))
            (compatibleRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid)
                .payload.media.single().displayName shouldBe "gallery.png"

            val conflictingRoot = testRoot.workspace("conflicting-display-name")
            conflictingRoot.writeIndex(
                ItemType.IMAGE,
                """
                    [
                      {
                        "id": 1,
                        "type": "IMAGE",
                        "text": null,
                        "uri": "$IMAGE_42",
                        "creationTimestampMs": 1,
                        "isPinned": false,
                        "mimeTypes": ["image/png"],
                        "displayName": "first.png"
                      },
                      {
                        "id": 2,
                        "type": "IMAGE",
                        "text": null,
                        "uri": "$IMAGE_42",
                        "creationTimestampMs": 2,
                        "isPinned": false,
                        "mimeTypes": ["image/png"],
                        "displayName": "second.png"
                      }
                    ]
                """.trimIndent(),
            )
            conflictingRoot.writeMedia(42, byteArrayOf(1))
            conflictingRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(
                    ClipboardRestorePayloadFailure.CONFLICTING_MEDIA_REFERENCE,
                )
        }

        test("rejects index type and item-shape mismatches") {
            val cases = listOf(
                "wrong-index-type" to imageItemJson(IMAGE_42),
                "text-with-uri" to textItemJson(text = "value", uri = IMAGE_42),
                "text-without-text" to textItemJson(text = null),
                "text-wrong-mime" to textItemJson(text = "value", mimeTypes = """["text/html"]"""),
            )
            cases.forEach { (name, json) ->
                val root = testRoot.workspace(name)
                root.writeIndex(ItemType.TEXT, json)
                root.prepare(ItemType.TEXT) shouldBe
                    ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)
            }
        }

        test("clamps archive timestamps to the captured local time") {
            val root = testRoot.workspace("timestamp-clamping")
            root.writeIndex(
                ItemType.TEXT,
                """
                    [
                      {
                        "id": 1,
                        "type": "TEXT",
                        "text": "past clock",
                        "uri": null,
                        "creationTimestampMs": -1,
                        "isPinned": false,
                        "mimeTypes": ["text/plain"]
                      },
                      {
                        "id": 2,
                        "type": "TEXT",
                        "text": "future clock",
                        "uri": null,
                        "creationTimestampMs": ${Long.MAX_VALUE},
                        "isPinned": false,
                        "mimeTypes": ["text/plain"]
                      },
                      {
                        "id": 3,
                        "type": "TEXT",
                        "text": "local clock",
                        "uri": null,
                        "creationTimestampMs": 900,
                        "isPinned": false,
                        "mimeTypes": ["text/plain"]
                      }
                    ]
                """.trimIndent(),
            )

            val result = ClipboardRestorePayload.prepare(
                stagedRoot = root,
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = setOf(ItemType.TEXT),
                nowMs = 1_000,
            ) as ClipboardRestorePayloadResult.Valid

            result.payload.items.map(PreparedClipboardItem::creationTimestampMs) shouldBe
                listOf(0L, 1_000L, 900L)
            result.payload.items.maxOf(PreparedClipboardItem::creationTimestampMs) shouldBe 1_000L
        }

        test("rejects invalid media references and MIME fields") {
            val badReferences = listOf(
                "content://foreign.provider.clipboard/clips/images/42",
                "content://$SOURCE_AUTHORITY/clips/videos/42",
                "content://$SOURCE_AUTHORITY/clips/images/042",
                "content://$SOURCE_AUTHORITY/clips/images/42?query=1",
                "content://$SOURCE_AUTHORITY/clips/images/%34%32",
            )
            badReferences.forEachIndexed { index, raw ->
                val root = testRoot.workspace("bad-reference-$index")
                root.writeIndex(ItemType.IMAGE, imageItemJson(raw))
                root.prepare(ItemType.IMAGE) shouldBe ClipboardRestorePayloadResult.Invalid(
                    ClipboardRestorePayloadFailure.INVALID_MEDIA_REFERENCE,
                )
            }

            listOf(
                """[]""",
                """["video/mp4"]""",
                """["image/png","video/mp4"]""",
                """["image/*","video/*"]""",
                """["*/*","image/png"]""",
                """["application/*","image/png"]""",
                """["image/png;private=value"]""",
                """["image"]""",
            ).forEachIndexed { index, mimeTypes ->
                val root = testRoot.workspace("bad-media-mime-$index")
                root.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42, mimeTypes))
                root.prepare(ItemType.IMAGE) shouldBe
                    ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)
            }
        }

        test("canonicalizes safe MIME wildcards from legacy archives") {
            val cases = listOf(
                Triple(ItemType.IMAGE, """["image/*"]""", listOf("image/unknown")),
                Triple(
                    ItemType.IMAGE,
                    """["IMAGE/JPEG","image/*","image/jpeg"]""",
                    listOf("image/jpeg"),
                ),
                Triple(ItemType.VIDEO, """["video/*"]""", listOf("video/unknown")),
            )
            cases.forEachIndexed { index, (type, mimeTypes, expected) ->
                val root = testRoot.workspace("legacy-wildcard-$index")
                val uri = if (type == ItemType.IMAGE) IMAGE_42 else VIDEO_42
                root.writeIndex(type, mediaItemJson(type, uri, mimeTypes))
                root.writeMedia(42, byteArrayOf(1))

                val result = root.prepare(type) as ClipboardRestorePayloadResult.Valid

                result.payload.items.single().mimeTypes shouldBe expected
                result.payload.media.single().mimeTypes shouldBe expected
            }
        }

        test("preserves bounded mixed media MIME metadata") {
            val root = testRoot.workspace("mixed-media-mime")
            root.writeIndex(
                ItemType.IMAGE,
                imageItemJson(
                    uri = IMAGE_42,
                    mimeTypes = """["image/png","application/octet-stream"]""",
                ),
            )
            root.writeMedia(42, byteArrayOf(1))

            val result = root.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid

            result.payload.items.single().mimeTypes shouldBe
                listOf("image/png", "application/octet-stream")
            result.payload.media.single().mimeTypes shouldBe
                listOf("image/png", "application/octet-stream")
        }

        test("reduces legacy MIME metadata to installer boundaries") {
            val sixteenMimeTypes = (0 until 16)
                .joinToString(prefix = "[", postfix = "]") { index -> "\"image/type$index\"" }
            val validCountRoot = testRoot.workspace("mime-count-16")
            validCountRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(IMAGE_42, sixteenMimeTypes),
            )
            validCountRoot.writeMedia(42, byteArrayOf(1))
            (validCountRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid)
                .payload.media.single().mimeTypes.size shouldBe 16

            val seventeenMimeTypes = (0 until 17)
                .joinToString(prefix = "[", postfix = "]") { index -> "\"image/type$index\"" }
            val reducedCountRoot = testRoot.workspace("mime-count-17")
            reducedCountRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(IMAGE_42, seventeenMimeTypes),
            )
            reducedCountRoot.writeMedia(42, byteArrayOf(1))
            (reducedCountRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid)
                .payload.media.single().mimeTypes shouldBe
                (0 until 16).map { index -> "image/type$index" }

            val familyAfterBoundary = buildList {
                repeat(16) { index -> add("\"application/x-$index\"") }
                add("\"image/png\"")
            }.joinToString(prefix = "[", postfix = "]")
            val lateFamilyRoot = testRoot.workspace("mime-family-after-boundary")
            lateFamilyRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(IMAGE_42, familyAfterBoundary),
            )
            lateFamilyRoot.writeMedia(42, byteArrayOf(1))
            val lateFamily = (
                lateFamilyRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid
            ).payload.media.single().mimeTypes
            lateFamily.size shouldBe 16
            lateFamily.contains("image/png") shouldBe true

            val mixedFamilyAfterBoundary = buildList {
                repeat(16) { index -> add("\"image/type$index\"") }
                add("\"video/mp4\"")
            }.joinToString(prefix = "[", postfix = "]")
            val mixedFamilyRoot = testRoot.workspace("mime-mixed-family-after-boundary")
            mixedFamilyRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(IMAGE_42, mixedFamilyAfterBoundary),
            )
            mixedFamilyRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)

            val mimeAtLimit = "image/" + "a".repeat(121)
            val validLengthRoot = testRoot.workspace("mime-length-127")
            validLengthRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(IMAGE_42, """["$mimeAtLimit"]"""),
            )
            validLengthRoot.writeMedia(42, byteArrayOf(1))
            (validLengthRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid)
                .payload.media.single().mimeTypes shouldBe listOf(mimeAtLimit)

            val mimeOverLimit = "image/" + "a".repeat(122)
            val invalidLengthRoot = testRoot.workspace("mime-length-128")
            invalidLengthRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(IMAGE_42, """["$mimeOverLimit"]"""),
            )
            invalidLengthRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)

            val overlongAncillary = "application/" + "a".repeat(200)
            val ancillaryRoot = testRoot.workspace("mime-overlong-ancillary")
            ancillaryRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(
                    IMAGE_42,
                    """["$overlongAncillary","image/png"]""",
                ),
            )
            ancillaryRoot.writeMedia(42, byteArrayOf(1))
            (ancillaryRoot.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid)
                .payload.media.single().mimeTypes shouldBe listOf("image/png")
        }

        test("bounds absurd legacy MIME metadata before validation") {
            val tooManyMimeTypes = List(MAX_ARCHIVE_MEDIA_MIME_CANDIDATES + 1) { "\"image/png\"" }
                .joinToString(prefix = "[", postfix = "]")
            val tooManyRoot = testRoot.workspace("mime-absurd-count")
            tooManyRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(IMAGE_42, tooManyMimeTypes),
            )
            tooManyRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)

            val tooLongMimeType = "image/" +
                "a".repeat(MAX_ARCHIVE_MEDIA_MIME_CANDIDATE_LENGTH)
            val tooLongRoot = testRoot.workspace("mime-absurd-length")
            tooLongRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson(IMAGE_42, """["$tooLongMimeType"]"""),
            )
            tooLongRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)
        }

        test("rejects an oversized MIME union for shared media before construction") {
            val firstMimeTypes = (0 until 9)
                .joinToString(prefix = "[", postfix = "]") { index -> "\"image/a$index\"" }
            val secondMimeTypes = (8 until 17)
                .joinToString(prefix = "[", postfix = "]") { index -> "\"image/a$index\"" }
            val root = testRoot.workspace("shared-mime-union")
            root.writeIndex(
                ItemType.IMAGE,
                """
                    [
                      {
                        "id": 1,
                        "type": "IMAGE",
                        "text": null,
                        "uri": "$IMAGE_42",
                        "creationTimestampMs": 1,
                        "isPinned": false,
                        "mimeTypes": $firstMimeTypes
                      },
                      {
                        "id": 2,
                        "type": "IMAGE",
                        "text": null,
                        "uri": "$IMAGE_42",
                        "creationTimestampMs": 2,
                        "isPinned": false,
                        "mimeTypes": $secondMimeTypes
                      }
                    ]
                """.trimIndent(),
            )
            root.writeMedia(42, byteArrayOf(1))

            root.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(
                    ClipboardRestorePayloadFailure.LIMIT_EXCEEDED,
                )
        }

        test("requires a no-follow regular staged media file") {
            val missingRoot = testRoot.workspace("missing-media")
            missingRoot.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42))
            missingRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.MEDIA_UNAVAILABLE)

            val emptyRoot = testRoot.workspace("empty-media")
            emptyRoot.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42))
            emptyRoot.writeMedia(42, byteArrayOf())
            emptyRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.MEDIA_UNAVAILABLE)

            val symlinkRoot = testRoot.workspace("linked-media")
            symlinkRoot.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42))
            val outside = testRoot.resolve("private-outside-media").also {
                Files.write(it, byteArrayOf(1))
            }
            val linkedMedia = symlinkRoot.mediaPath(42)
            Files.createDirectories(linkedMedia.parent)
            Files.createSymbolicLink(linkedMedia, outside)
            symlinkRoot.prepare(ItemType.IMAGE) shouldBe
                ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.MEDIA_UNAVAILABLE)
        }

        test("rejects a source ID shared across image and video types") {
            val root = testRoot.workspace("cross-type")
            root.writeIndex(ItemType.IMAGE, imageItemJson(IMAGE_42))
            root.writeIndex(ItemType.VIDEO, videoItemJson(VIDEO_42))
            root.writeMedia(42, byteArrayOf(1))

            root.prepare(ItemType.IMAGE, ItemType.VIDEO) shouldBe ClipboardRestorePayloadResult.Invalid(
                ClipboardRestorePayloadFailure.CONFLICTING_MEDIA_REFERENCE,
            )
        }

        test("enforces index item text MIME URI and media budgets") {
            fun limited(
                name: String,
                type: ItemType,
                json: String,
                limits: ClipboardRestorePayloadLimits,
                withMedia: Boolean = false,
            ): ClipboardRestorePayloadResult {
                val root = testRoot.workspace(name)
                root.writeIndex(type, json)
                if (withMedia) root.writeMedia(42, byteArrayOf(1, 2))
                return ClipboardRestorePayload.prepare(
                    stagedRoot = root,
                    sourcePackageName = SOURCE_PACKAGE,
                    selectedTypes = setOf(type),
                    limits = limits,
                )
            }

            limited(
                name = "index-budget",
                type = ItemType.TEXT,
                json = textItemJson(text = "value"),
                limits = ClipboardRestorePayloadLimits.Default.copy(maxIndexBytes = 1),
            ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INDEX_TOO_LARGE)

            limited(
                name = "item-budget",
                type = ItemType.TEXT,
                json = textItemJson(text = "value"),
                limits = ClipboardRestorePayloadLimits.Default.copy(maxItems = 0),
            ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)

            limited(
                name = "text-budget",
                type = ItemType.TEXT,
                json = textItemJson(text = "value"),
                limits = ClipboardRestorePayloadLimits.Default.copy(maxTextChars = 4),
            ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)

            limited(
                name = "mime-count-budget",
                type = ItemType.IMAGE,
                json = imageItemJson(IMAGE_42, """["image/png","image/webp"]"""),
                limits = ClipboardRestorePayloadLimits.Default.copy(maxMimeTypesPerItem = 1),
                withMedia = true,
            ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)

            limited(
                name = "mime-length-budget",
                type = ItemType.IMAGE,
                json = imageItemJson(IMAGE_42),
                limits = ClipboardRestorePayloadLimits.Default.copy(maxMimeTypeChars = 4),
                withMedia = true,
            ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)

            limited(
                name = "uri-budget",
                type = ItemType.IMAGE,
                json = imageItemJson(IMAGE_42),
                limits = ClipboardRestorePayloadLimits.Default.copy(maxUriChars = 8),
                withMedia = true,
            ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_ITEM)

            limited(
                name = "media-budget",
                type = ItemType.IMAGE,
                json = imageItemJson(IMAGE_42),
                limits = ClipboardRestorePayloadLimits.Default.copy(maxMediaBytes = 1),
                withMedia = true,
            ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.LIMIT_EXCEEDED)
        }

        test("rejects empty selections and unsafe source package names") {
            ClipboardRestorePayload.prepare(
                stagedRoot = testRoot.workspace("empty-selection"),
                sourcePackageName = SOURCE_PACKAGE,
                selectedTypes = emptySet(),
            ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.EMPTY_SELECTION)

            listOf(
                "",
                ".",
                "keyboard",
                ".dev.example",
                "dev..example",
                "dev.example.",
                "dev.example/keyboard",
                "dev.example\nkeyboard",
            ).forEachIndexed { index, source ->
                ClipboardRestorePayload.prepare(
                    stagedRoot = testRoot.workspace("invalid-source-$index"),
                    sourcePackageName = source,
                    selectedTypes = setOf(ItemType.TEXT),
                ) shouldBe ClipboardRestorePayloadResult.Invalid(ClipboardRestorePayloadFailure.INVALID_SOURCE)
            }
        }

        test("rejects prepared values which bypass the parser contract") {
            val imageRef = ArchiveClipboardMediaRef.parse(
                IMAGE_42,
                SOURCE_AUTHORITY,
                ItemType.IMAGE,
            )!!

            shouldThrow<IllegalArgumentException> {
                PreparedClipboardItem(
                    type = ItemType.TEXT,
                    text = null,
                    creationTimestampMs = 1,
                    isPinned = false,
                    mimeTypes = listOf("text/plain"),
                    isSensitive = false,
                    isRemoteDevice = false,
                    mediaRef = null,
                )
            }
            shouldThrow<IllegalArgumentException> {
                PreparedClipboardItem(
                    type = ItemType.IMAGE,
                    text = null,
                    creationTimestampMs = 1,
                    isPinned = false,
                    mimeTypes = listOf("image/png"),
                    isSensitive = false,
                    isRemoteDevice = false,
                    mediaRef = null,
                )
            }
            shouldThrow<IllegalArgumentException> {
                PreparedClipboardMedia(
                    ref = imageRef,
                    stagedFile = testRoot.resolve("unused"),
                    byteCount = 0,
                    mimeTypes = listOf("image/png"),
                )
            }
            shouldThrow<IllegalArgumentException> {
                PreparedClipboardMedia(
                    ref = imageRef,
                    stagedFile = testRoot.resolve("unused"),
                    byteCount = 1,
                    mimeTypes = listOf("image/*"),
                )
            }

            val oversizedMedia = (1L..6L).map { sourceId ->
                val ref = ArchiveClipboardMediaRef.parse(
                    "content://$SOURCE_AUTHORITY/clips/images/$sourceId",
                    SOURCE_AUTHORITY,
                    ItemType.IMAGE,
                )!!
                PreparedClipboardMedia(
                    ref = ref,
                    stagedFile = testRoot.resolve("unused-$sourceId"),
                    byteCount = ClipboardFileStorage.MAX_MEDIA_BYTES,
                    mimeTypes = listOf("image/png"),
                )
            }
            val oversizedItems = oversizedMedia.map { media ->
                PreparedClipboardItem(
                    type = ItemType.IMAGE,
                    text = null,
                    creationTimestampMs = 1,
                    isPinned = false,
                    mimeTypes = listOf("image/png"),
                    isSensitive = false,
                    isRemoteDevice = false,
                    mediaRef = media.ref,
                )
            }
            shouldThrow<IllegalArgumentException> {
                PreparedClipboardRestore(
                    selectedTypes = setOf(ItemType.IMAGE),
                    items = oversizedItems,
                    media = oversizedMedia,
                )
            }
        }

        test("does not allow custom limits above storage safety caps") {
            shouldThrow<IllegalArgumentException> {
                ClipboardRestorePayloadLimits.Default.copy(maxTextChars = 1_000_001)
            }
            shouldThrow<IllegalArgumentException> {
                ClipboardRestorePayloadLimits.Default.copy(
                    maxMediaBytes = ClipboardFileStorage.MAX_MEDIA_BYTES + 1,
                )
            }
            shouldThrow<IllegalArgumentException> {
                ClipboardRestorePayloadLimits.Default.copy(
                    maxTotalMediaBytes = ClipboardFileStorage.MAX_TOTAL_MEDIA_BYTES + 1,
                )
            }
            shouldThrow<IllegalArgumentException> {
                ClipboardRestorePayloadLimits.Default.copy(
                    maxMimeTypesPerItem = ClipboardFileStorage.MAX_MEDIA_MIME_TYPES + 1,
                )
            }
            shouldThrow<IllegalArgumentException> {
                ClipboardRestorePayloadLimits.Default.copy(
                    maxMimeTypeChars = ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH + 1,
                )
            }
        }

        test("prepared and failure summaries never expose clipboard content or paths") {
            val textMarker = "private-text-marker"
            val mimeMarker = "private-mime-marker"
            val displayNameMarker = "private-display-name-marker"
            val idMarker = "738291"
            val root = testRoot.workspace("redaction-$textMarker")
            val uri = "content://$SOURCE_AUTHORITY/clips/images/$idMarker"
            val serialized = SerializedClipboardItem(
                id = idMarker.toLong(),
                type = ItemType.IMAGE,
                text = textMarker,
                uri = uri,
                creationTimestampMs = 1,
                isPinned = false,
                mimeTypes = listOf("image/$mimeMarker"),
                displayName = "$displayNameMarker.png",
            )
            root.writeIndex(
                ItemType.IMAGE,
                imageItemJson(
                    uri = uri,
                    mimeTypes = """["image/$mimeMarker"]""",
                    text = textMarker,
                    displayNameJson = "\"$displayNameMarker.png\"",
                ),
            )
            root.writeMedia(idMarker.toLong(), byteArrayOf(1))

            val valid = root.prepare(ItemType.IMAGE) as ClipboardRestorePayloadResult.Valid
            val summaries = listOf(
                serialized.toString(),
                valid.toString(),
                valid.payload.toString(),
                valid.payload.items.single().toString(),
                valid.payload.media.single().toString(),
            )
            summaries.forEach { summary ->
                summary shouldNotContain textMarker
                summary shouldNotContain mimeMarker
                summary shouldNotContain displayNameMarker
                summary shouldNotContain idMarker
                summary shouldNotContain root.toString()
                summary shouldNotContain uri
            }

            val invalidRoot = testRoot.workspace("invalid-redaction")
            invalidRoot.writeIndex(
                ItemType.IMAGE,
                imageItemJson("content://foreign/$textMarker/$idMarker"),
            )
            invalidRoot.prepare(ItemType.IMAGE).toString() shouldNotContain textMarker
        }
    })

private const val SOURCE_PACKAGE = "dev.patrickgold.florisboard.beta"
private const val SOURCE_AUTHORITY = "$SOURCE_PACKAGE.provider.clipboard"
private const val IMAGE_42 = "content://$SOURCE_AUTHORITY/clips/images/42"
private const val VIDEO_42 = "content://$SOURCE_AUTHORITY/clips/videos/42"

private fun Path.workspace(name: String): Path =
    Files.createDirectories(resolve(name))

private fun Path.prepare(vararg selectedTypes: ItemType): ClipboardRestorePayloadResult =
    ClipboardRestorePayload.prepare(
        stagedRoot = this,
        sourcePackageName = SOURCE_PACKAGE,
        selectedTypes = selectedTypes.toSet(),
    )

private fun Path.indexPath(type: ItemType): Path = resolve(
    when (type) {
        ItemType.TEXT -> BackupArchive.CLIPBOARD_TEXT_PATH
        ItemType.IMAGE -> BackupArchive.CLIPBOARD_IMAGES_PATH
        ItemType.VIDEO -> BackupArchive.CLIPBOARD_VIDEO_PATH
    },
)

private fun Path.writeIndex(type: ItemType, json: String) {
    val path = indexPath(type)
    Files.createDirectories(path.parent)
    Files.write(path, json.toByteArray(StandardCharsets.UTF_8))
}

private fun Path.mediaPath(id: Long): Path =
    resolve(BackupArchive.CLIPBOARD_MEDIA_ROOT).resolve(id.toString())

private fun Path.writeMedia(id: Long, bytes: ByteArray) {
    val path = mediaPath(id)
    Files.createDirectories(path.parent)
    Files.write(path, bytes)
}

private fun textItemJson(
    text: String?,
    timestamp: Long = 1,
    uri: String? = null,
    mimeTypes: String = """["text/plain"]""",
    displayNameJson: String? = null,
): String {
    val displayNameField = displayNameJson?.let { """,
            "displayName": $it""" }.orEmpty()
    return """
        [
          {
            "id": 7,
            "type": "TEXT",
            "text": ${text?.let { "\"$it\"" } ?: "null"},
            "uri": ${uri?.let { "\"$it\"" } ?: "null"},
            "creationTimestampMs": $timestamp,
            "isPinned": false,
            "mimeTypes": $mimeTypes$displayNameField
          }
        ]
    """.trimIndent()
}

private fun imageItemJson(
    uri: String,
    mimeTypes: String = """["image/png"]""",
    text: String? = null,
    displayNameJson: String? = null,
): String = mediaItemJson(ItemType.IMAGE, uri, mimeTypes, text, displayNameJson)

private fun videoItemJson(
    uri: String,
    mimeTypes: String = """["video/mp4"]""",
    text: String? = null,
    displayNameJson: String? = null,
): String = mediaItemJson(ItemType.VIDEO, uri, mimeTypes, text, displayNameJson)

private fun mediaItemsJson(
    type: ItemType,
    uri: String,
    count: Int,
    text: String? = null,
): String {
    val mimeType = when (type) {
        ItemType.IMAGE -> "image/png"
        ItemType.VIDEO -> "video/mp4"
        ItemType.TEXT -> error("Text items do not reference media.")
    }
    return (1..count).joinToString(prefix = "[", postfix = "]") { id ->
        """
            {
              "id": $id,
              "type": "$type",
              "text": ${text?.let { "\"$it\"" } ?: "null"},
              "uri": "$uri",
              "creationTimestampMs": 2,
              "isPinned": false,
              "mimeTypes": ["$mimeType"]
            }
        """.trimIndent()
    }
}

private fun mediaItemJson(
    type: ItemType,
    uri: String,
    mimeTypes: String,
    text: String? = null,
    displayNameJson: String? = null,
): String {
    val displayNameField = displayNameJson?.let { """,
            "displayName": $it""" }.orEmpty()
    return """
        [
          {
            "id": 8,
            "type": "$type",
            "text": ${text?.let { "\"$it\"" } ?: "null"},
            "uri": "$uri",
            "creationTimestampMs": 2,
            "isPinned": false,
            "mimeTypes": $mimeTypes$displayNameField
          }
        ]
    """.trimIndent()
}
