/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorComposingPolicy
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionProvider
import dev.patrickgold.florisboard.ime.smartbar.SmartbarCandidateController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.lib.kotlin.collectLatestIn

internal class CandidateRevision {
    private var current = 0L

    @Synchronized
    fun next(onAdvance: (Long) -> Unit = {}) = (++current).also(onAdvance)

    @Synchronized
    fun publishIfCurrent(revision: Long, publish: () -> Unit) =
        (revision == current).also { if (it) publish() }
}

internal data class RevisionedPreload<T>(
    val revision: Long,
    val value: T,
)

internal class GlideTypingLexiconKey(
    val subtype: Subtype,
) {
    private val locales = subtype.locales()
    private val suggestionProvider = subtype.nlpProviders.suggestion

    override fun equals(other: Any?) =
        other is GlideTypingLexiconKey &&
            locales == other.locales &&
            suggestionProvider == other.suggestionProvider

    override fun hashCode() = 31 * locales.hashCode() + suggestionProvider.hashCode()
}

/**
 * Serializes preloads for a key and exposes only data produced after the latest preload completed.
 */
internal class AsyncPreloadCache<K, V>(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 5,
    private val load: suspend (K) -> V,
) {
    private class Entry<V> {
        var latest: Deferred<RevisionedPreload<V>>? = null
        val unfinished = mutableSetOf<Deferred<RevisionedPreload<V>>>()
    }

    private class EvictedException : CancellationException("Preload cache entry was evicted")

    private val guard = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true)
    private var nextRevision = 0L

    init {
        require(maxEntries > 0)
    }

    fun preload(key: K): Deferred<RevisionedPreload<V>> {
        val pending = synchronized(guard) {
            createPreload(key)
        }
        pending.start()
        return pending
    }

    suspend fun await(key: K): RevisionedPreload<V> {
        while (true) {
            currentCoroutineContext().ensureActive()
            val pending = synchronized(guard) {
                entries[key]?.latest ?: createPreload(key)
            }
            pending.start()
            val value = try {
                pending.await()
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (error is EvictedException) continue
                discardFailed(key, pending)
                throw error
            } catch (error: Exception) {
                discardFailed(key, pending)
                throw error
            }
            if (synchronized(guard) { entries[key]?.latest === pending }) {
                return value
            }
        }
    }

    private fun createPreload(key: K): Deferred<RevisionedPreload<V>> {
        val entry = entries[key] ?: Entry<V>().also { entries[key] = it }
        val previous = entry.latest
        val revision = ++nextRevision
        return scope.async(start = CoroutineStart.LAZY) {
            val previousValue = try {
                previous?.await()
            } catch (_: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (_: Exception) {
                null
            }
            val value = load(key)
            previousValue?.takeIf { it.value == value } ?: RevisionedPreload(revision, value)
        }.also { pending ->
            entry.latest = pending
            entry.unfinished.add(pending)
            pending.invokeOnCompletion {
                synchronized(guard) { entry.unfinished.remove(pending) }
            }
            while (entries.size > maxEntries) {
                entries.entries.iterator().run {
                    val evicted = next().value
                    remove()
                    cancelUnfinished(evicted)
                }
            }
        }
    }

    private fun discardFailed(key: K, pending: Deferred<RevisionedPreload<V>>) {
        if (!pending.isCancelled) return
        synchronized(guard) {
            entries[key]?.takeIf { it.latest === pending }?.let { entry ->
                entries.remove(key)
                cancelUnfinished(entry)
            }
        }
    }

    private fun cancelUnfinished(entry: Entry<V>) {
        val cause = EvictedException()
        entry.unfinished.toList().asReversed().forEach { it.cancel(cause) }
    }
}

/** Synchronous suggestion operations needed while dispatching keyboard input. */
interface KeyboardSuggestionSession {
    fun isSuggestionOn(): Boolean
    fun suggest(subtype: Subtype, content: EditorContent)
    fun clearSuggestions()
    fun finishAutocorrectSession()
}

class NlpManager internal constructor(
    context: Context,
    private val activeSubtypeFlow: StateFlow<Subtype>,
    builtInProviders: Map<String, SuggestionProvider>,
    private val composingPolicy: EditorComposingPolicy,
    private val currentEditorContent: () -> EditorContent,
    private val isIncognitoMode: () -> Boolean,
    private val candidates: SmartbarCandidateController,
    private val scope: CoroutineScope,
) : KeyboardSuggestionSession {
    private val prefs by FlorisPreferenceStore
    private val autocorrectPluginManager by context.autocorrectPluginManager()

    private val emojiSuggestionProvider = EmojiSuggestionProvider(context)
    private val providers = builtInProviders.mapValues { (_, provider) -> ProviderInstanceWrapper(provider) }
    private val providerLifecycleGate = Mutex()

    private val candidateRequestRevision = CandidateRevision()
    private val glideTypingWords = AsyncPreloadCache<GlideTypingLexiconKey, List<String>>(scope) { key ->
        val subtype = key.subtype
        preloadProviders(subtype)
        getBuiltInSuggestionProvider(subtype).glideTypingWordsOrEmpty(subtype).toList()
    }
    private val suggestionJobGuard = Any()
    private var suggestionJob: Job? = null

    init {
        prefs.suggestion.enabled.asFlow().collectLatestIn(scope) {
            autocorrectPluginManager.finishSession()
            clearSuggestions()
        }
        prefs.suggestion.autocorrectPluginComponent.asFlow().collectLatestIn(scope) {
            autocorrectPluginManager.onSelectedProviderChanged()
            clearSuggestions()
        }
        activeSubtypeFlow.collectLatestIn(scope) { subtype ->
            preload(subtype)
        }
    }

    private fun resolveBuiltInSuggestionProvider(subtype: Subtype): SuggestionProvider {
        return providers[subtype.nlpProviders.suggestion]?.provider.asSuggestionProviderOrFallback()
    }

    private suspend fun getBuiltInSuggestionProvider(subtype: Subtype): SuggestionProvider {
        return providerLifecycleGate.withLock {
            resolveBuiltInSuggestionProvider(subtype)
        }
    }

    override fun finishAutocorrectSession() {
        autocorrectPluginManager.finishSession()
        clearSuggestions()
    }

    fun preload(subtype: Subtype) {
        if (prefs.glide.enabled.get()) {
            glideTypingWords.preload(GlideTypingLexiconKey(subtype))
        } else {
            scope.launch { preloadProviders(subtype) }
        }
    }

    private suspend fun preloadProviders(subtype: Subtype) {
        emojiSuggestionProvider.preload(subtype)
        providerLifecycleGate.withLock {
            providers[subtype.nlpProviders.suggestion]?.let { instance ->
                instance.createIfNecessary()
                instance.provider.preload(subtype)
            }
        }
    }

    override fun isSuggestionOn(): Boolean = composingPolicy.isSuggestionOn()

    private fun launchLatestSuggestionRequest(block: suspend (Long) -> Unit) {
        synchronized(suggestionJobGuard) {
            suggestionJob?.cancel()
            val revision = candidateRequestRevision.next()
            suggestionJob = scope.launch { block(revision) }
        }
    }

    override fun suggest(subtype: Subtype, content: EditorContent) {
        val requestEditorGeneration = autocorrectPluginManager.captureEditorGeneration()
        candidates.onTyping(content)
        launchLatestSuggestionRequest { revision ->
            val emojiSuggestions = when {
                prefs.emoji.suggestionEnabled.get() -> {
                    emojiSuggestionProvider.suggest(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = prefs.emoji.suggestionCandidateMaxCount.get(),
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = isIncognitoMode(),
                    )
                }
                else -> emptyList()
            }
            suspend fun builtInSuggestions() = getBuiltInSuggestionProvider(subtype).suggest(
                subtype = subtype,
                content = content,
                maxCandidateCount = 8,
                allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                isPrivateSession = isIncognitoMode(),
            )
            val suggestions = when {
                emojiSuggestions.isNotEmpty() && prefs.emoji.suggestionType.get().prefix.isNotEmpty() -> {
                    emptyList()
                }
                prefs.suggestion.autocorrectPluginComponent.get().isBlank() -> builtInSuggestions()
                else -> {
                    val externalResult = autocorrectPluginManager.suggestWithStatus(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = AutocorrectPluginContract.MAX_CANDIDATES,
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = isIncognitoMode(),
                        requestEditorGeneration = requestEditorGeneration,
                    )
                    if (externalResult.handled) externalResult.candidates else builtInSuggestions()
                }
            }
            candidateRequestRevision.publishIfCurrent(revision) {
                candidates.replaceCandidates {
                    buildList {
                        emojiSuggestions.forEach { add(it.bindOriginContent(content)) }
                        suggestions.forEach { add(it.bindOriginContent(content)) }
                    }
                }
            }
        }
    }

    fun suggestDirectly(suggestions: List<SuggestionCandidate>) {
        synchronized(suggestionJobGuard) {
            suggestionJob?.cancel()
            suggestionJob = null
            candidateRequestRevision.next()
            candidates.replaceCandidates(suggestions)
        }
    }

    override fun clearSuggestions() {
        synchronized(suggestionJobGuard) {
            suggestionJob?.cancel()
            suggestionJob = null
            candidateRequestRevision.next()
            candidates.clearCandidates()
        }
    }

    suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return (candidate.sourceProvider?.removeSuggestion(subtype, candidate) == true).also { removed ->
            if (removed) {
                // Need to re-trigger the suggestions algorithm
                if (candidate is ClipboardSuggestionCandidate) {
                    candidates.refresh()
                } else {
                    suggest(activeSubtypeFlow.value, currentEditorContent())
                }
            }
        }
    }

    internal suspend fun getGlideTypingWordData(subtype: Subtype) =
        glideTypingWords.await(GlideTypingLexiconKey(subtype))

    internal suspend fun getGlideTypingWordFrequency(subtype: Subtype, word: String) =
        getBuiltInSuggestionProvider(subtype).glideTypingWordFrequencyOrZero(subtype, word)

    private class ProviderInstanceWrapper(val provider: NlpProvider) {
        private val lifecycle = NlpProviderLifecycle()

        suspend fun createIfNecessary() {
            lifecycle.createIfNecessary(provider::create)
        }
    }
}
