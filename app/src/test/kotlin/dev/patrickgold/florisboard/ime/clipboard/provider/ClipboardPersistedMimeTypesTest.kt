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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ClipboardPersistedMimeTypesTest :
    FunSpec({
        test("share recovery metadata is excluded from serialized and backup-shaped data") {
            val encoded = Json.encodeToString(
                ClipboardFileInfo(
                    id = 42L,
                    displayName = "Image",
                    size = 3L,
                    orientation = 0,
                    mimeTypes = listOf("image/png"),
                    ownershipState = ClipboardMediaOwnershipState.PENDING,
                    shareOperationToken = "123e4567-e89b-42d3-a456-426614174000",
                    shareRequestFingerprint = "a".repeat(64),
                    sharePendingBootCount = 42,
                    sharePendingDeadlineElapsedRealtimeMs = 73L,
                ),
            )

            encoded.contains("shareOperation") shouldBe false
            encoded.contains("shareRequest") shouldBe false
            encoded.contains("sharePending") shouldBe false
            encoded.contains("123e4567") shouldBe false
            encoded.contains("a".repeat(64)) shouldBe false
        }

        test("pending share recovery uses one exact boot and monotonic deadline") {
            val pending = ClipboardFileInfo(
                id = 42L,
                displayName = "Image",
                size = 3L,
                orientation = 0,
                mimeTypes = listOf("image/png"),
                ownershipState = ClipboardMediaOwnershipState.PENDING,
                shareOperationToken = "123e4567-e89b-42d3-a456-426614174000",
                shareRequestFingerprint = "a".repeat(64),
                sharePendingBootCount = 42,
                sharePendingDeadlineElapsedRealtimeMs = 101L,
            )

            shareOperationBindingHasValidShape(pending) shouldBe true
            sharePendingStatus(
                pending,
                currentBootCount = 42,
                elapsedRealtimeMs = 100L,
            ) shouldBe SharePendingStatus.UNEXPIRED
            sharePendingStatus(
                pending,
                currentBootCount = 42,
                elapsedRealtimeMs = 101L,
            ) shouldBe SharePendingStatus.EXPIRED
            sharePendingStatus(
                pending,
                currentBootCount = 43,
                elapsedRealtimeMs = 100L,
            ) shouldBe SharePendingStatus.EXPIRED
            sharePendingStatus(
                pending,
                currentBootCount = -1,
                elapsedRealtimeMs = 100L,
            ) shouldBe SharePendingStatus.UNVERIFIABLE
            sharePendingIsUnexpired(
                pending,
                currentBootCount = -1,
                elapsedRealtimeMs = 100L,
            ) shouldBe false
        }

        test("pending share recovery rejects malformed or impossible clock metadata") {
            val pending = ClipboardFileInfo(
                id = 42L,
                displayName = "Image",
                size = 3L,
                orientation = 0,
                mimeTypes = listOf("image/png"),
                ownershipState = ClipboardMediaOwnershipState.PENDING,
                shareOperationToken = "123e4567-e89b-42d3-a456-426614174000",
                shareRequestFingerprint = "a".repeat(64),
                sharePendingBootCount = 42,
                sharePendingDeadlineElapsedRealtimeMs = 101L,
            )

            shareOperationBindingHasValidShape(
                pending.copy(shareRequestFingerprint = "b"),
            ) shouldBe false
            shareOperationBindingHasValidShape(
                pending.copy(sharePendingBootCount = -1),
            ) shouldBe false
            sharePendingStatus(
                pending.copy(sharePendingDeadlineElapsedRealtimeMs = Long.MAX_VALUE),
                currentBootCount = 42,
                elapsedRealtimeMs = 100L,
            ) shouldBe SharePendingStatus.INVALID

            val attempted = pending.copy(
                ownershipState = ClipboardMediaOwnershipState.ACTIVE,
                sharePendingBootCount = null,
                sharePendingDeadlineElapsedRealtimeMs = 0L,
            )
            shareOperationBindingHasValidShape(attempted) shouldBe true
            shareOperationBindingHasValidShape(
                attempted.copy(sharePendingBootCount = 42),
            ) shouldBe false
            shareOperationBindingHasValidShape(
                attempted.copy(sharePendingDeadlineElapsedRealtimeMs = 1L),
            ) shouldBe false
        }

        test("repairs legacy family wildcards without losing concrete metadata") {
            ClipboardFileStorage.normalizePersistedMediaMimeTypes(
                listOf("image/*"),
            ) shouldBe listOf("image/unknown")
            ClipboardFileStorage.normalizePersistedMediaMimeTypes(
                listOf("application/octet-stream", "video/*"),
            ) shouldBe listOf("application/octet-stream", "video/unknown")
            ClipboardFileStorage.normalizePersistedMediaMimeTypes(
                listOf(" IMAGE/* ", "image/png", "APPLICATION/OCTET-STREAM", "image/png"),
            ) shouldBe listOf("image/png", "application/octet-stream")
            ClipboardFileStorage.normalizePersistedMediaMimeTypes(
                listOf("VIDEO/MP4", " video/* "),
            ) shouldBe listOf("video/mp4")
        }

        test("never guesses between image and video metadata") {
            listOf(
                listOf("image/*", "video/*"),
                listOf("image/*", "video/mp4"),
                listOf("image/png", "video/*"),
                listOf("image/png", "video/mp4"),
                List(ClipboardFileStorage.MAX_MEDIA_MIME_TYPES) { index -> "image/x$index" } +
                    "video/mp4",
            ).forEach { mimeTypes ->
                ClipboardFileStorage.normalizePersistedMediaMimeTypes(mimeTypes) shouldBe null
            }
        }

        test("reduces legacy metadata to the current persisted limits") {
            val familyTypes = List(ClipboardFileStorage.MAX_MEDIA_MIME_TYPES + 1) { index ->
                "image/x$index"
            }
            ClipboardFileStorage.normalizePersistedMediaMimeTypes(familyTypes) shouldBe
                familyTypes.take(ClipboardFileStorage.MAX_MEDIA_MIME_TYPES)

            val ancillaryTypes = List(ClipboardFileStorage.MAX_MEDIA_MIME_TYPES) { index ->
                "application/x$index"
            }
            ClipboardFileStorage.normalizePersistedMediaMimeTypes(
                ancillaryTypes + "image/png",
            ) shouldBe ancillaryTypes.take(ClipboardFileStorage.MAX_MEDIA_MIME_TYPES - 1) +
                "image/png"

            ClipboardFileStorage.normalizePersistedMediaMimeTypes(
                listOf(
                    "application/${"a".repeat(ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH)}",
                    "image/png",
                ),
            ) shouldBe listOf("image/png")
            ClipboardFileStorage.normalizePersistedMediaMimeTypes(
                listOf(
                    "image/${"a".repeat(ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH)}",
                    "video/mp4",
                ),
            ) shouldBe listOf("video/mp4")
        }

        test("rejects unsupported wildcards malformed family metadata and absurd inputs") {
            listOf(
                emptyList(),
                listOf("*/*"),
                listOf("application/*", "image/png"),
                listOf("image/png; charset=binary"),
                listOf("application/octet-stream"),
                listOf(""),
                listOf("image/${"a".repeat(ClipboardFileStorage.MAX_MEDIA_MIME_TYPE_LENGTH)}"),
                List(MAX_ARCHIVE_MEDIA_MIME_CANDIDATES + 1) { "image/png" },
                listOf("x".repeat(MAX_ARCHIVE_MEDIA_MIME_CANDIDATE_LENGTH + 1), "image/png"),
            ).forEach { mimeTypes ->
                ClipboardFileStorage.normalizePersistedMediaMimeTypes(mimeTypes) shouldBe null
            }
        }
    })
