/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.autocorrect.api

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutocorrectFinishSessionTest {
    @Test
    fun finalRequestRoundTripStripsTraceAndInvalidRanges() {
        val request = request().copy(
            composingStart = 0,
            composingEnd = 99,
            inputTrace = AutocorrectInputTrace(
                keys = emptyList(),
                points = listOf(AutocorrectTouchPoint("x", 0.5f, 0.5f)),
            ),
        )

        val bundle = finishSessionBundle(7L, request)
        val actual = finalRequestFromFinishSessionBundle(bundle, 7L)!!

        assertTrue(bundle.getBoolean("hasFinalRequest"))
        assertEquals(AutocorrectInputTrace.Empty, actual.inputTrace)
        assertEquals(-1, actual.composingStart)
        assertEquals(-1, actual.composingEnd)
        assertEquals(0, actual.currentWordStart)
        assertEquals(5, actual.currentWordEnd)
    }

    @Test
    fun legacyFinishBundleHasNoFinalRequest() {
        val bundle = Bundle().apply { putLong("sessionId", 7L) }

        assertFalse(bundle.getBoolean("hasFinalRequest"))
        assertNull(finalRequestFromFinishSessionBundle(bundle, 7L))
    }

    @Test
    fun emptyCursorSnapshotRemainsAuthoritative() {
        val bundle = finishSessionBundle(
            7L,
            request().copy(
                text = "",
                selectionStart = 0,
                selectionEnd = 0,
                currentWordStart = -1,
                currentWordEnd = -1,
            ),
        )

        assertTrue(bundle.getBoolean("hasFinalRequest"))
        assertEquals("", finalRequestFromFinishSessionBundle(bundle, 7L)?.text)
    }

    @Test
    fun staleOrNonCursorSnapshotsBecomeAuthoritativeEmpty() {
        val stale = finishSessionBundle(7L, request()).apply {
            getBundle("finalRequest")!!.putLong("sessionId", 8L)
        }
        val selected = finishSessionBundle(
            7L,
            request().copy(selectionStart = 0, selectionEnd = 5),
        )

        assertEquals("", finalRequestFromFinishSessionBundle(stale, 7L)?.text)
        assertTrue(selected.getBoolean("hasFinalRequest"))
        assertEquals("", finalRequestFromFinishSessionBundle(selected, 7L)?.text)
    }

    private fun request() = AutocorrectRequest(
        sessionId = 7L,
        requestId = 9L,
        text = "probe@example.test",
        selectionStart = 18,
        selectionEnd = 18,
        composingStart = -1,
        composingEnd = -1,
        currentWordStart = 0,
        currentWordEnd = 5,
        maxCandidateCount = 3,
        allowPossiblyOffensive = false,
    )
}
