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

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.UserDictionary
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.dictionaryManager
import dev.patrickgold.florisboard.lib.FlorisLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DictionaryManagerAndroidTest {
    @Test
    fun systemDictionaryQueriesKeepNullAndStoredLocaleSelections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val dao = SystemUserDictionaryDatabase(context).userDictionaryDao()
        val word = "system-query-${UUID.randomUUID()}"
        val locale = FlorisLocale.fromTag("en-US")
        val tag = locale.localeTag()
        val inserted = mutableListOf<Uri>()

        fun add(value: String, frequency: Int, storedLocale: String?): Long {
            val values = ContentValues().apply {
                put(UserDictionary.Words.WORD, value)
                put(UserDictionary.Words.FREQUENCY, frequency)
                put(UserDictionary.Words.LOCALE, storedLocale)
                put(UserDictionary.Words.APP_ID, 0)
            }
            val uri = requireNotNull(resolver.insert(UserDictionary.Words.CONTENT_URI, values))
            inserted += uri
            return ContentUris.parseId(uri)
        }

        try {
            val withoutLocale = add(word, 55, null)
            val canonical = add(word, 75, tag)
            val hyphenated = add(word, 65, tag.replace('_', '-'))
            val otherLocale = add(word, 45, "fr_FR")
            val otherWord = add("$word-other", 85, tag)
            val insertedIds = setOf(withoutLocale, canonical, hyphenated, otherLocale, otherWord)
            fun selectedIds(entries: List<UserDictionaryEntry>) = entries.map(UserDictionaryEntry::id)
                .filter { it in insertedIds }

            assertEquals(listOf(otherWord, canonical, hyphenated, withoutLocale, otherLocale), selectedIds(dao.queryAll()))
            assertEquals(listOf(withoutLocale), selectedIds(dao.queryAll(null)))
            assertEquals(listOf(otherWord, canonical), selectedIds(dao.queryAll(locale)))
            assertEquals(listOf(otherWord, canonical), selectedIds(dao.queryAllRaw(tag)))
            assertEquals(listOf(otherWord, canonical, hyphenated), selectedIds(dao.queryAllRawAliases(tag)))
            assertEquals(listOf(withoutLocale), selectedIds(dao.queryExact(word, null)))
            assertEquals(listOf(canonical), selectedIds(dao.queryExact(word, locale)))
            assertEquals(listOf(canonical), selectedIds(dao.queryExactRaw(word, tag)))
            assertEquals(listOf(canonical, hyphenated), selectedIds(dao.queryExactRawAliases(word, tag)))
        } finally {
            val failedDeletes = inserted.count { uri ->
                runCatching { resolver.delete(uri, null, null) }.getOrNull() != 1
            }
            assertEquals("Failed to remove synthetic system-dictionary rows", 0, failedDeletes)
        }
    }

    @Test
    fun florisDaoRejectsMainThreadQueries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dao = instrumentation.targetContext.dictionaryManager().value.florisUserDictionary.userDictionaryDao()

        instrumentation.runOnMainSync {
            assertThrows(IllegalStateException::class.java) {
                dao.queryAll()
            }
        }
    }

    @Test
    fun florisDictionaryImportExportRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, FlorisUserDictionaryDatabase::class.java).build()
        val source = File.createTempFile("floris-dictionary-import-", ".txt", context.cacheDir)
        val exported = File.createTempFile("floris-dictionary-export-", ".txt", context.cacheDir)
        val word = "dictionary-roundtrip-${UUID.randomUUID()}"
        val locale = FlorisLocale.fromTag("en-US")
        val importedLine = " w=$word;f=173;l=en-US;s=dr"
        val exportedLine = " w=$word;f=173;l=${locale.localeTag()};s=dr"

        try {
            source.writeText("dictionary=test;version=1\n$importedLine\n")
            database.importCombinedList(context, Uri.fromFile(source))

            val dao = database.userDictionaryDao()
            val imported = dao.queryAll(locale).single()
            assertEquals(word, imported.word)
            assertEquals(173, imported.freq)
            assertEquals(locale.localeTag(), imported.locale)
            assertEquals("dr", imported.shortcut)
            assertEquals(listOf(imported), dao.queryExact(word, locale))

            database.exportCombinedList(context, Uri.fromFile(exported))
            val exportedLines = exported.readLines()
            assertEquals(2, exportedLines.size)
            assertEquals(exportedLine, exportedLines[1])

            source.writeText("dictionary=test;version=1\n w=$word;f=211;l=en-US;s=dr\n")
            database.importCombinedList(context, Uri.fromFile(source))
            assertEquals(listOf(imported.copy(freq = 211)), dao.queryExact(word, locale))
            assertEquals(1, dao.queryAll().size)

            assertEquals(1, dao.delete(imported))
            database.importCombinedList(context, Uri.fromFile(exported))
            val restored = dao.queryExact(word, locale).single()
            assertEquals(imported.copy(id = restored.id), restored)
        } finally {
            database.close()
            source.delete()
            exported.delete()
        }
        assertFalse(source.exists())
        assertFalse(exported.exists())
    }

    @Test
    fun florisImportHealsLegacyLanguageTagInPlace() {
        withImportSource { context, database, source ->
            val dao = database.userDictionaryDao()
            val locale = FlorisLocale.fromTag("en-US")
            val word = "legacy-language-tag-${UUID.randomUUID()}"
            val legacy = UserDictionaryEntry(0, word, 42, "en-US", "old")
            val id = dao.insert(legacy)
            assertEquals(listOf(legacy.copy(id = id)), dao.queryAll(locale))

            source.writeText("dictionary=test;version=1\n w=$word;f=173;l=en-US;s=new\n")
            database.importCombinedList(context, Uri.fromFile(source))

            val healed = legacy.copy(id = id, freq = 173, locale = locale.localeTag(), shortcut = "new")
            assertEquals(listOf(healed), dao.queryExact(word, locale))
            assertEquals(listOf(healed), dao.queryAll())
        }
    }

    @Test
    fun florisImportHealsAliasSeparatorsWithoutMergingLanguageCodesOrExtendedTags() {
        withImportSource { context, database, source ->
            val dao = database.userDictionaryDao()
            val word = "legacy-alias-${UUID.randomUUID()}"
            val legacyTag = "iw-IL"
            val modernTag = "he-IL"
            val extendedTag = "iw-IL-u-ca-gregory"
            val legacy = UserDictionaryEntry(0, word, 42, legacyTag, "legacy")
            val modern = UserDictionaryEntry(0, word, 64, modernTag, "modern")
            val extended = UserDictionaryEntry(0, word, 73, extendedTag, "extended")
            val legacyId = dao.insert(legacy)
            val modernId = dao.insert(modern)
            val extendedId = dao.insert(extended)

            source.writeText("dictionary=test;version=1\n w=$word;f=173;l=$legacyTag;s=updated\n")
            database.importCombinedList(context, Uri.fromFile(source))
            source.writeText("dictionary=test;version=1\n w=$word;f=181;l=$modernTag;s=updated\n")
            database.importCombinedList(context, Uri.fromFile(source))

            val healedLegacy = legacy.copy(id = legacyId, freq = 173, locale = "iw_IL", shortcut = "updated")
            val healedModern = modern.copy(id = modernId, freq = 181, locale = "he_IL", shortcut = "updated")
            assertEquals(listOf(healedLegacy), dao.queryAllRawAliases("iw_IL"))
            assertEquals(listOf(healedModern), dao.queryAllRawAliases("he_IL"))
            assertEquals(listOf(extended.copy(id = extendedId)), dao.queryAllRaw(extendedTag))
            assertEquals(3, dao.queryAll().size)

            val exported = File.createTempFile("floris-dictionary-alias-", ".txt", context.cacheDir)
            try {
                database.exportCombinedList(context, Uri.fromFile(exported))
                val lines = exported.readLines()
                assertTrue(lines.any { it.contains(";l=iw_IL;") })
                assertTrue(lines.any { it.contains(";l=he_IL;") })
                assertTrue(lines.any { it.contains(";l=$extendedTag;") })
                database.importCombinedList(context, Uri.fromFile(exported))
                assertEquals(3, dao.queryAll().size)
                assertEquals(listOf(healedLegacy), dao.queryAllRawAliases("iw_IL"))
                assertEquals(listOf(healedModern), dao.queryAllRawAliases("he_IL"))
            } finally {
                exported.delete()
            }
        }
    }

    @Test
    fun florisImportPrefersCanonicalAliasRowAndPreservesLegacyDuplicate() {
        withImportSource { context, database, source ->
            val dao = database.userDictionaryDao()
            listOf(
                "iw" to "he", "he" to "iw", "in" to "id",
                "id" to "in", "ji" to "yi", "yi" to "ji",
            ).forEachIndexed { index, (code, otherCode) ->
                val word = "alias-duplicate-$index-${UUID.randomUUID()}"
                val caseOnly = UserDictionaryEntry(0, word, 35, "${code}_il", "case-only")
                val legacy = UserDictionaryEntry(0, word, 42, "$code-IL", "legacy")
                val other = UserDictionaryEntry(0, word, 64, "${otherCode}_IL", "other")
                val canonical = UserDictionaryEntry(0, word, 73, "${code}_IL", "canonical")
                val caseOnlyId = dao.insert(caseOnly)
                val legacyId = dao.insert(legacy)
                val otherId = dao.insert(other)
                val canonicalId = dao.insert(canonical)

                source.writeText("dictionary=test;version=1\n w=$word;f=211;l=$code-IL;s=updated\n")
                database.importCombinedList(context, Uri.fromFile(source))

                assertEquals(listOf(caseOnly.copy(id = caseOnlyId)), dao.queryExactRaw(word, "${code}_il"))
                assertEquals(listOf(legacy.copy(id = legacyId)), dao.queryExactRaw(word, "$code-IL"))
                assertEquals(listOf(other.copy(id = otherId)), dao.queryExactRaw(word, "${otherCode}_IL"))
                assertEquals(
                    listOf(canonical.copy(id = canonicalId, freq = 211, shortcut = "updated")),
                    dao.queryExactRaw(word, "${code}_IL"),
                )
            }
        }
    }

    @Test
    fun florisImportPrefersCanonicalRowWhenLegacyDuplicateExists() {
        withImportSource { context, database, source ->
            val dao = database.userDictionaryDao()
            val locale = FlorisLocale.fromTag("en-US")
            val word = "duplicate-language-tag-${UUID.randomUUID()}"
            val legacy = UserDictionaryEntry(0, word, 42, "en-US", "legacy")
            val canonical = UserDictionaryEntry(0, word, 73, locale.localeTag(), "canonical")
            val legacyId = dao.insert(legacy)
            val canonicalId = dao.insert(canonical)

            source.writeText("dictionary=test;version=1\n w=$word;f=173;l=en-US;s=updated\n")
            database.importCombinedList(context, Uri.fromFile(source))

            assertEquals(
                listOf(
                    canonical.copy(id = canonicalId, freq = 173, shortcut = "updated"),
                    legacy.copy(id = legacyId),
                ),
                dao.queryExact(word, locale),
            )
            assertEquals(2, dao.queryAll().size)
        }
    }

    @Test
    fun florisImportKeepsDistinctExtendedLocaleTags() {
        withImportSource { context, database, source ->
            val dao = database.userDictionaryDao()
            val word = "extended-language-tag-${UUID.randomUUID()}"
            val shorterTag = "en-US-oxendict"
            val longerTag = "$shorterTag-extra"
            val shorter = UserDictionaryEntry(0, word, 42, shorterTag, "shorter")
            val shorterId = dao.insert(shorter)

            source.writeText("dictionary=test;version=1\n w=$word;f=173;l=$longerTag;s=longer\n")
            database.importCombinedList(context, Uri.fromFile(source))

            val imported = dao.queryAll()
            assertEquals(2, imported.size)
            assertEquals(shorter.copy(id = shorterId), imported.single { it.locale == shorterTag })
            val longer = imported.single { it.locale == longerTag }
            assertEquals(173, longer.freq)
            assertEquals("longer", longer.shortcut)

            source.writeText("dictionary=test;version=1\n w=$word;f=211;l=$longerTag;s=updated\n")
            database.importCombinedList(context, Uri.fromFile(source))

            val reimported = dao.queryAll()
            assertEquals(2, reimported.size)
            assertEquals(shorter.copy(id = shorterId), reimported.single { it.locale == shorterTag })
            assertEquals(
                longer.copy(freq = 211, shortcut = "updated"),
                reimported.single { it.locale == longerTag },
            )
            assertEquals(setOf(shorterTag, longerTag), dao.queryLanguageList().toSet())
            assertEquals(listOf(shorter.copy(id = shorterId)), dao.queryAllRaw(shorterTag))
            assertEquals(listOf(longer.copy(freq = 211, shortcut = "updated")), dao.queryAllRaw(longerTag))

            val exported = File.createTempFile("floris-dictionary-extended-", ".txt", context.cacheDir)
            try {
                database.exportCombinedList(context, Uri.fromFile(exported))
                val lines = exported.readLines()
                assertTrue(lines.any { it.contains(";l=$shorterTag;") })
                assertTrue(lines.any { it.contains(";l=$longerTag;") })
            } finally {
                exported.delete()
            }
        }
    }

    @Test
    fun florisExportReimportKeepsMixedSeparatorVariantRowsExact() {
        withImportSource { context, database, source ->
            val dao = database.userDictionaryDao()
            val word = "mixed-separator-${UUID.randomUUID()}"
            val tags = listOf("en_US-POSIX", "en-US_POSIX")
            val entries = tags.mapIndexed { index, tag ->
                val entry = UserDictionaryEntry(0, word, 100 + index, tag, "mixed-$index")
                entry.copy(id = dao.insert(entry))
            }

            database.exportCombinedList(context, Uri.fromFile(source))
            database.importCombinedList(context, Uri.fromFile(source))

            assertEquals(2, dao.queryAll().size)
            entries.forEach { entry ->
                assertEquals(listOf(entry), dao.queryExactRaw(word, entry.locale!!))
            }
            assertTrue(dao.queryExactRaw(word, "en_US_POSIX").isEmpty())
        }
    }

    @Test
    fun florisRawLocaleQueriesKeepScriptExtensionAndMalformedTagsSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, FlorisUserDictionaryDatabase::class.java).build()
        try {
            val dao = database.userDictionaryDao()
            val tags = listOf(
                "sr-Latn-RS", "sr-Latn-RS-u-ca-gregory", "sr-Latn-RS-", "sr-latn-RS",
                "iw", "all", "ALL", "null", "NULL",
            )
            val entries = tags.mapIndexed { index, tag ->
                val entry = UserDictionaryEntry(0, "locale-choice-$index", 100, tag, null)
                entry.copy(id = dao.insert(entry))
            }
            assertEquals(tags.toSet(), dao.queryLanguageList().toSet())
            entries.forEach { entry ->
                assertEquals(listOf(entry), dao.queryAllRaw(entry.locale!!))
            }
            assertTrue(dao.queryAllRaw("sr-Latn").isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun malformedFlorisLocaleImportStaysBrowsableByItsStoredTag() {
        withImportSource { context, database, source ->
            val tag = "sr-Latn-RS-"
            source.writeText("dictionary=test;version=1\n w=malformed-locale;f=173;l=$tag\n")

            database.importCombinedList(context, Uri.fromFile(source))

            val dao = database.userDictionaryDao()
            val imported = dao.queryAllRaw(tag).single()
            assertEquals(tag, imported.locale)
            assertEquals(listOf(tag), dao.queryLanguageList())
            assertEquals(listOf(imported), dao.queryExactRaw(imported.word, tag))
        }
    }

    @Test
    fun malformedFlorisImportStillFailsWithoutInsertingEntry() {
        withImportSource { context, database, source ->
            source.writeText("dictionary=test;version=1\n w=bad-frequency;f=not-a-number;l=en-US\n")

            assertThrows(IllegalStateException::class.java) {
                database.importCombinedList(context, Uri.fromFile(source))
            }
            assertTrue(database.userDictionaryDao().queryAll().isEmpty())
        }
    }

    @Test
    fun applicationContextsShareThreadSafeDictionaryStoresAndData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wrappedContext = ContextWrapper(context)
        val executor = Executors.newFixedThreadPool(CALLER_COUNT)
        val callersReady = CountDownLatch(CALLER_COUNT)
        val start = CountDownLatch(1)
        val calls = List(CALLER_COUNT) { index ->
            executor.submit(Callable {
                callersReady.countDown()
                start.await()
                val callerContext = if (index % 2 == 0) context else wrappedContext
                val manager = callerContext.dictionaryManager().value
                Triple(
                    manager,
                    manager.florisUserDictionary,
                    manager.systemUserDictionary,
                )
            })
        }

        val stores = try {
            assertTrue(callersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            start.countDown()
            calls.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }

        val shared = stores.first()
        stores.forEach { observed ->
            assertSame(shared.first, observed.first)
            assertSame(shared.second, observed.second)
            assertSame(shared.third, observed.third)
        }
        assertSame(shared.first, context.dictionaryManager().value)
        assertSame(shared.first, wrappedContext.dictionaryManager().value)

        val dao = shared.second.userDictionaryDao()
        val locale = FlorisLocale.fromTag("en-US")
        val word = "dictionary-lifecycle-${UUID.randomUUID()}"
        var persistedEntry: UserDictionaryEntry? = null
        try {
            val entry = UserDictionaryEntry(
                id = 0,
                word = word,
                freq = 173,
                locale = locale.localeTag(),
                shortcut = null,
            )
            val id = dao.insert(entry)
            assertTrue(id > 0)
            val persisted = entry.copy(id = id)
            persistedEntry = persisted

            assertEquals(listOf(persisted), dao.queryExact(word, locale))
            assertEquals(1, dao.delete(persisted))
            persistedEntry = null
            assertTrue(dao.queryExact(word, locale).isEmpty())
        } finally {
            persistedEntry?.let { dao.delete(it) }
        }
    }

    private companion object {
        const val CALLER_COUNT = 8
        const val TIMEOUT_SECONDS = 10L
    }

    private fun withImportSource(block: (Context, FlorisUserDictionaryDatabase, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, FlorisUserDictionaryDatabase::class.java).build()
        val source = File.createTempFile("floris-dictionary-import-", ".txt", context.cacheDir)
        try {
            block(context, database, source)
        } finally {
            database.close()
            source.delete()
        }
    }
}
