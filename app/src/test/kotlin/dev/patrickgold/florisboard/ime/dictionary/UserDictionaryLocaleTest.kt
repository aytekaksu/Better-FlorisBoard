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

package dev.patrickgold.florisboard.ime.dictionary

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UserDictionaryLocaleTest : FunSpec({
    test("Android storage locales retain scripts, extensions, and legacy variants") {
        mapOf(
            "zh_TW_#Hant" to "zh-Hant-TW",
            "sr_RS_#Latn" to "sr-Latn-RS",
            "de__POSIX" to "de-POSIX",
            "en__#Latn" to "en-Latn",
            "en__#u-ca-gregory" to "en-u-ca-gregory",
            "en__#x-foo" to "en-x-foo",
            "en__POSIX_#Latn" to "en-Latn-POSIX",
            "zh_TW_#Hant_x-java" to "zh-Hant-TW-x-java",
            "th_TH_TH_#u-nu-thai" to "th-TH-u-nu-thai-x-lvariant-TH",
            "en_US_WIN_#x-java" to "en-US-x-java-lvariant-WIN",
        ).forEach { (stored, expected) ->
            storedUserDictionaryLocale(stored)?.toLanguageTag() shouldBe expected
        }
    }

    test("malformed Android storage locales are rejected") {
        listOf(
            "und",
            "und-Latn",
            "x-private",
            "_US",
            "en__",
            "en_#Latn",
            "en_US#Latn",
            "en_US#",
            "en_US_#Hant_",
            "en_US_#not-script_bad",
            "en_US_#Hant_invalid",
            "en_US_#Hant#x-java",
        ).forEach { stored ->
            storedUserDictionaryLocale(stored) shouldBe null
        }
    }
})
