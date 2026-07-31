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

package dev.patrickgold.florisboard.ime.clipboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClipboardSharePreviewTest :
    FunSpec({
        test("plans bounded previews without changing small images") {
            planClipboardSharePreview(1, 1) shouldBe
                ClipboardSharePreviewPlan(sampleSize = 1, width = 1, height = 1)
            planClipboardSharePreview(512, 512) shouldBe
                ClipboardSharePreviewPlan(sampleSize = 1, width = 512, height = 512)
            planClipboardSharePreview(4_000, 2_000) shouldBe
                ClipboardSharePreviewPlan(sampleSize = 4, width = 512, height = 256)
        }

        test("rejects invalid dimensions and pixel bombs") {
            listOf(
                0 to 1,
                1 to 0,
                -1 to 1,
                100_001 to 1,
                10_001 to 10_000,
                Int.MAX_VALUE to Int.MAX_VALUE,
            ).forEach { (width, height) ->
                planClipboardSharePreview(width, height) shouldBe null
            }
        }

        test("retains a visible edge for extreme accepted aspect ratios") {
            planClipboardSharePreview(100_000, 1) shouldBe
                ClipboardSharePreviewPlan(sampleSize = 128, width = 512, height = 1)
            planClipboardSharePreview(1, 100_000) shouldBe
                ClipboardSharePreviewPlan(sampleSize = 128, width = 1, height = 512)
        }

        test("publishes bounded concrete image MIME metadata") {
            clipboardShareMimeTypes("IMAGE/PNG", "image/jpeg") shouldBe
                listOf("image/png", "image/jpeg")
            clipboardShareMimeTypes(" image/webp ", "image/webp") shouldBe
                listOf("image/webp")
            clipboardShareMimeTypes(null, "image/svg+xml") shouldBe
                listOf("image/svg+xml")
            clipboardShareMimeTypes(
                decodedMimeType = null,
                declaredMimeType = "image/*",
                sourceMimeType = "IMAGE/SVG+XML",
            ) shouldBe listOf("image/svg+xml")
        }

        test("rejects wildcards and malformed or non-image MIME metadata") {
            listOf(
                null to null,
                null to "image/*",
                "video/mp4" to "application/octet-stream",
                "image/png; charset=binary" to "",
                "image/${"a".repeat(128)}" to "text/plain",
            ).forEach { (decoded, declared) ->
                clipboardShareMimeTypes(decoded, declared) shouldBe listOf("image/unknown")
            }
        }

        test("commits root preparation before publishing without a cancellation gap") {
            val steps = mutableListOf<String>()

            commitSystemClipboardMediaPublication(
                prepareRoot = { steps += "root" },
                markActive = { steps += "active" },
                verifyReadableRoot = { steps += "verify" },
                publish = { steps += "publish" },
            )

            steps shouldBe listOf("root", "active", "verify", "publish")
        }

        test("a process-restored request reuses only its own operation token") {
            val original = requireNotNull(
                ClipboardShareOperation.resolve(
                    sourceUri = "content://source/images/42",
                    declaredMimeType = "image/png",
                ),
            )
            val restored = requireNotNull(
                ClipboardShareOperation.resolve(
                    sourceUri = "content://source/images/42",
                    declaredMimeType = "image/png",
                    restoredToken = original.token.value,
                    restoredRequestFingerprint = original.requestFingerprint.value,
                ),
            )
            val mismatchedSource =
                ClipboardShareOperation.resolve(
                    sourceUri = "content://source/images/73",
                    declaredMimeType = "image/png",
                    restoredToken = original.token.value,
                    restoredRequestFingerprint = original.requestFingerprint.value,
                )
            val malformedToken =
                ClipboardShareOperation.resolve(
                    sourceUri = "content://source/images/42",
                    declaredMimeType = "image/png",
                    restoredToken = "not-an-operation-token",
                    restoredRequestFingerprint = original.requestFingerprint.value,
                )
            val partialIdentity = ClipboardShareOperation.resolve(
                sourceUri = "content://source/images/42",
                declaredMimeType = "image/png",
                restoredToken = original.token.value,
            )

            restored.token shouldBe original.token
            original.isRestored shouldBe false
            restored.isRestored shouldBe true
            mismatchedSource shouldBe null
            malformedToken shouldBe null
            partialIdentity shouldBe null
            restored.matches("content://source/images/42", "image/png") shouldBe true
            restored.matches("content://source/images/73", "image/png") shouldBe false
        }

        test("share operation identity is bounded and summaries remain opaque") {
            ClipboardShareOperation.resolve(
                sourceUri = "x".repeat(32 * 1024 + 1),
                declaredMimeType = "image/png",
            ) shouldBe null

            val operation = requireNotNull(
                ClipboardShareOperation.resolve(
                    sourceUri = "content://private/source/42",
                    declaredMimeType = "image/png",
                ),
            )
            operation.requestFingerprint.value.length shouldBe 64
            operation.toString().contains("private") shouldBe false
            operation.toString().contains(operation.token.value) shouldBe false
        }
    })
