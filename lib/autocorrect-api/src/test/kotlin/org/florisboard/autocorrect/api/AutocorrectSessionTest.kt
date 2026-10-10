/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.autocorrect.api

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutocorrectSessionTest {
    @Test
    fun preferredEmojiSkinToneRoundTrips() {
        val session = session(preferredEmojiSkinToneModifier = 0x1F3FD)

        assertEquals(session, AutocorrectSession.fromBundle(session.toBundle()))
    }

    @Test
    fun invalidPreferredEmojiSkinToneNormalizesToDefault() {
        val session = session(preferredEmojiSkinToneModifier = 0x1F600)

        assertEquals(
            0,
            AutocorrectSession.fromBundle(session.toBundle()).preferredEmojiSkinToneModifier,
        )
    }

    private fun session(preferredEmojiSkinToneModifier: Int) = AutocorrectSession(
        sessionId = 7L,
        primaryLanguageTag = "en-US",
        secondaryLanguageTags = listOf("tr-TR"),
        inputType = 1,
        capsMode = 0,
        preferredEmojiSkinToneModifier = preferredEmojiSkinToneModifier,
    )
}
