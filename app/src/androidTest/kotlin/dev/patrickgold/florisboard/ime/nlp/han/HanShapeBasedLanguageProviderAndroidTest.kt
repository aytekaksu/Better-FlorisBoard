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

package dev.patrickgold.florisboard.ime.nlp.han

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.LanguagePackExtension
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
        val subtype = Subtype.DEFAULT.copy(primaryLocale = pack.items.first().locale)
        val subtypesFlow = MutableStateFlow(emptyList<Subtype>())
        val languagePacksFlow = MutableStateFlow(listOf(pack))
        val provider = HanShapeBasedLanguageProvider(context, subtypesFlow, lazy { languagePacksFlow })

        try {
            provider.create()
            assertNull(provider.getLanguagePack(subtype))

            subtypesFlow.value = listOf(subtype)
            withTimeout(30_000L) {
                while (provider.getLanguagePack(subtype) == null) delay(20L)
            }
            assertEquals(pack.meta.id, provider.getLanguagePack(subtype)?.second?.meta?.id)

            languagePacksFlow.value = emptyList()
            withTimeout(30_000L) {
                while (provider.getLanguagePack(subtype) != null) delay(20L)
            }
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
