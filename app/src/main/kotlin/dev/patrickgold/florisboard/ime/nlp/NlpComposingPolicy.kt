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
import dev.patrickgold.florisboard.ime.editor.EditorComposingPolicy
import dev.patrickgold.florisboard.ime.editor.EditorRange

/** Live composing decisions shared by the editor and candidate pipeline. */
internal class NlpComposingPolicy(
    private val builtInProviders: Map<String, SuggestionProvider>,
    private val activeSubtype: () -> Subtype,
    private val selectedExternalProviderId: () -> String,
    private val suggestionsEnabled: () -> Boolean,
    private val emojiSuggestionsEnabled: () -> Boolean,
) : EditorComposingPolicy {
    // External autocorrect uses the default provider composing rules. Keep this path independent
    // of its manager so the editor cannot resolve NLP through a transitive dependency.
    internal fun providerFor(subtype: Subtype): SuggestionProvider = selectActiveSuggestionProvider(
        builtInProvider = builtInProviders[subtype.nlpProviders.suggestion] ?: FallbackNlpProvider,
        externalProvider = FallbackNlpProvider,
        externalProviderId = selectedExternalProviderId(),
    )

    override fun determineLocalComposing(
        textBeforeSelection: CharSequence,
        breakIterators: BreakIteratorGroup,
        localLastCommitPosition: Int,
    ): EditorRange {
        val subtype = activeSubtype()
        return providerFor(subtype).determineLocalComposing(
            subtype,
            textBeforeSelection,
            breakIterators,
            localLastCommitPosition,
        )
    }

    override fun isSuggestionOn(): Boolean = suggestionsEnabled() ||
        emojiSuggestionsEnabled() || providerFor(activeSubtype()).forcesSuggestionOn
}
