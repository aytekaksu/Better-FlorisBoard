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

package dev.patrickgold.florisboard.lib

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class FlorisLocaleTest :
    FunSpec({
        test("variant labels use the requested display locale") {
            val locale = FlorisLocale.from("en", "US", "idi")
            val turkish = FlorisLocale.from("tr", "TR")

            locale.displayName(turkish) shouldEndWith "[İDİ]"
        }

        test("language-pack locales missing from Android stay available") {
            val english = FlorisLocale.from("en", "US")
            val custom = FlorisLocale.from("zz", "ZZ")
            val variant = FlorisLocale.from("en", "US", "pack")

            mergeAvailableLocales(listOf(english), listOf(custom, variant)) shouldBe
                listOf(english, custom, variant)
        }

        test("system locales win exact duplicates and pack order stays stable") {
            val english = FlorisLocale.from("en", "US")
            val french = FlorisLocale.from("fr", "FR")
            val custom = FlorisLocale.from("zz", "ZZ")

            mergeAvailableLocales(
                listOf(english, french),
                listOf(french, custom, english, custom),
            ) shouldBe listOf(english, french, custom)
        }

        test("capitalization and automatic spacing follow the language policy") {
            val cases = listOf(
                Triple(FlorisLocale.from("ja"), false, false),
                Triple(FlorisLocale.from("ja", "JP"), false, false),
                Triple(FlorisLocale.from("zh"), false, false),
                Triple(FlorisLocale.from("ko"), false, false),
                Triple(FlorisLocale.from("th"), false, false),
                Triple(FlorisLocale.from("bn"), false, true),
                Triple(FlorisLocale.from("hi"), false, true),
                Triple(FlorisLocale.from("en"), true, true),
            )
            for ((locale, capitalization, autoSpace) in cases) {
                withClue(locale.languageTag()) {
                    locale.supportsCapitalization shouldBe capitalization
                    locale.supportsAutoSpace shouldBe autoSpace
                }
            }
        }
    })
