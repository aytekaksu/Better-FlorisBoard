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

import android.content.ContextWrapper
import android.net.Uri
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
        val expectedLine = " w=$word;f=173;l=${locale.localeTag()};s=dr"

        try {
            source.writeText("dictionary=test;version=1\n$expectedLine\n")
            database.importCombinedList(context, Uri.fromFile(source))

            val imported = database.userDictionaryDao().queryExact(word, locale).single()
            assertEquals(word, imported.word)
            assertEquals(173, imported.freq)
            assertEquals(locale.localeTag(), imported.locale)
            assertEquals("dr", imported.shortcut)

            database.exportCombinedList(context, Uri.fromFile(exported))
            val exportedLines = exported.readLines()
            assertEquals(2, exportedLines.size)
            assertEquals(expectedLine, exportedLines[1])

            assertEquals(1, database.userDictionaryDao().delete(imported))
            database.importCombinedList(context, Uri.fromFile(exported))
            val restored = database.userDictionaryDao().queryExact(word, locale).single()
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
}
