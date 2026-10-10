/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.han

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.nlp.BreakIteratorGroup
import dev.patrickgold.florisboard.ime.nlp.LanguagePackExtension
import dev.patrickgold.florisboard.ime.nlp.NlpComposingPolicy
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import dev.patrickgold.florisboard.lib.io.FlorisRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HanShapeBasedLanguageProviderAndroidTest {
    @Test
    fun injectedFlowsRefreshBundledLanguagePack() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packPath = "ime/languagepack/org.florisboard.hanshapebasedbasicpack"
        val pack = ExtensionJsonConfig.decodeFromString(
            LanguagePackExtension.serializer(),
            context.assets.open("$packPath/extension.json").bufferedReader().use { it.readText() },
        ).apply { sourceRef = FlorisRef.assets(packPath) }
        val subtype = Subtype.DEFAULT.copy(
            primaryLocale = pack.items.first { it.id == "zh_TW_boshiamy" }.locale,
            nlpProviders = Subtype.DEFAULT.nlpProviders.copy(suggestion = HanShapeBasedLanguageProvider.ProviderId),
        )
        val subtypesFlow = MutableStateFlow(emptyList<Subtype>())
        val languagePacksFlow = MutableStateFlow(listOf(pack))
        val provider = HanShapeBasedLanguageProvider(context, subtypesFlow, lazy { languagePacksFlow })
        val composingPolicy = NlpComposingPolicy(
            builtInProviders = mapOf(HanShapeBasedLanguageProvider.ProviderId to provider),
            activeSubtype = { subtype },
            selectedExternalProviderId = { "" },
            suggestionsEnabled = { false },
            emojiSuggestionsEnabled = { false },
        )

        fun composingRange() = composingPolicy.determineLocalComposing("a[", BreakIteratorGroup(), 0)
        assertEquals(EditorRange.Unspecified, composingRange())

        try {
            provider.create()
            assertNull(provider.getLanguagePack(subtype))

            subtypesFlow.value = listOf(subtype)
            withTimeout(30_000L) {
                while (provider.getLanguagePack(subtype) == null) delay(20L)
            }
            assertEquals(pack.meta.id, provider.getLanguagePack(subtype)?.second?.meta?.id)
            assertEquals(EditorRange(0, 2), composingRange())

            languagePacksFlow.value = emptyList()
            withTimeout(30_000L) {
                while (provider.getLanguagePack(subtype) != null) delay(20L)
            }
            assertEquals(EditorRange.Unspecified, composingRange())
            languagePacksFlow.value = listOf(pack)
            withTimeout(30_000L) {
                while (provider.getLanguagePack(subtype) == null) delay(20L)
            }

            subtypesFlow.value = emptyList()
            withTimeout(30_000L) {
                while (provider.getLanguagePack(subtype) != null) delay(20L)
            }
        } finally {
            provider.destroy()
        }
    }
}
