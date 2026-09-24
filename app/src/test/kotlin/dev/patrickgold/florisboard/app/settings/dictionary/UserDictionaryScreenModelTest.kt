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

package dev.patrickgold.florisboard.app.settings.dictionary

import androidx.lifecycle.ViewModelStore
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryDao
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryDatabase
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.lib.FlorisLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class UserDictionaryScreenModelTest : FunSpec({
    test("database lookup, DAO calls, and locale sorting use the I/O dispatcher") {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val io = QueuedIoDispatcher()
                val dao = FakeDao(io).apply {
                    entries += entry(1, "bonjour", "fr")
                    entries += entry(2, "hello", "en")
                    entries += entry(3, "universal")
                }
                val model = model(io, dao)

                drain(io)
                model.state.loading shouldBe false
                model.state.languages shouldBe listOf(
                    AllLanguagesLocale,
                    FlorisLocale.fromTag("en"),
                    FlorisLocale.fromTag("fr"),
                )

                model.selectLocale(AllLanguagesLocale)
                drain(io)
                model.state.words.map { it.word } shouldBe listOf("universal")
                dao.queriedLocales.last() shouldBe null
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("a completed old read cannot replace a newer locale selection") {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val io = QueuedIoDispatcher()
                val dao = FakeDao(io).apply {
                    entries += entry(1, "hello", "en")
                    entries += entry(2, "bonjour", "fr")
                }
                val model = model(io, dao)
                drain(io)

                model.selectLocale(FlorisLocale.fromTag("en"))
                runCurrent()
                io.runAll() // The English result is ready, but has not reached the UI yet.
                model.selectLocale(FlorisLocale.fromTag("fr"))
                drain(io)

                model.state.currentLocale shouldBe FlorisLocale.fromTag("fr")
                model.state.words.map { it.word } shouldBe listOf("bonjour")
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("a second write is rejected synchronously, then the list refreshes") {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val io = QueuedIoDispatcher()
                val dao = FakeDao(io)
                val model = model(io, dao)
                drain(io)

                model.save(entry(0, "first"), isAdd = true) shouldBe true
                model.state.busy shouldBe true
                model.save(entry(0, "second"), isAdd = true) shouldBe false
                drain(io)

                dao.entries.map { it.word } shouldBe listOf("first")
                model.state.busy shouldBe false
                model.state.languages shouldBe listOf(AllLanguagesLocale)
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("locale selection during a write waits for the completed write") {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val io = QueuedIoDispatcher()
                val dao = FakeDao(io)
                val model = model(io, dao)
                drain(io)

                model.save(entry(0, "bonjour", "fr"), isAdd = true) shouldBe true
                model.selectLocale(FlorisLocale.fromTag("fr"))
                model.state.currentLocale shouldBe FlorisLocale.fromTag("fr")
                model.state.loading shouldBe true
                runCurrent()
                io.pendingCount shouldBe 1 // Selection did not start a read during the write.

                drain(io)
                model.state.currentLocale shouldBe FlorisLocale.fromTag("fr")
                model.state.words.map { it.word } shouldBe listOf("bonjour")
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("a failed write reports a dismissible notice and releases busy state") {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val io = QueuedIoDispatcher()
                val dao = FakeDao(io).apply { insertFailure = IllegalStateException("write failed") }
                val model = model(io, dao)
                drain(io)

                model.save(entry(0, "word"), isAdd = true) shouldBe true
                drain(io)
                model.state.busy shouldBe false
                model.state.loading shouldBe false
                model.state.notice shouldBe UserDictionaryNotice.Failure("write failed")

                model.clearNotice(UserDictionaryNotice.Failure("write failed"))
                model.state.notice shouldBe null
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("a failed read ends loading and reports the error") {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val io = QueuedIoDispatcher()
                val dao = FakeDao(io).apply { queryFailure = IllegalStateException("read failed") }
                val model = model(io, dao)
                drain(io)

                model.state.loading shouldBe false
                model.state.notice shouldBe UserDictionaryNotice.Failure("read failed")
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("deleting the last word returns to the language list") {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val io = QueuedIoDispatcher()
                val word = entry(1, "hello", "en")
                val dao = FakeDao(io).apply { entries += word }
                val model = model(io, dao)
                drain(io)
                model.selectLocale(FlorisLocale.fromTag("en"))
                drain(io)

                model.delete(word) shouldBe true
                drain(io)
                model.state.currentLocale shouldBe null
                model.state.languages shouldBe emptyList()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    test("an accepted write completes after the route ViewModel is cleared") {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val io = QueuedIoDispatcher()
                val dao = FakeDao(io)
                val model = model(io, dao)
                val store = ViewModelStore().apply { put("dictionary", model) }
                drain(io)

                model.save(entry(0, "persist"), isAdd = true) shouldBe true
                store.clear()
                drain(io)

                dao.entries.map { it.word } shouldBe listOf("persist")
                model.state.notice shouldBe null
            } finally {
                Dispatchers.resetMain()
            }
        }
    }
})

private fun model(io: QueuedIoDispatcher, dao: FakeDao): UserDictionaryScreenModel {
    return UserDictionaryScreenModel(
        database = {
            check(io.running) { "Database must be acquired on the I/O dispatcher" }
            object : UserDictionaryDatabase {
                override fun userDictionaryDao(): UserDictionaryDao {
                    check(io.running) { "DAO must be acquired on the I/O dispatcher" }
                    return dao
                }
            }
        },
        context = { error("Context is not needed by DAO operations") },
        ioDispatcher = io,
    )
}

private fun entry(id: Long, word: String, locale: String? = null): UserDictionaryEntry {
    return UserDictionaryEntry(id = id, word = word, freq = 255, locale = locale, shortcut = null)
}

private class FakeDao(private val io: QueuedIoDispatcher) : UserDictionaryDao {
    val entries = mutableListOf<UserDictionaryEntry>()
    val queriedLocales = mutableListOf<FlorisLocale?>()
    var insertFailure: Exception? = null
    var queryFailure: Exception? = null
    private var nextId = 10L

    private fun requireIo() = check(io.running) { "DAO work must use the I/O dispatcher" }

    override fun queryAll(): List<UserDictionaryEntry> {
        requireIo()
        return entries.toList()
    }

    override fun queryAll(locale: FlorisLocale?): List<UserDictionaryEntry> {
        requireIo()
        queriedLocales += locale
        return entries.filter { it.locale?.let { tag -> FlorisLocale.fromTag(tag) } == locale }
    }

    override fun queryExact(word: String, locale: FlorisLocale?): List<UserDictionaryEntry> {
        requireIo()
        return entries.filter { it.word == word && it.locale?.let { tag -> FlorisLocale.fromTag(tag) } == locale }
    }

    override fun queryLanguageList(): List<FlorisLocale?> {
        requireIo()
        queryFailure?.let { throw it }
        return entries.map { it.locale?.let { tag -> FlorisLocale.fromTag(tag) } }.distinct()
    }

    override fun insert(entry: UserDictionaryEntry): Long {
        requireIo()
        insertFailure?.let { throw it }
        val id = nextId++
        entries += entry.copy(id = id)
        return id
    }

    override fun update(entry: UserDictionaryEntry): Int {
        requireIo()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index < 0) return 0
        entries[index] = entry
        return 1
    }

    override fun delete(entry: UserDictionaryEntry): Int {
        requireIo()
        return if (entries.remove(entry)) 1 else 0
    }

    override fun deleteAll() {
        requireIo()
        entries.clear()
    }
}

private class QueuedIoDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()
    val pendingCount: Int get() = tasks.size
    var running = false
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) {
            val task = tasks.removeFirst()
            running = true
            try {
                task.run()
            } finally {
                running = false
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.drain(io: QueuedIoDispatcher) {
    repeat(3) {
        runCurrent()
        io.runAll()
    }
    runCurrent()
}
