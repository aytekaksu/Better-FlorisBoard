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
        withMainTestDispatcher {
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
                UserDictionaryLocaleChoice.All,
                standard("en"),
                standard("fr"),
            )

            model.selectLocale(UserDictionaryLocaleChoice.All)
            drain(io)
            model.state.words.map { it.word } shouldBe listOf("universal")
            dao.queriedLocales.last() shouldBe null
        }
    }

    test("legacy and canonical locale strings show one language choice") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val dao = FakeDao(io).apply {
                entries += entry(1, "canonical", "en_US")
                entries += entry(2, "legacy", "en-US")
            }
            val model = model(io, dao)
            drain(io)

            model.state.languages shouldBe listOf(standard("en-US"))
            model.selectLocale(standard("en-US"))
            drain(io)
            model.state.words.map { it.word } shouldBe listOf("canonical", "legacy")
        }
    }

    test("extended and malformed Floris tags stay distinct selectable raw choices") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val script = "sr-Latn-RS"
            val extension = "sr-Latn-RS-u-ca-gregory"
            val malformed = "sr-Latn-RS-"
            val legacyAlias = "iw"
            val dao = FakeDao(io).apply {
                entries += entry(1, "same", script)
                entries += entry(2, "same", extension)
                entries += entry(3, "same", malformed)
                entries += entry(4, "simple", "en-US")
                entries += entry(5, "simple", "en_US")
                entries += entry(6, "alias", legacyAlias)
            }
            val model = model(io, dao)
            drain(io)

            model.state.languages shouldBe listOf(
                standard("en-US"),
                UserDictionaryLocaleChoice.RawAliases(legacyAlias),
                UserDictionaryLocaleChoice.Exact(script),
                UserDictionaryLocaleChoice.Exact(malformed),
                UserDictionaryLocaleChoice.Exact(extension),
            )
            listOf(script to 1L, extension to 2L, malformed to 3L).forEach { (tag, id) ->
                model.selectLocale(UserDictionaryLocaleChoice.Exact(tag))
                drain(io)
                model.state.currentLocale shouldBe UserDictionaryLocaleChoice.Exact(tag)
                model.state.words.map { it.id } shouldBe listOf(id)
                model.selectLocale(null)
                drain(io)
            }
            model.selectLocale(UserDictionaryLocaleChoice.RawAliases(legacyAlias))
            drain(io)
            model.state.words.map { it.id } shouldBe listOf(6L)
            dao.queriedRawTags shouldBe listOf(script, extension, malformed)
            dao.queriedRawAliasTags shouldBe listOf(legacyAlias)
        }
    }

    test("platform-dependent language codes group only their own simple spellings") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val extended = "iw-IL-u-ca-gregory"
            val mixed = "iw_IL-POSIX"
            val dao = FakeDao(io).apply {
                entries += entry(1, "same", "iw_IL")
                entries += entry(2, "same", "iw-IL")
                entries += entry(3, "same", "he_IL")
                entries += entry(4, "same", "he-IL")
                entries += entry(5, "same", extended)
                entries += entry(6, "same", mixed)
            }
            val model = model(io, dao)
            drain(io)

            model.state.languages.toSet() shouldBe setOf(
                UserDictionaryLocaleChoice.RawAliases("iw_IL"),
                UserDictionaryLocaleChoice.RawAliases("he_IL"),
                UserDictionaryLocaleChoice.Exact(extended),
                UserDictionaryLocaleChoice.Exact(mixed),
            )
            model.state.languages.size shouldBe 4
            model.selectLocale(UserDictionaryLocaleChoice.RawAliases("iw_IL"))
            drain(io)
            model.state.words.map { it.id } shouldBe listOf(1L, 2L)
            model.selectLocale(UserDictionaryLocaleChoice.RawAliases("he_IL"))
            drain(io)
            model.state.words.map { it.id } shouldBe listOf(3L, 4L)
            model.selectLocale(UserDictionaryLocaleChoice.Exact(extended))
            drain(io)
            model.state.words.map { it.id } shouldBe listOf(5L)
            model.selectLocale(UserDictionaryLocaleChoice.Exact(mixed))
            drain(io)
            model.state.words.map { it.id } shouldBe listOf(6L)
            dao.queriedRawAliasTags shouldBe listOf("iw_IL", "he_IL")
            dao.queriedRawTags shouldBe listOf(extended, mixed)
        }
    }

    test("system dictionary keeps its parsed language choices") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val dao = FakeDao(io).apply { entries += entry(1, "system", "sr-Latn-RS") }
            val model = model(io, dao, UserDictionaryType.SYSTEM)
            drain(io)

            model.state.languages shouldBe listOf(standard("sr-Latn-RS"))
            model.selectLocale(standard("sr-Latn-RS"))
            drain(io)
            dao.queriedLocales.last() shouldBe FlorisLocale.fromTag("sr-Latn-RS")
            dao.queriedRawTags shouldBe emptyList()
        }
    }

    test("reserved-looking raw tags never overlap the all-languages choice") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val tags = listOf("all", "ALL", "null", "NULL")
            val dao = FakeDao(io).apply {
                tags.forEachIndexed { index, tag -> entries += entry(index + 1L, "same", tag) }
            }
            val model = model(io, dao)
            drain(io)

            model.state.languages shouldBe tags.sorted().map { UserDictionaryLocaleChoice.Exact(it) }
            tags.forEachIndexed { index, tag ->
                model.selectLocale(UserDictionaryLocaleChoice.Exact(tag))
                drain(io)
                model.state.words.map { it.id } shouldBe listOf(index + 1L)
            }
            dao.queriedRawTags shouldBe tags
        }
    }

    test("editing Floris entries preserves extended tags while system normalization stays unchanged") {
        normalizeEditedUserDictionaryLocale(" sr-Latn-RS-u-ca-gregory ", UserDictionaryType.FLORIS) shouldBe
            "sr-Latn-RS-u-ca-gregory"
        normalizeEditedUserDictionaryLocale(" en-US ", UserDictionaryType.FLORIS) shouldBe "en_US"
        normalizeEditedUserDictionaryLocale(" he-IL ", UserDictionaryType.FLORIS) shouldBe "he_IL"
        normalizeEditedUserDictionaryLocale(" iw-IL ", UserDictionaryType.FLORIS) shouldBe "iw_IL"
        normalizeEditedUserDictionaryLocale(" sr-Latn-RS ", UserDictionaryType.SYSTEM) shouldBe "sr_LATN_RS"
        normalizeEditedUserDictionaryLocale("  ", UserDictionaryType.FLORIS) shouldBe null
        normalizeEditedUserDictionaryLocale("en-US", UserDictionaryType.FLORIS, "en-US") shouldBe "en-US"
        normalizeEditedUserDictionaryLocale("iw-IL", UserDictionaryType.FLORIS, "iw-IL") shouldBe "iw-IL"
        normalizeEditedUserDictionaryLocale("", UserDictionaryType.FLORIS, "") shouldBe ""
        normalizeEditedUserDictionaryLocale("en-US", UserDictionaryType.SYSTEM, "en-US") shouldBe "en_US"
    }

    test("a completed old read cannot replace a newer locale selection") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val dao = FakeDao(io).apply {
                entries += entry(1, "hello", "en")
                entries += entry(2, "bonjour", "fr")
            }
            val model = model(io, dao)
            drain(io)

            model.selectLocale(standard("en"))
            runCurrent()
            io.runAll() // The English result is ready, but has not reached the UI yet.
            model.selectLocale(standard("fr"))
            drain(io)

            model.state.currentLocale shouldBe standard("fr")
            model.state.words.map { it.word } shouldBe listOf("bonjour")
        }
    }

    test("a second write is rejected synchronously, then the list refreshes") {
        withMainTestDispatcher {
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
            model.state.languages shouldBe listOf(UserDictionaryLocaleChoice.All)
        }
    }

    test("locale selection during a write waits for the completed write") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val dao = FakeDao(io)
            val model = model(io, dao)
            drain(io)

            model.save(entry(0, "bonjour", "fr"), isAdd = true) shouldBe true
            model.selectLocale(standard("fr"))
            model.state.currentLocale shouldBe standard("fr")
            model.state.loading shouldBe true
            runCurrent()
            io.pendingCount shouldBe 1 // Selection did not start a read during the write.

            drain(io)
            model.state.currentLocale shouldBe standard("fr")
            model.state.words.map { it.word } shouldBe listOf("bonjour")
        }
    }

    test("a failed write reports a dismissible notice and releases busy state") {
        withMainTestDispatcher {
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
        }
    }

    test("a failed read ends loading and reports the error") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val dao = FakeDao(io).apply { queryFailure = IllegalStateException("read failed") }
            val model = model(io, dao)
            drain(io)

            model.state.loading shouldBe false
            model.state.notice shouldBe UserDictionaryNotice.Failure("read failed")
        }
    }

    test("deleting the last word returns to the language list") {
        withMainTestDispatcher {
            val io = QueuedIoDispatcher()
            val word = entry(1, "hello", "en")
            val dao = FakeDao(io).apply { entries += word }
            val model = model(io, dao)
            drain(io)
            model.selectLocale(standard("en"))
            drain(io)

            model.delete(word) shouldBe true
            drain(io)
            model.state.currentLocale shouldBe null
            model.state.languages shouldBe emptyList()
        }
    }

    test("an accepted write completes after the route ViewModel is cleared") {
        withMainTestDispatcher {
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
        }
    }
})

@OptIn(ExperimentalCoroutinesApi::class)
private fun withMainTestDispatcher(block: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
        block()
    } finally {
        Dispatchers.resetMain()
    }
}

private fun standard(tag: String) = UserDictionaryLocaleChoice.Standard(FlorisLocale.fromTag(tag))

private fun model(
    io: QueuedIoDispatcher,
    dao: FakeDao,
    type: UserDictionaryType = UserDictionaryType.FLORIS,
): UserDictionaryScreenModel {
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
        type = type,
        ioDispatcher = io,
    )
}

private fun entry(id: Long, word: String, locale: String? = null): UserDictionaryEntry {
    return UserDictionaryEntry(id = id, word = word, freq = 255, locale = locale, shortcut = null)
}

private class FakeDao(private val io: QueuedIoDispatcher) : UserDictionaryDao {
    val entries = mutableListOf<UserDictionaryEntry>()
    val queriedLocales = mutableListOf<FlorisLocale?>()
    val queriedRawTags = mutableListOf<String>()
    val queriedRawAliasTags = mutableListOf<String>()
    var insertFailure: Exception? = null
    var queryFailure: Exception? = null
    private var nextId = 10L

    private fun requireIo() = check(io.running) { "DAO work must use the I/O dispatcher" }

    private fun String?.matchesRawAliases(locale: String) =
        equals(locale, ignoreCase = true) || equals(locale.replace('_', '-'), ignoreCase = true)

    override fun queryAll(): List<UserDictionaryEntry> {
        requireIo()
        return entries.toList()
    }

    override fun queryAll(locale: FlorisLocale?): List<UserDictionaryEntry> {
        requireIo()
        queriedLocales += locale
        val tag = locale?.localeTag()
        return entries.filter { entry ->
            if (tag == null) {
                entry.locale == null
            } else {
                entry.locale.equals(tag, ignoreCase = true) ||
                    entry.locale.equals(tag.replace('_', '-'), ignoreCase = true)
            }
        }
    }

    override fun queryAllRaw(locale: String): List<UserDictionaryEntry> {
        requireIo()
        queriedRawTags += locale
        return entries.filter { it.locale == locale }
    }

    override fun queryAllRawAliases(locale: String): List<UserDictionaryEntry> {
        requireIo()
        queriedRawAliasTags += locale
        return entries.filter { it.locale.matchesRawAliases(locale) }
    }

    override fun queryExact(word: String, locale: FlorisLocale?): List<UserDictionaryEntry> {
        requireIo()
        return entries.filter { it.word == word && it.locale?.let { tag -> FlorisLocale.fromTag(tag) } == locale }
    }

    override fun queryExactRaw(word: String, locale: String): List<UserDictionaryEntry> {
        requireIo()
        return entries.filter { it.word == word && it.locale == locale }
    }

    override fun queryExactRawAliases(word: String, locale: String): List<UserDictionaryEntry> {
        requireIo()
        return entries.filter { it.word == word && it.locale.matchesRawAliases(locale) }
    }

    override fun queryLanguageList(): List<String?> {
        requireIo()
        queryFailure?.let { throw it }
        return entries.map { it.locale }.distinct()
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
