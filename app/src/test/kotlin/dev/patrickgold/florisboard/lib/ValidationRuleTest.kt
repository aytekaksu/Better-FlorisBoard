/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib

import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.dictionary.FREQUENCY_MAX
import dev.patrickgold.florisboard.ime.dictionary.FREQUENCY_MIN
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryValidation
import dev.patrickgold.florisboard.lib.ext.ExtensionValidation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ValidationRuleTest :
    FunSpec({
        test("rules receive typed values and expose result helpers") {
            var received = 0
            val rule = ValidationRule<Int> { value ->
                received = value
                if (value > 0) resultValid() else resultInvalid(R.string.ext__validation__enter_valid_number)
            }

            rule.validate(42).isValid() shouldBe true
            received shouldBe 42
            rule.validate(0).isInvalid() shouldBe true
        }

        test("extension rule boundaries remain unchanged") {
            ExtensionValidation.MetaId.validate("org.florisboard.theme").isValid() shouldBe true
            ExtensionValidation.MetaId.validate("Invalid ID").isInvalid() shouldBe true
            ExtensionValidation.ThemeComponentStylesheetPath.validate("").isValid() shouldBe true
            ExtensionValidation.ThemeComponentStylesheetPath.validate(" ").isInvalid() shouldBe true
            ExtensionValidation.ThemeComponentStylesheetPath.validate("../theme.json").isInvalid() shouldBe true
            ExtensionValidation.ThemeComponentStylesheetPath.validate("styles/theme.json").isValid() shouldBe true

            val hintedPercent = ExtensionValidation.SnyggPercentShapeValue.validate("51")
            hintedPercent.isValid() shouldBe true
            (hintedPercent as ValidationResult.Valid).hasHintMessage() shouldBe true
        }

        listOf(
            "version" to ExtensionValidation.MetaVersion,
            "title" to ExtensionValidation.MetaTitle,
            "maintainers" to ExtensionValidation.MetaMaintainers,
            "license" to ExtensionValidation.MetaLicense,
            "authors" to ExtensionValidation.ComponentAuthors,
        ).forEach { (name, rule) ->
            test("$name requires nonblank text") {
                listOf("", " \t\r\n", "\u2003\u2028").forEach { input ->
                    rule.validate(input).isInvalid() shouldBe true
                }
                listOf("text", "\ntext\n", "one\n\ntwo").forEach { input ->
                    rule.validate(input).isValid() shouldBe true
                }
            }
        }

        test("dictionary rule boundaries remain unchanged") {
            UserDictionaryValidation.Word.validate("hello").isValid() shouldBe true
            UserDictionaryValidation.Word.validate("two words").isInvalid() shouldBe true
            UserDictionaryValidation.Freq.validate(FREQUENCY_MIN.toString()).isValid() shouldBe true
            UserDictionaryValidation.Freq.validate(FREQUENCY_MAX.toString()).isValid() shouldBe true
            UserDictionaryValidation.Freq.validate((FREQUENCY_MAX + 1).toString()).isInvalid() shouldBe true
            UserDictionaryValidation.Shortcut.validate("").isValid() shouldBe true
            UserDictionaryValidation.Locale.validate("en-US").isValid() shouldBe true
            UserDictionaryValidation.Locale.validate("custom locale").isValid() shouldBe true
        }
    })
