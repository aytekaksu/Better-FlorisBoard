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

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryDatabase
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.dictionary.canonicalImportedFlorisLocale
import dev.patrickgold.florisboard.ime.dictionary.parsedFlorisBrowseLocale
import dev.patrickgold.florisboard.lib.FlorisLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface UserDictionaryLocaleChoice {
    data object All : UserDictionaryLocaleChoice
    data class Standard(val locale: FlorisLocale) : UserDictionaryLocaleChoice
    data class Exact(val tag: String) : UserDictionaryLocaleChoice
}

internal fun normalizeEditedUserDictionaryLocale(
    input: String,
    type: UserDictionaryType,
    original: String? = null,
): String? = if (type == UserDictionaryType.FLORIS && input == original.orEmpty()) {
    original
} else {
    input.trim().takeIf { it.isNotBlank() }?.let { tag ->
        when (type) {
            UserDictionaryType.FLORIS -> canonicalImportedFlorisLocale(tag)
            UserDictionaryType.SYSTEM -> FlorisLocale.fromTag(tag).localeTag()
        }
    }
}

internal data class UserDictionaryScreenState(
    val currentLocale: UserDictionaryLocaleChoice? = null,
    val languages: List<UserDictionaryLocaleChoice> = emptyList(),
    val words: List<UserDictionaryEntry> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val notice: UserDictionaryNotice? = null,
)

internal sealed interface UserDictionaryNotice {
    data object ImportSuccess : UserDictionaryNotice
    data object ExportSuccess : UserDictionaryNotice
    data class Failure(val detail: String?) : UserDictionaryNotice
}

/** Keeps dictionary I/O off the UI thread, including the first database lookup. */
internal class UserDictionaryScreenModel(
    private val database: () -> UserDictionaryDatabase,
    private val context: () -> Context,
    private val type: UserDictionaryType,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    constructor(
        database: () -> UserDictionaryDatabase,
        context: Context,
        type: UserDictionaryType,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(database, { context }, type, ioDispatcher)

    var state by mutableStateOf(UserDictionaryScreenState())
        private set

    private var readJob: Job? = null
    private var readGeneration = 0L
    private val storeMutex = Mutex()
    @Volatile private var cleared = false

    init {
        refresh()
    }

    fun selectLocale(locale: UserDictionaryLocaleChoice?) {
        if (cleared) return
        state = state.copy(currentLocale = locale, words = emptyList(), loading = true)
        if (!state.busy) refresh()
    }

    fun save(entry: UserDictionaryEntry, isAdd: Boolean): Boolean = submit {
        if (isAdd) userDictionaryDao().insert(entry) else userDictionaryDao().update(entry)
        null
    }

    fun delete(entry: UserDictionaryEntry): Boolean = submit {
        userDictionaryDao().delete(entry)
        null
    }

    fun importFrom(uri: Uri): Boolean = submit {
        importCombinedList(context(), uri)
        UserDictionaryNotice.ImportSuccess
    }

    fun exportTo(uri: Uri): Boolean = submit {
        exportCombinedList(context(), uri)
        UserDictionaryNotice.ExportSuccess
    }

    fun clearNotice(notice: UserDictionaryNotice) {
        if (state.notice == notice) state = state.copy(notice = null)
    }

    private fun submit(action: UserDictionaryDatabase.() -> UserDictionaryNotice?): Boolean {
        if (cleared || state.busy) return false
        readGeneration++
        readJob?.cancel()
        state = state.copy(busy = true, loading = true, notice = null)

        // Start synchronously so clearing the route immediately after a tap cannot skip the write.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val notice = withContext(NonCancellable + ioDispatcher) {
                try {
                    storeMutex.withLock { database().action() }
                } catch (error: Exception) {
                    UserDictionaryNotice.Failure(error.localizedMessage)
                }
            }
            if (!cleared) {
                state = state.copy(busy = false, notice = notice)
                refresh()
            }
        }
        return true
    }

    private fun refresh() {
        val generation = ++readGeneration
        val requestedLocale = state.currentLocale
        readJob?.cancel()
        state = state.copy(loading = true)
        readJob = viewModelScope.launch {
            try {
                val snapshot = withContext(ioDispatcher) { storeMutex.withLock { load(requestedLocale) } }
                if (!cleared && generation == readGeneration) {
                    state = state.copy(
                        currentLocale = snapshot.locale,
                        languages = snapshot.languages,
                        words = snapshot.words,
                        loading = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!cleared && generation == readGeneration) {
                    state = state.copy(loading = false, notice = UserDictionaryNotice.Failure(error.localizedMessage))
                }
            }
        }
    }

    private fun load(requestedLocale: UserDictionaryLocaleChoice?): Snapshot {
        val dao = database().userDictionaryDao()
        val words = when (requestedLocale) {
            null -> emptyList()
            UserDictionaryLocaleChoice.All -> dao.queryAll(null)
            is UserDictionaryLocaleChoice.Standard -> dao.queryAll(requestedLocale.locale)
            is UserDictionaryLocaleChoice.Exact -> dao.queryAllRaw(requestedLocale.tag)
        }
        return if (requestedLocale != null && words.isNotEmpty()) {
            Snapshot(requestedLocale, emptyList(), words)
        } else {
            val languages = dao.queryLanguageList()
                .map { tag ->
                    when {
                        tag == null -> UserDictionaryLocaleChoice.All
                        type == UserDictionaryType.FLORIS ->
                            parsedFlorisBrowseLocale(tag)?.let { UserDictionaryLocaleChoice.Standard(it) }
                                ?: UserDictionaryLocaleChoice.Exact(tag)
                        else -> UserDictionaryLocaleChoice.Standard(FlorisLocale.fromTag(tag))
                    }
                }
                .distinct()
                .sortedBy { choice ->
                    when (choice) {
                        UserDictionaryLocaleChoice.All -> ""
                        is UserDictionaryLocaleChoice.Standard -> choice.locale.displayLanguage()
                        is UserDictionaryLocaleChoice.Exact -> choice.tag
                    }
                }
            Snapshot(null, languages, emptyList())
        }
    }

    override fun onCleared() {
        cleared = true
        super.onCleared()
    }

    private data class Snapshot(
        val locale: UserDictionaryLocaleChoice?,
        val languages: List<UserDictionaryLocaleChoice>,
        val words: List<UserDictionaryEntry>,
    )
}
