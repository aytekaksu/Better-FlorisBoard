/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import org.florisboard.autocorrect.api.AutocorrectTouchPoint

/**
 * Compares a tap trace with editor text without constructing a joined copy of sensitive input.
 *
 * Besides avoiding a hot-path allocation, this ensures the temporary comparison value can never
 * escape through a debugger, heap dump, or future log statement.
 */
internal fun traceTextMatches(points: List<AutocorrectTouchPoint>, expectedText: String): Boolean {
    var expectedOffset = 0
    for (point in points) {
        val pointText = point.text
        if (
            expectedOffset + pointText.length > expectedText.length ||
            !expectedText.regionMatches(
                thisOffset = expectedOffset,
                other = pointText,
                otherOffset = 0,
                length = pointText.length,
            )
        ) {
            return false
        }
        expectedOffset += pointText.length
    }
    return expectedOffset == expectedText.length
}
