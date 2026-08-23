/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp.han

import android.content.Context
import android.database.sqlite.SQLiteException
import android.icu.text.BreakIterator
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.nlp.BreakIteratorGroup
import dev.patrickgold.florisboard.ime.nlp.LanguagePackComponent
import dev.patrickgold.florisboard.ime.nlp.LanguagePackExtension
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HanShapeBasedLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.han.shape"

        private const val DEFAULT_KEY_CODE_ID = "default"
        private val DEFAULT_KEY_CODES = "abcdefghijklmnopqrstuvwxyz".toSet()
    }

    private val appContext = context.applicationContext
    private val extensionManager by appContext.extensionManager()
    private val subtypeManager by appContext.subtypeManager()
    private val lifecycleGuard = Mutex()
    private val resourceGuard = Mutex()

    private var refreshOwner: Job? = null
    private var refreshCollector: Job? = null

    @Volatile
    private var languagePackState = emptyLanguagePackState()

    private val connections = LanguagePackConnections(
        isUsable = { pack: LanguagePackExtension ->
            pack.hasOpenHanDatabase()
        },
        connect = { pack ->
            pack.loadForHanProvider(appContext)
                .fold(
                    onSuccess = { true },
                    onFailure = {
                        flogError { "Failed to load Han language pack" }
                        false
                    },
                )
        },
        disconnect = { pack ->
            pack.unloadForHanProvider(appContext)
                .onFailure {
                    flogError { "Failed to unload Han language pack" }
                }
            Unit
        },
    )

    override val providerId = ProviderId

    override suspend fun create() {
        lifecycleGuard.withLock {
            if (refreshCollector?.isActive == true) return

            refreshOwner?.cancel()
            refreshCollector?.join()
            reconcileLanguagePacks(currentRefreshInput())

            val owner = SupervisorJob()
            refreshOwner = owner
            refreshCollector = CoroutineScope(Dispatchers.Default + owner).launch {
                observeLanguagePackChanges()
            }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        return when (word.lowercase()) {
            // Use typo for typing errors
            "typo" -> SpellingResult.typo(arrayOf("typo1", "typo2", "typo3"))
            // Use grammar error if the algorithm can detect this. On Android 11 and lower grammar errors are visually
            // marked as typos due to a lack of support
            "gerror" -> SpellingResult.grammarError(arrayOf("grammar1", "grammar2", "grammar3"))
            // Use valid word for valid input
            else -> SpellingResult.validWord()
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        if (content.composingText.isEmpty()) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            resourceGuard.withLock {
                val (languagePackItem, languagePackExtension) =
                    getLanguagePack(subtype) ?: return@withLock emptyList()
                val layout = languagePackItem.hanShapeBasedTable
                try {
                    languagePackExtension.withHanDatabase { database ->
                        database.query(
                            layout,
                            arrayOf("code", "text"),
                            "code LIKE ? || '%'",
                            arrayOf(content.composingText),
                            null,
                            null,
                            "code ASC, weight DESC",
                            maxCandidateCount.toString(),
                        ).use { cursor ->
                            val suggestions = buildList<SuggestionCandidate> {
                                while (cursor.moveToNext()) {
                                    add(
                                        WordSuggestionCandidate(
                                            text = cursor.getString(1),
                                            secondaryText = cursor.getString(0),
                                            confidence = 0.5,
                                            isEligibleForAutoCommit = isEmpty(),
                                            sourceProvider = this@HanShapeBasedLanguageProvider,
                                        ),
                                    )
                                }
                            }
                            flogDebug {
                                "Dictionary query completed, resultCount=${suggestions.size}"
                            }
                            suggestions
                        }
                    } ?: emptyList()
                } catch (_: IllegalStateException) {
                    flogError { "Dictionary layout is invalid" }
                    emptyList()
                } catch (error: SQLiteException) {
                    flogError {
                        "Dictionary query failed: error=${error::class.simpleName}"
                    }
                    emptyList()
                }
            }
        }
    }

    fun getLanguagePack(subtype: Subtype): Pair<LanguagePackComponent, LanguagePackExtension>? {
        return languagePackState.bindings[subtype.primaryLocale.localeTag()]
            ?.let { binding -> binding.component to binding.extension }
    }

    override suspend fun destroy() {
        lifecycleGuard.withLock {
            val owner = refreshOwner
            val collector = refreshCollector
            refreshOwner = null
            refreshCollector = null
            owner?.cancel()
            withContext(NonCancellable) {
                collector?.join()
                withContext(Dispatchers.IO) {
                    resourceGuard.withLock {
                        connections.clear()
                        languagePackState = emptyLanguagePackState()
                    }
                }
            }
        }
    }

    override fun determineLocalComposing(
        subtype: Subtype,
        textBeforeSelection: CharSequence,
        breakIterators: BreakIteratorGroup,
        localLastCommitPosition: Int,
    ): EditorRange {
        return breakIterators.character(subtype.primaryLocale) {
            it.setText(textBeforeSelection.toString())
            val end = it.last()
            var start = end
            var next = it.previous()
            val keyCodes = languagePackState.keyCodes
            val keyCodeLocale =
                keyCodes[subtype.primaryLocale.localeTag()]
                    ?: keyCodes[DEFAULT_KEY_CODE_ID]
                    ?: emptySet()
            while (next != BreakIterator.DONE && start > localLastCommitPosition) {
                val sub = textBeforeSelection.substring(next, start)
                if (!sub.all { char -> char in keyCodeLocale }) {
                    break
                }
                start = next
                next = it.previous()
            }
            if (start != end) {
                flogDebug { "Determined composing range length=${end - start}" }
                EditorRange(start, end)
            } else {
                flogDebug { "Determined Unspecified as composing" }
                EditorRange.Unspecified
            }
        }
    }

    override val forcesSuggestionOn
        get() = true

    private suspend fun observeLanguagePackChanges() = coroutineScope {
        val requests = Channel<LanguagePackRefreshInput>(Channel.CONFLATED)
        launch {
            try {
                combine(
                    extensionManager.languagePacks,
                    subtypeManager.subtypesFlow,
                ) { languagePacks, subtypes ->
                    refreshInput(languagePacks, subtypes)
                }.collect { input ->
                    requests.trySend(input)
                }
            } finally {
                requests.close()
            }
        }
        for (input in requests) {
            try {
                reconcileLanguagePacks(input)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                flogError { "Failed to refresh Han language packs" }
            }
        }
    }

    private suspend fun reconcileLanguagePacks(input: LanguagePackRefreshInput) {
        if (languagePackState.input == input) return
        withContext(Dispatchers.IO) {
            resourceGuard.withLock {
                if (languagePackState.input == input) return@withLock
                val desired = input.languagePacks.filter { languagePack ->
                    languagePack.items.any { item ->
                        item.locale.localeTag() in input.activeLocales
                    }
                }
                val connected = connections.replace(desired)
                val bindings = buildMap {
                    for (languagePack in connected) {
                        for (component in languagePack.items) {
                            put(
                                component.locale.localeTag(),
                                LanguagePackBinding(component, languagePack),
                            )
                        }
                    }
                }
                val keyCodes = buildMap {
                    for ((tag, binding) in bindings) {
                        put(tag, binding.component.hanShapeBasedKeyCode.toSet())
                    }
                    put(DEFAULT_KEY_CODE_ID, DEFAULT_KEY_CODES)
                }
                languagePackState = LanguagePackState(
                    input = input,
                    bindings = bindings,
                    keyCodes = keyCodes,
                )
            }
        }
    }

    private fun currentRefreshInput(): LanguagePackRefreshInput =
        refreshInput(
            extensionManager.languagePacks.value,
            subtypeManager.subtypesFlow.value,
        )

    private fun refreshInput(
        languagePacks: List<LanguagePackExtension>,
        subtypes: List<Subtype>,
    ) = LanguagePackRefreshInput(
        languagePacks = languagePacks.toList(),
        activeLocales = subtypes.mapTo(linkedSetOf()) {
            it.primaryLocale.localeTag()
        },
    )

    private fun emptyLanguagePackState() = LanguagePackState(
        input = null,
        bindings = emptyMap(),
        keyCodes = mapOf(DEFAULT_KEY_CODE_ID to DEFAULT_KEY_CODES),
    )

    private data class LanguagePackRefreshInput(
        val languagePacks: List<LanguagePackExtension>,
        val activeLocales: Set<String>,
    )

    private data class LanguagePackBinding(
        val component: LanguagePackComponent,
        val extension: LanguagePackExtension,
    )

    private data class LanguagePackState(
        val input: LanguagePackRefreshInput?,
        val bindings: Map<String, LanguagePackBinding>,
        val keyCodes: Map<String, Set<Char>>,
    )
}

internal class LanguagePackConnections<T>(
    private val isUsable: (T) -> Boolean,
    private val connect: (T) -> Boolean,
    private val disconnect: (T) -> Unit,
) {
    private val connected = linkedSetOf<T>()

    fun replace(desired: Iterable<T>): Set<T> {
        val desiredSet = desired.toCollection(linkedSetOf())
        val removed = connected.filterNot(desiredSet::contains)
        for (candidate in removed) {
            disconnect(candidate)
            connected.remove(candidate)
        }
        for (candidate in desiredSet) {
            if (candidate in connected && isUsable(candidate)) continue
            connected.remove(candidate)
            if (connect(candidate)) {
                connected.add(candidate)
            } else {
                disconnect(candidate)
            }
        }
        return connected.toSet()
    }

    fun clear() {
        val previous = connected.toList()
        connected.clear()
        previous.forEach(disconnect)
    }
}
