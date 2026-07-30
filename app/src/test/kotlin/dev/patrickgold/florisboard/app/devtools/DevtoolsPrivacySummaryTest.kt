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

package dev.patrickgold.florisboard.app.devtools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class DevtoolsPrivacySummaryTest :
    FunSpec({
        test("editor summary exposes lengths and closed states without editor text") {
            val sensitiveValues = listOf(
                "cached-editor-sentinel",
                "before-selection-sentinel",
                "selected-text-sentinel",
                "after-selection-sentinel",
                "composing-text-sentinel",
                "current-word-sentinel",
            )

            val rendered = editorDebugLines(
                cachedText = sensitiveValues[0],
                textBeforeSelection = sensitiveValues[1],
                selectedText = sensitiveValues[2],
                textAfterSelection = sensitiveValues[3],
                composingText = sensitiveValues[4],
                currentWordText = sensitiveValues[5],
                selectionIsValid = true,
                composingIsValid = true,
                currentWordIsValid = true,
                lastCommitKnown = true,
            ).joinToString()

            sensitiveValues.forEach { rendered shouldNotContain it }
            rendered shouldContain "Cached text length: ${sensitiveValues[0].length}"
            rendered shouldContain "Selection: present"
            rendered shouldContain "Last commit known: true"
        }

        test("clipboard summary ignores MIME values") {
            val mimeSentinel = "private/mime-sentinel"
            val rendered = clipboardDebugLines(
                type = ClipboardDebugType.TEXT,
                isPinned = true,
                isSensitive = true,
                isRemoteDevice = false,
                mimeTypes = listOf(mimeSentinel),
            ).joinToString()

            rendered shouldNotContain mimeSentinel
            rendered shouldContain "Type: text"
            rendered shouldContain "MIME types: 1"
        }

        test("failure summary exposes only the exception class") {
            val messageSentinel = "private-exception-message"
            val rendered = failureClassName(IllegalStateException(messageSentinel))

            rendered shouldBe "IllegalStateException"
            rendered shouldNotContain messageSentinel
        }
    })
