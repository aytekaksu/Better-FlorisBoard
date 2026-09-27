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

package dev.patrickgold.florisboard.app.settings.localization

import androidx.compose.runtime.saveable.SaverScope
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeNlpProviderMap
import dev.patrickgold.florisboard.ime.keyboard.LayoutType
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SubtypeEditorStateTest : FunSpec({
    test("unfinished draft survives save and restore") {
        val draft = SubtypeEditorState(null)
        draft.primaryLocale.value = FlorisLocale.from("de", "DE")

        val restored = draft.roundTrip()

        restored.primaryLocale.value shouldBe draft.primaryLocale.value
        restored.toSubtype().isFailure shouldBe true
    }

    test("every field of a complete subtype survives save and restore") {
        val subtype = Subtype.DEFAULT.copy(
            id = 42L,
            primaryLocale = FlorisLocale.from("de", "DE"),
            secondaryLocales = listOf(FlorisLocale.from("fr", "FR")),
            nlpProviders = SubtypeNlpProviderMap(spelling = "spell.provider", suggestion = "suggest.provider"),
            punctuationRule = ExtensionComponentName("test", "punctuation"),
        )

        SubtypeEditorState(subtype).roundTrip().toSubtype().getOrThrow() shouldBe subtype
    }

    listOf(
        LayoutType.CHARACTERS,
        LayoutType.SYMBOLS,
        LayoutType.SYMBOLS2,
        LayoutType.NUMERIC,
        LayoutType.NUMERIC_ADVANCED,
        LayoutType.NUMERIC_ROW,
        LayoutType.PHONE,
        LayoutType.PHONE2,
    ).forEach { type ->
        test("placeholder $type prevents final save") {
            val layoutMap = requireNotNull(Subtype.DEFAULT.layoutMap.copy(type, ExtensionComponentName("00", "00")))
            SubtypeEditorState(Subtype.DEFAULT.copy(layoutMap = layoutMap)).toSubtype().isFailure shouldBe true
        }
    }
})

private fun SubtypeEditorState.roundTrip(): SubtypeEditorState {
    val saved = requireNotNull(with(SubtypeEditorState.Saver) {
        with(object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }) { save(this@roundTrip) }
    })
    return requireNotNull(SubtypeEditorState.Saver.restore(saved))
}
