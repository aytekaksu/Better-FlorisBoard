/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import android.icu.text.BreakIterator
import dev.patrickgold.florisboard.lib.FlorisLocale
import java.util.concurrent.ConcurrentHashMap

class BreakIteratorGroup {
    private val charInstances = ConcurrentHashMap<FlorisLocale, BreakIterator>()

    private val wordInstances = ConcurrentHashMap<FlorisLocale, BreakIterator>()

    fun <R> character(locale: FlorisLocale, action: (BreakIterator) -> R): R {
        val instance = charInstances.computeIfAbsent(locale) {
            BreakIterator.getCharacterInstance(it.base)
        }
        return synchronized(instance) { action(instance) }
    }

    fun <R> word(locale: FlorisLocale, action: (BreakIterator) -> R): R {
        val instance = wordInstances.computeIfAbsent(locale) {
            BreakIterator.getWordInstance(it.base)
        }
        return synchronized(instance) { action(instance) }
    }

    fun measureUChars(text: String, numUnicodeChars: Int, locale: FlorisLocale = FlorisLocale.default()): Int =
        character(locale) {
            it.setText(text)
            val start = it.first()
            var end: Int
            var n = 0
            do {
                end = it.next()
            } while (end != BreakIterator.DONE && ++n < numUnicodeChars)
            (if (end == BreakIterator.DONE) text.length else end) - start
        }.coerceIn(0, text.length)

    fun measureLastUChars(text: String, numUnicodeChars: Int, locale: FlorisLocale = FlorisLocale.default()): Int =
        character(locale) {
            it.setText(text)
            val end = it.last()
            var start: Int
            var n = 0
            do {
                start = it.previous()
            } while (start != BreakIterator.DONE && ++n < numUnicodeChars)
            end - (if (start == BreakIterator.DONE) 0 else start)
        }.coerceIn(0, text.length)

    fun measureUWords(text: String, numUnicodeWords: Int, locale: FlorisLocale = FlorisLocale.default()): Int =
        word(locale) {
            it.setText(text)
            val start = it.first()
            var end: Int
            var n = 0
            do {
                end = it.next()
                if (it.ruleStatus != BreakIterator.WORD_NONE) n++
            } while (end != BreakIterator.DONE && n < numUnicodeWords)
            (if (end == BreakIterator.DONE) text.length else end) - start
        }.coerceIn(0, text.length)

    fun measureLastUWords(text: String, numUnicodeWords: Int, locale: FlorisLocale = FlorisLocale.default()): Int =
        word(locale) {
            it.setText(text)
            val end = it.last()
            var start: Int
            var n = 0
            do {
                if (it.ruleStatus != BreakIterator.WORD_NONE) n++
                start = it.previous()
            } while (start != BreakIterator.DONE && n < numUnicodeWords)
            end - (if (start == BreakIterator.DONE) 0 else start)
        }.coerceIn(0, text.length)
}
