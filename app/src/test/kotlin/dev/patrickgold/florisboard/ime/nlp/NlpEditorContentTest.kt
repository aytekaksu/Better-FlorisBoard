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

package dev.patrickgold.florisboard.ime.nlp

import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NlpEditorContentTest : FunSpec({
    test("shared actions sample the current editor selection on every update") {
        var content = EditorContent.selectionOnly(EditorRange.cursor(1))
        var reads = 0
        val currentContent = {
            reads++
            content
        }
        val candidate = listOf(Unit)

        shouldExpandSmartbarActions(currentContent, candidate, null) shouldBe false
        content = EditorContent.selectionOnly(EditorRange(0, 1))
        shouldExpandSmartbarActions(currentContent, candidate, null) shouldBe true
        content = EditorContent.selectionOnly(EditorRange.cursor(1))
        shouldExpandSmartbarActions(currentContent, candidate, null) shouldBe false
        shouldExpandSmartbarActions(currentContent, emptyList<Any>(), null) shouldBe true
        reads shouldBe 4
    }
})
