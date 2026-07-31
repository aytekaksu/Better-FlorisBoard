/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.nlp

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.autocorrectPluginManager
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.han.HanShapeBasedLanguageProvider
import dev.patrickgold.florisboard.ime.nlp.latin.LatinLanguageProvider
import dev.patrickgold.florisboard.ime.nlp.plugin.AutocorrectPluginManager
import dev.patrickgold.florisboard.ime.nlp.plugin.AutocorrectPluginSuggestionBatch
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.util.NetworkUtils
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.systemService
import org.florisboard.lib.kotlin.guardedByLock
import org.florisboard.lib.kotlin.collectLatestIn
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.Delegates

internal data class ClipboardSuggestionMatch(
    val value: String,
    val range: IntRange,
) {
    override fun toString() = "ClipboardSuggestionMatch(value=<redacted>)"
}

internal fun findClipboardSuggestionMatches(
    text: CharSequence,
    maxMatches: Int,
): List<ClipboardSuggestionMatch> {
    val limit = maxMatches.coerceIn(0, MAX_CLIPBOARD_SUGGESTION_CANDIDATES)
    if (limit == 0) return emptyList()

    val boundedText = text.take(MAX_CLIPBOARD_SUGGESTION_SCAN_CHARS).toString()
    val matches = sequence {
        yieldAll(NetworkUtils.getEmailAddresses(boundedText, MAX_CLIPBOARD_SUGGESTION_RAW_MATCHES))
        yieldAll(NetworkUtils.getUrls(boundedText, MAX_CLIPBOARD_SUGGESTION_RAW_MATCHES))
        yieldAll(NetworkUtils.getPhoneNumbers(boundedText, MAX_CLIPBOARD_SUGGESTION_RAW_MATCHES))
    }
    val previousMatches = mutableListOf<MatchGroup>()
    return buildList(limit) {
        for (match in matches) {
            val isUnique = previousMatches.none { previous ->
                previous.value == match.value ||
                    previous.range.first <= match.range.last &&
                    match.range.first <= previous.range.last
            }
            previousMatches += match
            if (match.value == boundedText || !isUnique) continue
            add(ClipboardSuggestionMatch(match.value, match.range))
            if (size == limit) break
        }
    }
}

internal fun buildClipboardSuggestionItems(
    source: ClipboardItem,
    maxCandidateCount: Int,
): List<ClipboardItem> {
    val limit = maxCandidateCount.coerceIn(0, MAX_CLIPBOARD_SUGGESTION_CANDIDATES)
    if (limit == 0) return emptyList()
    return buildList(limit) {
        add(source)
        if (source.isSensitive || source.type != ItemType.TEXT || size == limit) {
            return@buildList
        }
        findClipboardSuggestionMatches(
            text = source.stringRepresentation(),
            maxMatches = limit - size,
        ).forEach { match ->
            add(source.copy(text = match.value.removeSurrounding("(", ")")))
        }
    }
}

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

internal class AutomaticSmartbarMutations {
    private val revision = AtomicLong()
    private val guard = Mutex()

    fun next() = revision.incrementAndGet()

    suspend fun runIfCurrent(expectedRevision: Long, mutate: suspend () -> Unit) =
        guard.withLock {
            (expectedRevision == revision.get()).also { if (it) mutate() }
        }
}

internal data class SharedActionsAnimationSuppression(
    val revision: Long,
    val targetExpanded: Boolean,
)

internal class SharedActionsAnimationSuppressionTracker {
    private val mutableSuppression = MutableStateFlow<SharedActionsAnimationSuppression?>(null)

    val suppression = mutableSuppression.asStateFlow()

    fun suppress(revision: Long, targetExpanded: Boolean) {
        mutableSuppression.value = SharedActionsAnimationSuppression(revision, targetExpanded)
    }

    fun acknowledge(suppression: SharedActionsAnimationSuppression) =
        mutableSuppression.compareAndSet(suppression, null)

    fun clear() {
        mutableSuppression.value = null
    }
}

class NlpManager(context: Context) {
    private val prefs by FlorisPreferenceStore
    private val clipboardManager by context.clipboardManager()
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val autocorrectPluginManager by context.autocorrectPluginManager()
    private val subtypeManager by context.subtypeManager()
    private val keyguardManager = context.systemService(AndroidKeyguardManager::class)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clipboardSuggestionProvider = ClipboardSuggestionProvider(context)
    private val emojiSuggestionProvider = EmojiSuggestionProvider(context)
    private val providers = guardedByLock {
        mapOf(
            LatinLanguageProvider.ProviderId to ProviderInstanceWrapper(LatinLanguageProvider(context)),
            HanShapeBasedLanguageProvider.ProviderId to ProviderInstanceWrapper(HanShapeBasedLanguageProvider(context)),
        )
    }
    // lock unnecessary because values constant
    private val providersForceSuggestionOn = mutableMapOf<String, Boolean>()

    private val candidateAssemblyRevision = CandidateRevision()
    private val candidateRequestRevision = CandidateRevision()
    private val automaticSmartbarMutations = AutomaticSmartbarMutations()
    private val sharedActionsAnimationSuppression = SharedActionsAnimationSuppressionTracker()
    private val glideTypingWords = AsyncPreloadCache<GlideTypingLexiconKey, List<String>>(scope) { key ->
        val subtype = key.subtype
        preloadProviders(subtype)
        getBuiltInSuggestionProvider(subtype).getListOfWords(subtype).toList()
    }
    private val suggestionJobGuard = Any()
    private val internalSuggestionsGuard = Any()
    private var suggestionJob: Job? = null
    private var internalSuggestions by Delegates.observable(listOf<SuggestionCandidate>()) { _, _, _ ->
        scope.launch { assembleCandidates() }
    }

    private val _activeCandidatesFlow = MutableStateFlow(listOf<SuggestionCandidate>())
    @Volatile private var autoCommitCandidate: SuggestionCandidate? = null
    val activeCandidatesFlow = _activeCandidatesFlow.asStateFlow()
    internal val sharedActionsAnimationSuppressionState =
        sharedActionsAnimationSuppression.suppression
    inline var activeCandidates
        get() = activeCandidatesFlow.value
        private set(v) {
            _activeCandidatesFlow.value = v
        }

    private val spellingDiagnostics = SpellingDiagnostics()
    private val _spellingDiagnosticsVersion = MutableStateFlow(0L)
    internal val spellingDiagnosticsVersion = _spellingDiagnosticsVersion.asStateFlow()

    init {
        clipboardManager.primaryClipFlow.collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.suggestion.enabled.asFlow().collectLatestIn(scope) {
            autocorrectPluginManager.finishSession()
            clearSuggestions()
        }
        prefs.suggestion.autocorrectPluginComponent.asFlow().collectLatestIn(scope) {
            autocorrectPluginManager.onSelectedProviderChanged()
            clearSuggestions()
        }
        prefs.clipboard.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.emoji.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        subtypeManager.activeSubtypeFlow.collectLatestIn(scope) { subtype ->
            preload(subtype)
        }
    }

    /**
     * Gets the punctuation rule from the currently active subtype and returns it. Falls back to a default one if the
     * subtype does not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getActivePunctuationRule(): PunctuationRule {
        return getPunctuationRule(subtypeManager.activeSubtype)
    }

    /**
     * Gets the punctuation rule from the given subtype and returns it. Falls back to a default one if the subtype does
     * not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getPunctuationRule(subtype: Subtype): PunctuationRule {
        return keyboardManager.resources.punctuationRules.value[subtype.punctuationRule] ?: PunctuationRule.Fallback
    }

    private suspend fun getSpellingProvider(subtype: Subtype): SpellingProvider {
        return providers.withLock { it[subtype.nlpProviders.spelling] }?.provider as? SpellingProvider
            ?: FallbackNlpProvider
    }

    private suspend fun getBuiltInSuggestionProvider(subtype: Subtype): SuggestionProvider {
        return providers.withLock { it[subtype.nlpProviders.suggestion] }?.provider as? SuggestionProvider
            ?: FallbackNlpProvider
    }

    private suspend fun getSuggestionProvider(subtype: Subtype): SuggestionProvider {
        return if (prefs.suggestion.autocorrectPluginComponent.get().isNotBlank()) {
            autocorrectPluginManager
        } else {
            getBuiltInSuggestionProvider(subtype)
        }
    }

    fun finishAutocorrectSession() {
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
        providers.withLock { providers ->
            subtype.nlpProviders.forEach { _, providerId ->
                providers[providerId]?.let { provider ->
                    provider.createIfNecessary()
                    provider.preload(subtype)
                }
            }
        }
    }

    /**
     * Spell wrapper helper which calls the spelling provider and returns the result. Coroutine management must be done
     * by the source spell checker service.
     */
    suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
    ): SpellingResult {
        return getSpellingProvider(subtype).spell(
            subtype = subtype,
            word = word,
            precedingWords = precedingWords,
            followingWords = followingWords,
            maxSuggestionCount = maxSuggestionCount,
            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            isPrivateSession = keyboardManager.activeState.isIncognitoMode,
        )
    }

    suspend fun determineLocalComposing(
        textBeforeSelection: CharSequence, breakIterators: BreakIteratorGroup, localLastCommitPosition: Int
    ): EditorRange {
        return getSuggestionProvider(subtypeManager.activeSubtype).determineLocalComposing(
            subtypeManager.activeSubtype, textBeforeSelection, breakIterators, localLastCommitPosition
        )
    }

    fun providerForcesSuggestionOn(subtype: Subtype): Boolean {
        // Using a cache because I have no idea how fast the runBlocking is
        return providersForceSuggestionOn.getOrPut(subtype.nlpProviders.suggestion) {
            runBlocking {
                getSuggestionProvider(subtype).forcesSuggestionOn
            }
        }
    }

    fun isSuggestionOn(): Boolean =
        prefs.suggestion.enabled.get()
            || prefs.emoji.suggestionEnabled.get()
            || providerForcesSuggestionOn(subtypeManager.activeSubtype)

    private fun launchLatestSuggestionRequest(block: suspend (Long) -> Unit) {
        synchronized(suggestionJobGuard) {
            suggestionJob?.cancel()
            val revision = candidateRequestRevision.next()
            suggestionJob = scope.launch { block(revision) }
        }
    }

    fun suggest(subtype: Subtype, content: EditorContent) {
        val requestEditorGeneration = autocorrectPluginManager.captureEditorGeneration()
        if (content.currentWordText.isNotBlank() && !content.selection.isSelectionMode) {
            setSharedActionsExpanded(false)
        }
        launchLatestSuggestionRequest { revision ->
            val emojiSuggestions = when {
                prefs.emoji.suggestionEnabled.get() -> {
                    emojiSuggestionProvider.suggest(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = prefs.emoji.suggestionCandidateMaxCount.get(),
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                    )
                }
                else -> emptyList()
            }
            val suggestions = when {
                emojiSuggestions.isNotEmpty() && prefs.emoji.suggestionType.get().prefix.isNotEmpty() -> {
                    emptyList()
                }
                else -> {
                    val provider = getSuggestionProvider(subtype)
                    val externalResult = if (provider === autocorrectPluginManager) {
                        autocorrectPluginManager.suggestWithStatus(
                            subtype = subtype,
                            content = content,
                            maxCandidateCount = AutocorrectPluginContract.MAX_CANDIDATES,
                            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                            isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                            requestEditorGeneration = requestEditorGeneration,
                        )
                    } else {
                        AutocorrectPluginSuggestionBatch(
                            candidates = provider.suggest(
                                subtype = subtype,
                                content = content,
                                maxCandidateCount = 8,
                                allowPossiblyOffensive =
                                    !prefs.suggestion.blockPossiblyOffensive.get(),
                                isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                            ),
                            handled = true,
                        )
                    }
                    if (!externalResult.handled) {
                        getBuiltInSuggestionProvider(subtype).suggest(
                            subtype = subtype,
                            content = content,
                            maxCandidateCount = 8,
                            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                            isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                        )
                    } else {
                        externalResult.candidates
                    }
                }
            }
            candidateRequestRevision.publishIfCurrent(revision) {
                synchronized(internalSuggestionsGuard) {
                    internalSuggestions = buildList {
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
            synchronized(internalSuggestionsGuard) {
                internalSuggestions = suggestions
            }
        }
    }

    fun clearSuggestions() {
        synchronized(suggestionJobGuard) {
            suggestionJob?.cancel()
            suggestionJob = null
            candidateRequestRevision.next()
            synchronized(internalSuggestionsGuard) {
                internalSuggestions = emptyList()
                candidateAssemblyRevision.next {
                    autoCommitCandidate = null
                    activeCandidates = emptyList()
                    autoExpandCollapseSmartbarActions(
                        emptyList<SuggestionCandidate>(),
                        NlpInlineAutofill.suggestions.value,
                    )
                }
            }
        }
    }

    fun getAutoCommitCandidate(): SuggestionCandidate? {
        return autoCommitCandidate
    }

    suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return (candidate.sourceProvider?.removeSuggestion(subtype, candidate) == true).also { removed ->
            if (removed) {
                // Need to re-trigger the suggestions algorithm
                if (candidate is ClipboardSuggestionCandidate) {
                    assembleCandidates()
                } else {
                    suggest(subtypeManager.activeSubtype, editorInstance.activeContent)
                }
            }
        }
    }

    internal suspend fun getGlideTypingWordData(subtype: Subtype) =
        glideTypingWords.await(GlideTypingLexiconKey(subtype))

    fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return runBlocking { getBuiltInSuggestionProvider(subtype).getFrequencyForWord(subtype, word) }
    }

    private suspend fun assembleCandidates() {
        val revision = candidateAssemblyRevision.next()
        val candidates = when {
            isSuggestionOn() -> {
                val content = editorInstance.activeContent
                val wordCandidates = synchronized(internalSuggestionsGuard) {
                    internalSuggestions
                }
                val clipboardCandidates = clipboardSuggestionProvider.suggest(
                    subtype = Subtype.DEFAULT,
                    content = content,
                    maxCandidateCount = 8,
                    allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                    isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                )
                val isWordBeingTyped = content.currentWordText.isNotBlank() ||
                    wordCandidates.any {
                        it.isExternalAutocorrect() && it.kind != SuggestionCandidateKind.NEXT_WORD
                    }
                selectSmartbarCandidates(isWordBeingTyped, wordCandidates, clipboardCandidates)
            }
            else -> emptyList()
        }
        candidateAssemblyRevision.publishIfCurrent(revision) {
            val publishableCandidates = if (canUseClipboardSuggestions(
                    keyboardManager.activeState.isIncognitoMode,
                )
            ) {
                candidates
            } else {
                candidates.filterNot { it is ClipboardSuggestionCandidate }
            }
            val visibleCandidates =
                publishableCandidates.filter(SuggestionCandidate::isVisible)
            autoCommitCandidate =
                publishableCandidates.firstOrNull { it.isEligibleForAutoCommit }
            activeCandidates = visibleCandidates
            autoExpandCollapseSmartbarActions(
                visibleCandidates,
                NlpInlineAutofill.suggestions.value,
            )
        }
    }

    private fun canUseClipboardSuggestions(isPrivateSession: Boolean): Boolean {
        return prefs.clipboard.suggestionEnabled.get() &&
            !isPrivateSession &&
            runCatching {
                !keyguardManager.isDeviceLocked && !keyguardManager.isKeyguardLocked
            }.getOrDefault(false)
    }

    fun autoExpandCollapseSmartbarActions(
        candidates: List<*>?,
        inlineSuggestions: List<*>?,
    ) {
        val isSelection = editorInstance.activeContent.selection.isSelectionMode
        val isExpanded =
            candidates.isNullOrEmpty() && inlineSuggestions.isNullOrEmpty() || isSelection
        setSharedActionsExpanded(isExpanded)
    }

    fun setSharedActionsExpandedByUser(isExpanded: Boolean) {
        val revision = automaticSmartbarMutations.next()
        scope.launch {
            automaticSmartbarMutations.runIfCurrent(revision) {
                sharedActionsAnimationSuppression.clear()
                prefs.smartbar.sharedActionsExpanded.set(isExpanded)
            }
        }
    }

    internal fun acknowledgeSharedActionsAnimationSuppression(
        suppression: SharedActionsAnimationSuppression,
    ) {
        sharedActionsAnimationSuppression.acknowledge(suppression)
    }

    private fun setSharedActionsExpanded(isExpanded: Boolean) {
        if (!prefs.smartbar.enabled.get()) {
            return
        }
        val revision = automaticSmartbarMutations.next()
        scope.launch {
            automaticSmartbarMutations.runIfCurrent(revision) {
                if (prefs.smartbar.sharedActionsExpanded.get() == isExpanded) return@runIfCurrent
                sharedActionsAnimationSuppression.suppress(revision, isExpanded)
                prefs.smartbar.sharedActionsExpanded.set(isExpanded)
            }
        }
    }

    internal fun recordSpellingDiagnostic(result: SpellingResult) {
        spellingDiagnostics.record(
            state = result.diagnosticState,
            suggestionCount = result.suggestionsInfo.suggestionsCount,
        )
        _spellingDiagnosticsVersion.update { it + 1L }
    }

    internal fun spellingDiagnosticsSnapshot() = spellingDiagnostics.snapshot()

    internal fun clearSpellingDiagnostics() {
        spellingDiagnostics.clear()
        _spellingDiagnosticsVersion.update { it + 1L }
    }

    private class ProviderInstanceWrapper(val provider: NlpProvider) {
        private val lifecycle = NlpProviderLifecycle()

        suspend fun createIfNecessary() {
            lifecycle.createIfNecessary(provider::create)
        }

        suspend fun preload(subtype: Subtype) {
            provider.preload(subtype)
        }

        suspend fun destroyIfNecessary() {
            lifecycle.destroyIfNecessary(provider::destroy)
        }
    }

    inner class ClipboardSuggestionProvider internal constructor(private val context: Context) : SuggestionProvider {
        @Volatile
        private var suppressedClipboardCopy: ClipboardItem? = null

        override val providerId = "org.florisboard.nlp.providers.clipboard"

        override suspend fun create() {
            // Do nothing
        }

        override suspend fun preload(subtype: Subtype) {
            // Do nothing
        }

        override suspend fun suggest(
            subtype: Subtype,
            content: EditorContent,
            maxCandidateCount: Int,
            allowPossiblyOffensive: Boolean,
            isPrivateSession: Boolean,
        ): List<SuggestionCandidate> {
            if (maxCandidateCount <= 0 || !canUseClipboardSuggestions(isPrivateSession)) {
                return emptyList()
            }

            val currentItem = validateClipboardItem(clipboardManager.primaryClip, suppressedClipboardCopy, content.text)
                ?: return emptyList()
            val now = System.currentTimeMillis()
            if ((now - currentItem.creationTimestampMs) >= prefs.clipboard.suggestionTimeout.get() * 1_000L) {
                return emptyList()
            }

            return buildClipboardSuggestionItems(currentItem, maxCandidateCount).map { item ->
                clipboardSuggestionCandidate(item, currentItem)
            }
        }

        override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
            if (candidate is ClipboardSuggestionCandidate) {
                suppressedClipboardCopy = candidate.sourceClipboardItem
            }
        }

        override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
            // Do nothing
        }

        override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
            if (candidate is ClipboardSuggestionCandidate) {
                suppressedClipboardCopy = candidate.sourceClipboardItem
                return true
            }
            return false
        }

        override suspend fun getListOfWords(subtype: Subtype): List<String> {
            return emptyList()
        }

        override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
            return 0.0
        }

        override suspend fun destroy() {
            // Do nothing
        }

        private fun clipboardSuggestionCandidate(
            item: ClipboardItem,
            source: ClipboardItem,
        ) = ClipboardSuggestionCandidate(
            clipboardItem = item,
            sourceProvider = this,
            context = context,
            sourceClipboardItem = source,
        )

        private fun validateClipboardItem(
            currentItem: ClipboardItem?,
            suppressedCopy: ClipboardItem?,
            contentText: String,
        ) =
            currentItem?.takeIf {
                // Check if already used
                isNewClipboardSuggestionCopy(it, suppressedCopy)
                    // Check if content is empty
                    && contentText.isBlank()
                    // Check if clipboard content has any valid characters
                    && !currentItem.text.isNullOrBlank()
            }
    }
}

private fun SuggestionCandidate.isExternalAutocorrect(): Boolean {
    return sourceProvider?.providerId == AutocorrectPluginManager.ProviderId
}

internal fun <T> selectSmartbarCandidates(
    isWordBeingTyped: Boolean,
    wordCandidates: List<T>,
    clipboardCandidates: List<T>,
): List<T> =
    if (isWordBeingTyped) wordCandidates else clipboardCandidates.ifEmpty { wordCandidates }
