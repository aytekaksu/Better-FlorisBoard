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
import kotlin.math.abs

class ClipboardSharePreviewTest :
    FunSpec({
        test("plans bounded previews without changing small images") {
            listOf(1 to 1, 512 to 512).forEach { (width, height) ->
                val plan = assertBoundedPreviewPlan(width, height)
                plan.width shouldBe width
                plan.height shouldBe height
                Integer.highestOneBit(plan.sampleSize) shouldBe 1
            }
            listOf(4_000 to 2_000, 5_000 to 2_500).forEach { (width, height) ->
                assertBoundedPreviewPlan(width, height)
            }
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
            assertBoundedPreviewPlan(100_000, 1).height shouldBe 1
            assertBoundedPreviewPlan(1, 100_000).width shouldBe 1
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

private fun assertBoundedPreviewPlan(
    sourceWidth: Int,
    sourceHeight: Int,
): ClipboardSharePreviewPlan {
    val plan = requireNotNull(planClipboardSharePreview(sourceWidth, sourceHeight))
    (plan.sampleSize > 0) shouldBe true
    // BitmapFactory rounds a non-power-of-two request down, so raw division
    // would incorrectly accept e.g. a sample of 5 for a 5000-pixel source.
    val effectiveSample = Integer.highestOneBit(plan.sampleSize).toLong()
    val decodeWidth = (sourceWidth.toLong() + effectiveSample - 1) / effectiveSample
    val decodeHeight = (sourceHeight.toLong() + effectiveSample - 1) / effectiveSample
    (decodeWidth in 1L..1_024L) shouldBe true
    (decodeHeight in 1L..1_024L) shouldBe true
    // This is the plan's ARGB_8888 bound, not an Android allocation measurement.
    (decodeWidth * decodeHeight * 4L <= 1_024L * 1_024L * 4L) shouldBe true
    (plan.width in 1..minOf(sourceWidth, 512)) shouldBe true
    (plan.height in 1..minOf(sourceHeight, 512)) shouldBe true
    // Keep enough decoded detail to render without avoidable upscaling.
    (decodeWidth >= plan.width && decodeHeight >= plan.height) shouldBe true

    val sourceLongEdge = maxOf(sourceWidth, sourceHeight).toLong()
    val sourceShortEdge = minOf(sourceWidth, sourceHeight).toLong()
    val outputLongEdge = if (sourceWidth >= sourceHeight) plan.width else plan.height
    val outputShortEdge = if (sourceWidth >= sourceHeight) plan.height else plan.width
    outputLongEdge.toLong() shouldBe minOf(sourceLongEdge, 512L)
    // Permit integer rounding and the one-pixel floor for a very thin source.
    val aspectError = abs(outputShortEdge * sourceLongEdge - outputLongEdge * sourceShortEdge)
    (aspectError <= sourceLongEdge) shouldBe true
    return plan
}
