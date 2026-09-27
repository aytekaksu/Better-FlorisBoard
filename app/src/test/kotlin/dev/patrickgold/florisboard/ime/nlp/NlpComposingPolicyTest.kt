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

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NlpComposingPolicyTest :
    FunSpec({
        test("composing follows the live subtype and external selection") {
            val latinProvider = TestComposingProvider("latin", EditorRange(0, 1), forcesSuggestions = false)
            val hanProvider = TestComposingProvider("han", EditorRange(0, 2), forcesSuggestions = true)
            val latin = Subtype.DEFAULT.copy(
                nlpProviders = Subtype.DEFAULT.nlpProviders.copy(suggestion = latinProvider.providerId),
            )
            val han = latin.copy(nlpProviders = latin.nlpProviders.copy(suggestion = hanProvider.providerId))
            var subtype = latin
            var externalId = ""
            val policy = NlpComposingPolicy(
                builtInProviders = mapOf(
                    latinProvider.providerId to latinProvider,
                    hanProvider.providerId to hanProvider,
                ),
                activeSubtype = { subtype },
                selectedExternalProviderId = { externalId },
                suggestionsEnabled = { false },
                emojiSuggestionsEnabled = { false },
            )

            policy.determineLocalComposing("ab", BreakIteratorGroup(), 0) shouldBe EditorRange(0, 1)
            policy.isSuggestionOn() shouldBe false

            subtype = han
            policy.determineLocalComposing("ab", BreakIteratorGroup(), 0) shouldBe EditorRange(0, 2)
            policy.isSuggestionOn() shouldBe true

            externalId = "selected.provider"
            policy.providerFor(han) shouldBe FallbackNlpProvider
            policy.isSuggestionOn() shouldBe false

            externalId = ""
            policy.providerFor(han) shouldBe hanProvider
            policy.isSuggestionOn() shouldBe true
        }

        test("suggestion toggles and unknown-provider fallback are live") {
            var subtype = Subtype.DEFAULT.copy(
                nlpProviders = Subtype.DEFAULT.nlpProviders.copy(suggestion = "missing.provider"),
            )
            var suggestionsEnabled = false
            var emojiSuggestionsEnabled = false
            val policy = NlpComposingPolicy(
                builtInProviders = emptyMap(),
                activeSubtype = { subtype },
                selectedExternalProviderId = { "" },
                suggestionsEnabled = { suggestionsEnabled },
                emojiSuggestionsEnabled = { emojiSuggestionsEnabled },
            )

            policy.providerFor(subtype) shouldBe FallbackNlpProvider
            policy.isSuggestionOn() shouldBe false
            suggestionsEnabled = true
            policy.isSuggestionOn() shouldBe true
            suggestionsEnabled = false
            emojiSuggestionsEnabled = true
            policy.isSuggestionOn() shouldBe true
            emojiSuggestionsEnabled = false
            subtype = Subtype.DEFAULT
            policy.isSuggestionOn() shouldBe false
        }
    })

private class TestComposingProvider(
    override val providerId: String,
    private val composingRange: EditorRange,
    private val forcesSuggestions: Boolean,
) : SuggestionProvider {
    override val forcesSuggestionOn: Boolean get() = forcesSuggestions

    override fun determineLocalComposing(
        subtype: Subtype,
        textBeforeSelection: CharSequence,
        breakIterators: BreakIteratorGroup,
        localLastCommitPosition: Int,
    ): EditorRange = composingRange

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ) = emptyList<SuggestionCandidate>()
}
