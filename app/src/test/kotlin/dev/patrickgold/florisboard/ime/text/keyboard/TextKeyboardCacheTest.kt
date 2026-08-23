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

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

class TextKeyboardCacheTest :
    FunSpec({
        test("hash collisions retain distinct subtype entries") {
            val firstSubtype = Subtype.DEFAULT.copy(id = 0L)
            val secondSubtype = Subtype.DEFAULT.copy(id = 0x1_0000_0001L)
            val firstKeyboard = keyboard(KeyboardMode.CHARACTERS)
            val secondKeyboard = keyboard(KeyboardMode.CHARACTERS)
            val cache = TextKeyboardCache()

            (firstSubtype == secondSubtype) shouldBe false
            firstSubtype.hashCode() shouldBe secondSubtype.hashCode()
            cache.getOrPut(KeyboardMode.CHARACTERS, firstSubtype) { firstKeyboard }
            cache.getOrPut(KeyboardMode.CHARACTERS, secondSubtype) { secondKeyboard }
            cache.cached(KeyboardMode.CHARACTERS, firstSubtype) shouldBeSameInstanceAs firstKeyboard
            cache.cached(KeyboardMode.CHARACTERS, secondSubtype) shouldBeSameInstanceAs secondKeyboard
        }

        test("hits and clears stay scoped by keyboard mode") {
            val subtype = Subtype.DEFAULT
            val characters = keyboard(KeyboardMode.CHARACTERS)
            val symbols = keyboard(KeyboardMode.SYMBOLS)
            val cache = TextKeyboardCache()
            var characterLoads = 0

            cache.getOrPut(KeyboardMode.CHARACTERS, subtype) {
                characterLoads += 1
                characters
            }
            cache.cached(KeyboardMode.CHARACTERS, subtype) shouldBeSameInstanceAs characters
            cache.getOrPut(KeyboardMode.SYMBOLS, subtype) { symbols }
            characterLoads shouldBe 1

            cache.clear(KeyboardMode.CHARACTERS)
            val replacement = keyboard(KeyboardMode.CHARACTERS)
            cache.getOrPut(KeyboardMode.CHARACTERS, subtype) { replacement } shouldBeSameInstanceAs replacement
            cache.cached(KeyboardMode.SYMBOLS, subtype) shouldBeSameInstanceAs symbols

            cache.clear()
            val reloadedSymbols = keyboard(KeyboardMode.SYMBOLS)
            cache.getOrPut(KeyboardMode.SYMBOLS, subtype) { reloadedSymbols } shouldBeSameInstanceAs reloadedSymbols
        }

        test("misses inherit the caller job and failures are retried") {
            val subtype = Subtype.DEFAULT
            val cache = TextKeyboardCache()
            val callerJob = requireNotNull(currentCoroutineContext()[Job])
            var loaderJob: Job? = null
            var attempts = 0

            runCatching {
                cache.getOrPut(KeyboardMode.CHARACTERS, subtype) {
                    attempts += 1
                    loaderJob = currentCoroutineContext()[Job]
                    error("expected failure")
                }
            }.isFailure shouldBe true
            requireNotNull(loaderJob) shouldBeSameInstanceAs callerJob

            val recovered = keyboard(KeyboardMode.CHARACTERS)
            cache.getOrPut(KeyboardMode.CHARACTERS, subtype) {
                attempts += 1
                recovered
            } shouldBeSameInstanceAs recovered
            cache.cached(KeyboardMode.CHARACTERS, subtype) shouldBeSameInstanceAs recovered
            attempts shouldBe 2
        }
    })

private suspend fun TextKeyboardCache.cached(mode: KeyboardMode, subtype: Subtype) =
    getOrPut(mode, subtype) { error("unexpected cache miss") }

private fun keyboard(mode: KeyboardMode) = TextKeyboard(
    arrangement = emptyArray(),
    mode = mode,
    extendedPopupMapping = null,
    extendedPopupMappingDefault = null,
)
