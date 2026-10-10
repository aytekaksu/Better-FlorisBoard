/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeNlpProviderMap
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.han.HanShapeBasedLanguageProvider
import dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class NlpProviderCapabilityTest :
    FunSpec({
        test("optional lifecycle and suggestion callbacks have safe defaults") {
            val provider = object : SuggestionProvider {
                override val providerId = "org.florisboard.test.minimal"

                override suspend fun suggest(
                    subtype: Subtype,
                    content: EditorContent,
                    maxCandidateCount: Int,
                    allowPossiblyOffensive: Boolean,
                    isPrivateSession: Boolean,
                ) = emptyList<SuggestionCandidate>()
            }
            val candidate = WordSuggestionCandidate(text = "word")

            provider.create()
            provider.preload(Subtype.DEFAULT)
            provider.notifySuggestionAccepted(Subtype.DEFAULT, candidate)
            provider.notifySuggestionReverted(Subtype.DEFAULT, candidate)
            provider.removeSuggestion(Subtype.DEFAULT, candidate) shouldBe false
            provider.destroy()
        }

        test("active suggestion provider follows external selection changes") {
            val external = object : SuggestionProvider by FallbackNlpProvider {
                override val providerId = "org.florisboard.test.external"
            }

            selectActiveSuggestionProvider(FallbackNlpProvider, external, "") shouldBe FallbackNlpProvider
            selectActiveSuggestionProvider(FallbackNlpProvider, external, external.providerId) shouldBe external
            selectActiveSuggestionProvider(FallbackNlpProvider, external, "") shouldBe FallbackNlpProvider
        }

        test("glide lexicon lookup distinguishes capable and incapable providers") {
            val subtype = Subtype.DEFAULT.copy(id = 42)
            FallbackNlpProvider.glideTypingWordsOrEmpty(subtype).shouldBeEmpty()
            FallbackNlpProvider.glideTypingWordFrequencyOrZero(subtype, "word") shouldBe 0.0

            var wordsSubtype: Subtype? = null
            var frequencySubtype: Subtype? = null

            val provider = object : SuggestionProvider by FallbackNlpProvider, GlideTypingLexiconProvider {
                override val providerId = "org.florisboard.test.glide"

                override suspend fun getWords(subtype: Subtype): List<String> {
                    wordsSubtype = subtype
                    return listOf("alpha", "beta")
                }

                override suspend fun getWordFrequency(subtype: Subtype, word: String): Double {
                    frequencySubtype = subtype
                    return if (word == "alpha") 0.75 else 0.0
                }
            }

            provider.glideTypingWordsOrEmpty(subtype) shouldBe listOf("alpha", "beta")
            wordsSubtype shouldBe subtype
            provider.glideTypingWordFrequencyOrZero(subtype, "alpha") shouldBe 0.75
            frequencySubtype shouldBe subtype
            provider.glideTypingWordFrequencyOrZero(subtype, "unknown") shouldBe 0.0
        }

        test("only the Latin built-in provider supplies a glide lexicon") {
            GlideTypingLexiconProvider::class.java.isAssignableFrom(LatinLanguageProvider::class.java) shouldBe true
            GlideTypingLexiconProvider::class.java.isAssignableFrom(
                HanShapeBasedLanguageProvider::class.java,
            ) shouldBe false
            GlideTypingLexiconProvider::class.java.isAssignableFrom(
                FallbackNlpProvider::class.java,
            ) shouldBe false
        }

        test("saved subtypes keep their legacy spelling provider field") {
            val saved = """{"spelling":"legacy.provider","suggestion":"active.provider"}"""
            val providers = Json.decodeFromString<SubtypeNlpProviderMap>(saved)

            providers.spelling shouldBe "legacy.provider"
            providers.suggestion shouldBe "active.provider"
            Json.decodeFromString<SubtypeNlpProviderMap>(Json.encodeToString(providers)) shouldBe providers
        }
    })
