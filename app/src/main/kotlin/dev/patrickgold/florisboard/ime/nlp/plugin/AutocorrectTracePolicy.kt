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
