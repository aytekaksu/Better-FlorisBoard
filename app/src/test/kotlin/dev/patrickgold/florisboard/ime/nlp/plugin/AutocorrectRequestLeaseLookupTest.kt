/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.autocorrect.host.core.BindingEpoch
import org.florisboard.autocorrect.host.core.EditorGeneration
import org.florisboard.autocorrect.host.core.HostState
import org.florisboard.autocorrect.host.core.PendingRequest
import org.florisboard.autocorrect.host.core.ProviderId
import org.florisboard.autocorrect.host.core.RequestId
import org.florisboard.autocorrect.host.core.RequestLease
import org.florisboard.autocorrect.host.core.RetiredRequest
import org.florisboard.autocorrect.host.core.RetiredRequestReason
import org.florisboard.autocorrect.host.core.SessionId

class AutocorrectRequestLeaseLookupTest :
    FunSpec({
        val current = lease(8L)
        val retired = lease(7L)
        val state = HostState(
            pendingRequest = PendingRequest(current),
            retiredRequests = listOf(RetiredRequest(retired, RetiredRequestReason.SUPERSEDED)),
        )

        test("wire reply IDs resolve only current or retained requests") {
            state.suggestionLeaseForReply(current.requestId.value) shouldBe current
            state.suggestionLeaseForReply(retired.requestId.value) shouldBe retired
            state.suggestionLeaseForReply(6L) shouldBe null
            state.suggestionLeaseForReply(0L) shouldBe null
            state.suggestionLeaseForReply(-1L) shouldBe null
        }

        test("malformed replies can fail only the current request on their binding") {
            state.currentSuggestionLeaseForMalformedReply(current.requestId.value, 2L) shouldBe current
            state.currentSuggestionLeaseForMalformedReply(null, 2L) shouldBe current
            state.currentSuggestionLeaseForMalformedReply(retired.requestId.value, 2L) shouldBe null
            state.currentSuggestionLeaseForMalformedReply(9L, 2L) shouldBe null
            state.currentSuggestionLeaseForMalformedReply(null, 3L) shouldBe null
            state.copy(pendingRequest = null)
                .currentSuggestionLeaseForMalformedReply(null, 2L) shouldBe null
        }
    })

private fun lease(requestId: Long) = RequestLease(
    providerId = ProviderId("example.provider/.Service"),
    epoch = BindingEpoch(2L),
    sessionId = SessionId(3L),
    requestId = RequestId(requestId),
    editorGeneration = EditorGeneration(4L),
)
