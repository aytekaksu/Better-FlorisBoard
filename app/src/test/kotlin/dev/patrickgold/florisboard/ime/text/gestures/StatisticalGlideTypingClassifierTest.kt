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

package dev.patrickgold.florisboard.ime.text.gestures

import androidx.collection.SparseArrayCompat
import androidx.collection.set
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.lib.FlorisLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.text.Normalizer

class StatisticalGlideTypingClassifierTest : FunSpec({
    test("dedicated accented keys are indexed by their actual code points") {
        val accentedCodePoint = 'é'.code
        val lastCodePoint = 't'.code
        val accentedKey = GlideTypingKey(accentedCodePoint, 0f, 0f, 40f, 40f, "é")
        val lastKey = GlideTypingKey(lastCodePoint, 80f, 0f, 120f, 40f, "t")
        val keysByCharacter = SparseArrayCompat<GlideTypingKey>().apply {
            this[accentedCodePoint] = accentedKey
            this[lastCodePoint] = lastKey
        }
        val word = "ét"
        val userGesture = StatisticalGlideTypingClassifier.Gesture().apply {
            addPoint(accentedKey.centerX, accentedKey.centerY)
            addPoint(lastKey.centerX, lastKey.centerY)
        }
        val pruner = StatisticalGlideTypingClassifier.Pruner(
            lengthThreshold = 8.42,
            words = listOf(word),
            keysByCharacter = keysByCharacter,
        )

        pruner.pruneByExtremities(userGesture, listOf(accentedKey, lastKey)) shouldContain word
    }

    test("accented word endpoints fall back to their base keys") {
        val firstKey = GlideTypingKey('e'.code, 0f, 0f, 40f, 40f, "e")
        val lastKey = GlideTypingKey('t'.code, 80f, 0f, 120f, 40f, "t")
        val keysByCharacter = SparseArrayCompat<GlideTypingKey>().apply {
            this['e'.code] = firstKey
            this['t'.code] = lastKey
        }
        val userGesture = StatisticalGlideTypingClassifier.Gesture().apply {
            addPoint(firstKey.centerX, firstKey.centerY)
            addPoint(lastKey.centerX, lastKey.centerY)
        }
        val pruner = StatisticalGlideTypingClassifier.Pruner(
            lengthThreshold = 8.42,
            words = listOf("ét"),
            keysByCharacter = keysByCharacter,
        )

        pruner.pruneByExtremities(userGesture, listOf(firstKey, lastKey)) shouldContain "ét"
    }

    test("NFD accented words use NFC geometry without changing the candidate spelling") {
        val firstKey = GlideTypingKey('c'.code, 0f, 0f, 40f, 40f, "c")
        val middleKey = GlideTypingKey('a'.code, 40f, 0f, 80f, 40f, "a")
        val fKey = GlideTypingKey('f'.code, 40f, 0f, 80f, 40f, "f")
        val lastKey = GlideTypingKey('e'.code, 80f, 0f, 120f, 40f, "e")
        val keysByCharacter = SparseArrayCompat<GlideTypingKey>().apply {
            this['c'.code] = firstKey
            this['a'.code] = middleKey
            this['f'.code] = fKey
            this['e'.code] = lastKey
        }
        val nfdWord = Normalizer.normalize("café", Normalizer.Form.NFD)
        val userGesture = StatisticalGlideTypingClassifier.Gesture().apply {
            addPoint(firstKey.centerX, firstKey.centerY)
            addPoint(middleKey.centerX, middleKey.centerY)
            addPoint(lastKey.centerX, lastKey.centerY)
        }
        val pruner = StatisticalGlideTypingClassifier.Pruner(
            lengthThreshold = 8.42,
            words = listOf(nfdWord),
            keysByCharacter = keysByCharacter,
        )

        pruner.pruneByExtremities(
            userGesture,
            listOf(firstKey, middleKey, lastKey),
        ).single() shouldBe nfdWord
        StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures(nfdWord, keysByCharacter)
            .single()
            .getLastX() shouldBe lastKey.centerX
    }

    test("supplementary-plane letters retain full code points") {
        val firstUpper = 0x10400
        val lastUpper = 0x10401
        val firstLower = Character.toLowerCase(firstUpper)
        val lastLower = Character.toLowerCase(lastUpper)
        val firstKey = GlideTypingKey(
            firstLower,
            0f,
            0f,
            40f,
            40f,
            String(Character.toChars(firstLower)),
        )
        val lastKey = GlideTypingKey(
            lastLower,
            80f,
            0f,
            120f,
            40f,
            String(Character.toChars(lastLower)),
        )
        val keysByCharacter = SparseArrayCompat<GlideTypingKey>().apply {
            this[firstLower] = firstKey
            this[lastLower] = lastKey
        }
        val word = String(Character.toChars(firstUpper)) + String(Character.toChars(lastUpper))

        val ideal = StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures(word, keysByCharacter)
            .single()
        ideal.getFirstX() shouldBe firstKey.centerX
        ideal.getLastX() shouldBe lastKey.centerX

        val userGesture = StatisticalGlideTypingClassifier.Gesture().apply {
            addPoint(firstKey.centerX, firstKey.centerY)
            addPoint(lastKey.centerX, lastKey.centerY)
        }
        val pruner = StatisticalGlideTypingClassifier.Pruner(
            lengthThreshold = 8.42,
            words = listOf(word),
            keysByCharacter = keysByCharacter,
        )
        pruner.pruneByExtremities(userGesture, listOf(firstKey, lastKey)) shouldContain word
    }

    test("Greek final sigma words use the ordinary sigma key") {
        val firstKey = GlideTypingKey('α'.code, 0f, 0f, 40f, 40f, "α")
        val sigmaKey = GlideTypingKey('σ'.code, 80f, 0f, 120f, 40f, "σ")
        val keysByCharacter = SparseArrayCompat<GlideTypingKey>().apply {
            this['α'.code] = firstKey
            this['σ'.code] = sigmaKey
        }
        val word = "ας"
        val userGesture = StatisticalGlideTypingClassifier.Gesture().apply {
            addPoint(firstKey.centerX, firstKey.centerY)
            addPoint(sigmaKey.centerX, sigmaKey.centerY)
        }
        val pruner = StatisticalGlideTypingClassifier.Pruner(
            lengthThreshold = 8.42,
            words = listOf(word),
            keysByCharacter = keysByCharacter,
        )

        pruner.pruneByExtremities(userGesture, listOf(firstKey, sigmaKey)) shouldContain word
        StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures(word, keysByCharacter)
            .single()
            .getLastX() shouldBe sigmaKey.centerX
    }

    test("overlapping Kurdish-style key outputs keep every valid physical-key geometry") {
        val lamKey = GlideTypingKey(1, 0f, 0f, 40f, 40f, output = "ڵ")
        val alefKey = GlideTypingKey(2, 40f, 0f, 80f, 40f, output = "ا")
        val lamAlefKey = GlideTypingKey(3, 80f, 0f, 120f, 40f, output = "ڵا")
        val wawKey = GlideTypingKey(4, 120f, 0f, 160f, 40f, output = "و")
        val keys = listOf(lamKey, alefKey, lamAlefKey, wawKey)
        val keyIndex = StatisticalGlideTypingClassifier.buildKeyIndex(keys, Subtype.DEFAULT)

        val idealEndpoints = StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures("ڵاو", keyIndex)
            .map { it.getFirstX() to it.getLastX() }
            .toSet()

        idealEndpoints shouldBe setOf(
            lamAlefKey.centerX to wawKey.centerX,
            lamKey.centerX to wawKey.centerX,
        )
        StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures("ڵـاو", keyIndex) shouldBe emptyList()
        val userGesture = StatisticalGlideTypingClassifier.Gesture().apply {
            addPoint(lamAlefKey.centerX, lamAlefKey.centerY)
            addPoint(wawKey.centerX, wawKey.centerY)
        }
        StatisticalGlideTypingClassifier.Pruner(
            lengthThreshold = 8.42,
            words = listOf("ڵاو"),
            keyIndex = keyIndex,
        ).pruneByExtremities(userGesture, keys) shouldContain "ڵاو"
    }

    test("direct Greek sigma keys outrank ambiguous synthetic uppercase aliases") {
        val alphaKey = GlideTypingKey(1, 0f, 0f, 40f, 40f, output = "α")
        val sigmaKey = GlideTypingKey(2, 40f, 0f, 80f, 40f, output = "σ")
        val finalSigmaKey = GlideTypingKey(3, 80f, 0f, 120f, 40f, output = "ς")
        val keyIndex = StatisticalGlideTypingClassifier.buildKeyIndex(
            listOf(alphaKey, sigmaKey, finalSigmaKey),
            Subtype.DEFAULT,
        )

        StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures("ας", keyIndex)
            .single()
            .getLastX() shouldBe finalSigmaKey.centerX
        StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures("ΑΣ", keyIndex)
            .single()
            .getLastX() shouldBe sigmaKey.centerX
    }

    test("unambiguous locale aliases retain Turkish dotted and dotless key identity") {
        val dottedKey = GlideTypingKey(1, 0f, 0f, 40f, 40f, output = "i")
        val dotlessKey = GlideTypingKey(2, 40f, 0f, 80f, 40f, output = "ı")
        val subtype = Subtype.DEFAULT.copy(primaryLocale = FlorisLocale.from("tr", "TR"))
        val keyIndex = StatisticalGlideTypingClassifier.buildKeyIndex(
            listOf(dottedKey, dotlessKey),
            subtype,
        )

        keyIndex.keyForCodePoint('İ'.code) shouldBe dottedKey
        keyIndex.keyForCodePoint('I'.code) shouldBe dotlessKey
    }

    test("geometry bridges punctuation but rejects unavailable letters") {
        val dKey = GlideTypingKey(1, 0f, 0f, 40f, 40f, output = "d")
        val oKey = GlideTypingKey(2, 40f, 0f, 80f, 40f, output = "o")
        val nKey = GlideTypingKey(3, 80f, 0f, 120f, 40f, output = "n")
        val tKey = GlideTypingKey(4, 120f, 0f, 160f, 40f, output = "t")
        val keyIndex = StatisticalGlideTypingClassifier.buildKeyIndex(
            listOf(dKey, oKey, nKey, tKey),
            Subtype.DEFAULT,
        )

        StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures("don't", keyIndex)
            .single()
            .getLastX() shouldBe tKey.centerX
        StatisticalGlideTypingClassifier.Gesture
            .generateIdealGestures("doжnt", keyIndex) shouldBe emptyList()
    }
})
