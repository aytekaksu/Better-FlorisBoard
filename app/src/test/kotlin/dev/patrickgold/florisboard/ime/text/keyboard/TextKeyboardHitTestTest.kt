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

package dev.patrickgold.florisboard.ime.text.keyboard

import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.DefaultComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class TextKeyboardHitTestTest : FunSpec({
    test("directly touched n v and b centers beat adjacent predictive boosts") {
        val fixture = hitTestFixture()

        listOf(fixture.n, fixture.v, fixture.b).forEach { directKey ->
            fixture.keyboard.getKeyForPos(
                pointerX = directKey.visibleBounds.center.x,
                pointerY = directKey.visibleBounds.center.y,
                boostedCodePoints = setOf('n'.code, 'v'.code, 'b'.code) - directKey.computedData.code,
            ) shouldBe directKey
        }
    }

    test("a predictive boost can win near the shared character-key edge") {
        val fixture = hitTestFixture()

        fixture.keyboard.getKeyForPos(
            pointerX = 39f,
            pointerY = 20f,
            boostedCodePoints = setOf('b'.code),
        ) shouldBe fixture.b
    }

    test("predictive boosts never override directly touched space or period") {
        val fixture = hitTestFixture()

        fixture.keyboard.getKeyForPos(
            pointerX = 60f,
            pointerY = 55f,
            boostedCodePoints = setOf('v'.code, 'b'.code),
        ) shouldBe fixture.space
        fixture.keyboard.getKeyForPos(
            pointerX = 140f,
            pointerY = 20f,
            boostedCodePoints = setOf('.'.code, 'b'.code),
        ) shouldBe fixture.period
    }

    test("predictive boosts never override a directly touched editing control") {
        val delete = TextKey(TextKeyData.DELETE)
        val letter = TextKey(TextKeyData(code = 'b'.code, label = "b"))
        val keyboard = textKeyboard(KeyboardMode.CHARACTERS, delete, letter)
        delete.setTestBounds(0f, 0f, 40f, 40f)
        letter.setTestBounds(40f, 0f, 80f, 40f)

        keyboard.getKeyForPos(
            pointerX = 39f,
            pointerY = 20f,
            boostedCodePoints = setOf('b'.code),
        ) shouldBe delete
    }

    test("an empty point can be claimed only within the boost radius") {
        val fixture = hitTestFixture()

        fixture.keyboard.getKeyForPos(100f, 45f).shouldBeNull()
        fixture.keyboard.getKeyForPos(
            pointerX = 100f,
            pointerY = 45f,
            boostedCodePoints = setOf('n'.code),
        ) shouldBe fixture.n
        fixture.keyboard.getKeyForPos(
            pointerX = 100f,
            pointerY = -21f,
            boostedCodePoints = setOf('n'.code),
        ).shouldBeNull()
    }

    test("disabled keys cannot receive a predictive boost") {
        val fixture = hitTestFixture()
        fixture.n.isEnabled = false

        fixture.keyboard.getKeyForPos(
            pointerX = 100f,
            pointerY = 45f,
            boostedCodePoints = setOf('n'.code),
        ).shouldBeNull()
    }

    test("a disabled directly touched key is not replaced by a boosted neighbor") {
        val fixture = hitTestFixture()
        fixture.v.isEnabled = false

        fixture.keyboard.getKeyForPos(
            pointerX = 20f,
            pointerY = 20f,
            boostedCodePoints = setOf('b'.code),
        ) shouldBe fixture.v
    }

    for (mode in KeyboardMode.entries.filterNot { it == KeyboardMode.CHARACTERS }) {
        test("predictive boosts have no effect in $mode mode") {
            val fixture = hitTestFixture(mode)

            keyboardResultAtSharedEdge(fixture) shouldBe fixture.v
            fixture.keyboard.getKeyForPos(
                pointerX = 100f,
                pointerY = 45f,
                boostedCodePoints = setOf('n'.code),
            ).shouldBeNull()
        }
    }

    val naturallyPredictiveKeys = listOf(
        "Latin letter" to TextKeyData(code = 'é'.code, label = "é"),
        "Cyrillic letter" to TextKeyData(code = 'ж'.code, label = "ж"),
        "Arabic letter" to TextKeyData(code = 'ش'.code, label = "ش"),
        "Han letter" to TextKeyData(code = '漢'.code, label = "漢"),
        "supplementary-plane letter" to textKeyData(0x10437),
        "ASCII apostrophe" to TextKeyData(code = '\''.code, label = "'"),
        "curly apostrophe" to TextKeyData(code = '\u2019'.code, label = "\u2019"),
        "zero-width non-joiner" to TextKeyData(code = '\u200C'.code, label = "\u200C"),
        "Arabic tatweel" to TextKeyData(code = KeyCode.KESHIDA, label = "\u0640"),
        "combining mark" to TextKeyData(code = '\u0301'.code, label = "\u0301"),
        "connector punctuation" to TextKeyData(code = '_'.code, label = "_"),
        "number-row digit" to TextKeyData(type = KeyType.NUMERIC, code = '1'.code, label = "1"),
    )
    for ((name, data) in naturallyPredictiveKeys) {
        test("$name can receive a predictive boost") {
            val (keyboard, key) = singleKeyFixture(data)

            keyboard.getKeyForPos(
                pointerX = 45f,
                pointerY = 20f,
                boostedCodePoints = setOf(data.code),
            ) shouldBe key
        }
    }

    test("an autocorrect provider can declare a custom word connector") {
        val connector = TextKeyData(code = '-'.code, label = "-")
        val (keyboard, key) = singleKeyFixture(connector)

        keyboard.getKeyForPos(
            pointerX = 45f,
            pointerY = 20f,
            boostedCodePoints = setOf(connector.code),
        ) shouldBe key
    }

    test("a multi-code-point letter key can receive a first-code-point boost") {
        val data = MultiTextKeyData(
            codePoints = intArrayOf(2332, 2381, 2334),
            label = "ज्ञ",
        )
        val (keyboard, key) = singleKeyFixture(data)

        keyboard.getKeyForPos(
            pointerX = 45f,
            pointerY = 20f,
            boostedCodePoints = setOf(2332),
        ) shouldBe key
    }

    test("visible bounds define boost distance when they are available") {
        val direct = TextKey(TextKeyData(code = 'a'.code, label = "a"))
        val boosted = TextKey(TextKeyData(code = 'b'.code, label = "b"))
        val keyboard = textKeyboard(KeyboardMode.CHARACTERS, direct, boosted)
        direct.setTestBounds(0f, 0f, 50f, 40f)
        boosted.setTestBounds(50f, 0f, 100f, 40f)
        boosted.visibleBounds.apply {
            left = 80f
            top = 10f
            right = 100f
            bottom = 30f
        }

        keyboard.getKeyForPos(
            pointerX = 49f,
            pointerY = 20f,
            boostedCodePoints = setOf('b'.code),
        ) shouldBe direct
    }

    test("empty visible bounds fall back to touch bounds") {
        val (keyboard, key) = singleKeyFixture(TextKeyData(code = 'a'.code, label = "a"))
        key.visibleBounds.apply {
            left = 0f
            top = 0f
            right = 0f
            bottom = 0f
        }

        keyboard.getKeyForPos(
            pointerX = 55f,
            pointerY = 20f,
            boostedCodePoints = setOf('a'.code),
        ) shouldBe key
    }

    test("boost radius uses the shorter dimension of a non-square key") {
        val (keyboard, key) = singleKeyFixture(TextKeyData(code = 'a'.code, label = "a"))
        key.setTestBounds(0f, 0f, 100f, 20f)

        keyboard.getKeyForPos(
            pointerX = 50f,
            pointerY = 30f,
            boostedCodePoints = setOf('a'.code),
        ) shouldBe key
        keyboard.getKeyForPos(
            pointerX = 50f,
            pointerY = 30.01f,
            boostedCodePoints = setOf('a'.code),
        ).shouldBeNull()
    }

    test("gesture origins use visible keys while taps retain the wider touch target") {
        val (keyboard, key) = singleKeyFixture(TextKeyData(code = 'a'.code, label = "a"))
        key.visibleBounds.apply {
            left = 4f
            top = 4f
            right = 36f
            bottom = 36f
        }

        keyboard.getKeyForPos(38f, 20f) shouldBe key
        keyboard.getVisibleKeyForPos(38f, 20f).shouldBeNull()
        keyboard.getVisibleKeyForPos(20f, 20f) shouldBe key

        key.isEnabled = false
        keyboard.getVisibleKeyForPos(20f, 20f).shouldBeNull()
    }

    test("autocorrect layout snapshots include universal text inputs and actual visible geometry") {
        val letter = TextKey(TextKeyData(code = 'é'.code, label = "é"))
        val multiText = TextKey(
            MultiTextKeyData(codePoints = intArrayOf(2332, 2381, 2334), label = "ज्ञ"),
        )
        val number = TextKey(TextKeyData(type = KeyType.NUMERIC, code = '1'.code, label = "1"))
        val space = TextKey(TextKeyData.SPACE)
        val delete = TextKey(TextKeyData.DELETE)
        val disabled = TextKey(TextKeyData(code = 'x'.code, label = "x"))
        val zeroArea = TextKey(TextKeyData(code = 'z'.code, label = "z"))
        val keyboard = createTextKeyboard(
            KeyboardMode.CHARACTERS,
            arrayOf(arrayOf(letter, multiText, number, space, delete, disabled, zeroArea)),
        )
        letter.setTestBounds(0f, 0f, 40f, 40f)
        letter.visibleBounds.apply {
            left = 4f
            top = 8f
            right = 36f
            bottom = 32f
        }
        multiText.setTestBounds(40f, 0f, 80f, 40f)
        number.setTestBounds(80f, 0f, 100f, 40f)
        space.setTestBounds(0f, 40f, 40f, 80f)
        delete.setTestBounds(40f, 40f, 60f, 80f)
        disabled.setTestBounds(60f, 40f, 80f, 80f)
        disabled.isEnabled = false

        val snapshot = keyboard.snapshotAutocorrectInputLayout(width = 100f, height = 80f)

        snapshot.keys.map { it.text } shouldBe listOf("é", "ज्ञ", "1")
        snapshot.keys.first().let { geometry ->
            geometry.left shouldBe 0.04f
            geometry.top shouldBe 0.1f
            geometry.right shouldBe 0.36f
            geometry.bottom shouldBe 0.4f
        }
    }

    test("autocorrect layout snapshots stay immutable across layout changes") {
        val (keyboard, key) = singleKeyFixture(TextKeyData(code = 'a'.code, label = "a"))
        val original = keyboard.snapshotAutocorrectInputLayout(width = 100f, height = 100f)

        key.visibleBounds.left = 10f
        val updated = keyboard.snapshotAutocorrectInputLayout(width = 100f, height = 100f)

        original.keys.single().left shouldBe 0f
        updated.keys.single().left shouldBe 0.1f
    }
})

private data class HitTestFixture(
    val keyboard: TextKeyboard,
    val n: TextKey,
    val v: TextKey,
    val b: TextKey,
    val period: TextKey,
    val space: TextKey,
)

private fun hitTestFixture(mode: KeyboardMode = KeyboardMode.CHARACTERS): HitTestFixture {
    val v = TextKey(TextKeyData(code = 'v'.code, label = "v"))
    val b = TextKey(TextKeyData(code = 'b'.code, label = "b"))
    val n = TextKey(TextKeyData(code = 'n'.code, label = "n"))
    val period = TextKey(TextKeyData(code = '.'.code, label = "."))
    val space = TextKey(TextKeyData.SPACE)
    val keyboard = createTextKeyboard(
        mode = mode,
        arrangement = arrayOf(arrayOf(v, b, n, period), arrayOf(space)),
    )

    v.setTestBounds(0f, 0f, 40f, 40f)
    b.setTestBounds(40f, 0f, 80f, 40f)
    n.setTestBounds(80f, 0f, 120f, 40f)
    period.setTestBounds(120f, 0f, 160f, 40f)
    space.setTestBounds(0f, 50f, 160f, 90f)
    return HitTestFixture(keyboard, n, v, b, period, space)
}

private fun keyboardResultAtSharedEdge(fixture: HitTestFixture): TextKey? {
    return fixture.keyboard.getKeyForPos(
        pointerX = 39f,
        pointerY = 20f,
        boostedCodePoints = setOf('b'.code),
    )
}

private fun singleKeyFixture(data: KeyData): Pair<TextKeyboard, TextKey> {
    val key = TextKey(data)
    val keyboard = textKeyboard(KeyboardMode.CHARACTERS, key)
    key.setTestBounds(0f, 0f, 40f, 40f)
    return keyboard to key
}

private fun textKeyboard(
    mode: KeyboardMode,
    vararg keys: TextKey,
): TextKeyboard {
    return createTextKeyboard(mode, arrayOf(keys.map { it }.toTypedArray()))
}
private fun createTextKeyboard(
    mode: KeyboardMode,
    arrangement: Array<Array<TextKey>>,
): TextKeyboard {
    return TextKeyboard(
        arrangement = arrangement,
        mode = mode,
        extendedPopupMapping = null,
        extendedPopupMappingDefault = null,
    ).also { keyboard ->
        val evaluator = object : ComputingEvaluator by DefaultComputingEvaluator {
            override val keyboard = keyboard
        }
        keyboard.keys().forEach { it.compute(evaluator) }
    }
}

private fun textKeyData(code: Int): TextKeyData {
    return TextKeyData(
        code = code,
        label = String(Character.toChars(code)),
    )
}

internal fun TextKey.setTestBounds(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
) {
    touchBounds.apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
    visibleBounds.applyFrom(touchBounds)
}
