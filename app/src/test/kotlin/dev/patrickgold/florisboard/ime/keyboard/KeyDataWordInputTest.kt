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

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.MultiTextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyDataWordInputTest : FunSpec({
    val naturalWordInputs = listOf(
        "Latin letter" to key('é'),
        "Cyrillic letter" to key('ж'),
        "Arabic letter" to key('ش'),
        "Han letter" to key('漢'),
        "supplementary-plane letter" to key(0x10437),
        "Unicode alphabetic letter number" to key('\u2167'),
        "character digit" to key('3'),
        "numeric digit" to key('3', KeyType.NUMERIC),
        "ASCII apostrophe" to key('\''),
        "curly apostrophe" to key('\u2019'),
        "Persian half-space (zero-width non-joiner)" to key(KeyCode.HALF_SPACE),
        "zero-width joiner" to key('\u200D'),
        "Arabic tatweel (keshida)" to key(KeyCode.KESHIDA),
        "connector punctuation" to key('_'),
        "non-spacing combining mark" to key('\u0301'),
        "enclosing combining mark" to key('\u20DD'),
        "spacing combining mark" to key('\u093E'),
    )
    for ((name, data) in naturalWordInputs) {
        test("$name is accepted as word input in characters mode") {
            data.isWordInput(KeyboardMode.CHARACTERS) shouldBe true
        }
    }

    test("stock multi-code-point letters remain word and trace input") {
        val hindi = MultiTextKeyData(codePoints = intArrayOf(2332, 2381, 2334), label = "ज्ञ")
        val bengali = MultiTextKeyData(codePoints = intArrayOf(2509, 2480), label = "্র")
        val kurdish = TextKeyData(code = KeyCode.URI_COMPONENT_TLD, label = "وو")

        for (data in listOf(hindi, bengali, kurdish)) {
            data.isWordInput(KeyboardMode.CHARACTERS) shouldBe true
            data.isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe true
        }
        hindi.primaryCodePoint() shouldBe 2332
        bengali.primaryCodePoint() shouldBe 2509
        kurdish.primaryCodePoint() shouldBe 1608
    }

    test("a multi-output key keeps first-code-point boundary semantics") {
        val domain = TextKeyData(code = KeyCode.URI_COMPONENT_TLD, label = ".com")

        domain.isWordInput(KeyboardMode.CHARACTERS) shouldBe false
        domain.isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe true
    }

    test("provider predictions adjust touch geometry without redefining word boundaries") {
        val characterConnector = key('-')
        val numericConnector = key('#', KeyType.NUMERIC)

        characterConnector.isWordInput(KeyboardMode.CHARACTERS) shouldBe false
        numericConnector.isWordInput(KeyboardMode.CHARACTERS) shouldBe false
        characterConnector.isPredictiveInput(
            KeyboardMode.CHARACTERS,
            predictedCodePoints = setOf(characterConnector.code),
        ) shouldBe true
        numericConnector.isPredictiveInput(
            KeyboardMode.CHARACTERS,
            predictedCodePoints = setOf(numericConnector.code),
        ) shouldBe true
    }

    test("provider predictions cannot turn controls or spaces into predictive input") {
        val positiveFunction = key('a', KeyType.FUNCTION)
        val delete = TextKeyData.DELETE

        positiveFunction.isPredictiveInput(
            KeyboardMode.CHARACTERS,
            predictedCodePoints = setOf(positiveFunction.code),
        ) shouldBe false
        delete.isPredictiveInput(
            KeyboardMode.CHARACTERS,
            predictedCodePoints = setOf(delete.code),
        ) shouldBe false
    }

    test("word-separator spaces never become predictive input") {
        for (code in listOf(KeyCode.SPACE, KeyCode.CJK_SPACE)) {
            key(code).isPredictiveInput(
                KeyboardMode.CHARACTERS,
                predictedCodePoints = setOf(code),
            ) shouldBe false
        }
    }

    for (mode in KeyboardMode.entries.filterNot { it == KeyboardMode.CHARACTERS }) {
        test("predictive input is disabled in $mode mode") {
            val data = key('a')
            data.isPredictiveInput(
                mode,
                predictedCodePoints = setOf(data.code),
            ) shouldBe false
        }
    }

    test("autocorrect trace geometry accepts character keys and numeric digits") {
        key('a').isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe true
        key('\u0301').isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe true
        key('1', KeyType.NUMERIC).isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe true
        key(KeyCode.HALF_SPACE).isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe true
        key(KeyCode.KESHIDA).isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe true
    }

    test("autocorrect trace geometry rejects controls spaces and non-digit numeric keys") {
        key(KeyCode.SPACE).isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe false
        key(KeyCode.CJK_SPACE).isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe false
        TextKeyData.DELETE.isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe false
        key('#', KeyType.NUMERIC).isAutocorrectTraceInput(KeyboardMode.CHARACTERS) shouldBe false
    }

    for (mode in KeyboardMode.entries.filterNot { it == KeyboardMode.CHARACTERS }) {
        test("autocorrect trace geometry is disabled in $mode mode") {
            key('a').isAutocorrectTraceInput(mode) shouldBe false
        }
    }
})

private fun key(
    code: Char,
    type: KeyType = KeyType.CHARACTER,
): TextKeyData {
    return key(code.code, type)
}

private fun key(
    code: Int,
    type: KeyType = KeyType.CHARACTER,
): TextKeyData {
    return TextKeyData(
        type = type,
        code = code,
        label = if (code > 0) String(Character.toChars(code)) else "",
    )
}
