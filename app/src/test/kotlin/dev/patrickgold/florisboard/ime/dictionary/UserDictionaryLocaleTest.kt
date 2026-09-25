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
    test("Floris imports canonicalize only locale tags they can represent") {
        mapOf(
            "en-US" to "en_US",
            "en-us" to "en_US",
            "de_DE" to "de_DE",
            "de-419" to "de_419",
            "iw-IL" to "iw_IL",
            "iw_IL" to "iw_IL",
            "he-IL" to "he_IL",
            "he_IL" to "he_IL",
            "in-ID" to "in_ID",
            "in_ID" to "in_ID",
            "id-ID" to "id_ID",
            "id_ID" to "id_ID",
            "ji-US" to "ji_US",
            "ji" to "ji",
            "yi-US" to "yi_US",
            "yi" to "yi",
            "iw-IL-u-ca-gregory" to "iw-IL-u-ca-gregory",
            "sr-Latn-RS" to "sr-Latn-RS",
            "en-US-u-ca-gregory" to "en-US-u-ca-gregory",
            "en_US_#u-ca-gregory" to "en_US_#u-ca-gregory",
        ).forEach { (input, expected) ->
            canonicalImportedFlorisLocale(input) shouldBe expected
        }
        canonicalImportedFlorisLocale(null) shouldBe null
    }

    test("language-alias families canonicalize separators without merging codes or losing subtags") {
        canonicalAliasFamilyLocale("IW-il") shouldBe "iw_IL"
        canonicalAliasFamilyLocale("HE-il") shouldBe "he_IL"
        canonicalAliasFamilyLocale("iw-IL-POSIX") shouldBe "iw_IL_POSIX"
        listOf("iw-IL-u-ca-gregory", "he-Latn-IL", "in-ID-", "iw_IL-POSIX", "en-US").forEach { tag ->
            canonicalAliasFamilyLocale(tag) shouldBe null
        }
    }

    test("mixed separator variant tags keep their exact import and browsing identity") {
        listOf("en_US-POSIX", "en-US_POSIX").forEach { tag ->
            canonicalImportedFlorisLocale(tag) shouldBe tag
            parsedFlorisBrowseLocale(tag) shouldBe null
        }
    }

    test("parsed browsing is limited to tags the standard Room query can actually match") {
        parsedFlorisBrowseLocale("en_US")?.localeTag() shouldBe "en_US"
        parsedFlorisBrowseLocale("en-US")?.localeTag() shouldBe "en_US"
        listOf(
            "iw", "iw_IL", "he", "in", "id", "ji", "yi", "sr-Latn-RS", "en-US-u-ca-gregory", "sr-Latn-RS-",
            "all", "ALL", "null", "NULL",
        ).forEach { tag ->
            parsedFlorisBrowseLocale(tag) shouldBe null
        }
    }

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
