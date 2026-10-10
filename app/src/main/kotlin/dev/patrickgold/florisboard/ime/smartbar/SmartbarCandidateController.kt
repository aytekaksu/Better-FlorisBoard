/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.smartbar

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.CandidateRevision
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.MAX_CLIPBOARD_SUGGESTION_CANDIDATES
import dev.patrickgold.florisboard.ime.nlp.MAX_CLIPBOARD_SUGGESTION_RAW_MATCHES
import dev.patrickgold.florisboard.ime.nlp.MAX_CLIPBOARD_SUGGESTION_SCAN_CHARS
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidateKind
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.isNewClipboardSuggestionCopy
import dev.patrickgold.florisboard.ime.nlp.plugin.AutocorrectPluginManager
import dev.patrickgold.florisboard.lib.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.systemService
import org.florisboard.lib.kotlin.collectLatestIn
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

internal data class ClipboardSuggestionMatch(val value: String, val range: IntRange) {
    override fun toString() = "ClipboardSuggestionMatch(value=<redacted>)"
}

internal fun findClipboardSuggestionMatches(text: CharSequence, maxMatches: Int): List<ClipboardSuggestionMatch> {
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
                    (previous.range.first <= match.range.last && match.range.first <= previous.range.last)
            }
            previousMatches += match
            if (match.value != boundedText && isUnique) {
                add(ClipboardSuggestionMatch(match.value, match.range))
                if (size == limit) break
            }
        }
    }
}

internal fun buildClipboardSuggestionItems(source: ClipboardItem, maxCandidateCount: Int): List<ClipboardItem> {
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

/** Assembles and publishes the Smartbar row without owning provider requests. */
internal class SmartbarCandidateController(
    private val context: Context,
    private val prefs: FlorisPreferenceModel,
    clipboardPrimaryClipFlow: Lazy<StateFlow<ClipboardItem?>>,
    private val inlineSuggestionsFlow: StateFlow<List<*>>,
    private val scope: CoroutineScope,
    private val isSuggestionOn: () -> Boolean,
    private val currentEditorContent: () -> EditorContent,
    private val isIncognitoMode: () -> Boolean,
    private val sharedActions: SharedActionsController,
) {
    private val primaryClipFlow by clipboardPrimaryClipFlow
    private val keyguardManager = context.systemService(AndroidKeyguardManager::class)
    private val clipboardSuggestionProvider = ClipboardSuggestionProvider()
    private val candidateAssemblyRevision = CandidateRevision()
    private val internalSuggestionsGuard = Any()
    private var internalSuggestions by Delegates.observable(listOf<SuggestionCandidate>()) { _, _, _ ->
        scope.launch { refresh() }
    }

    private val _activeCandidatesFlow = MutableStateFlow(listOf<SuggestionCandidate>())
    val activeCandidatesFlow = _activeCandidatesFlow.asStateFlow()

    @Volatile
    var autoCommitCandidate: SuggestionCandidate? = null
        private set

    init {
        primaryClipFlow.collectLatestIn(scope) { refresh() }
        inlineSuggestionsFlow.collectLatestIn(scope) { refresh() }
        prefs.smartbar.enabled.asFlow().collectLatestIn(scope) { refresh() }
        prefs.clipboard.suggestionEnabled.asFlow().collectLatestIn(scope) { refresh() }
        prefs.emoji.suggestionEnabled.asFlow().collectLatestIn(scope) { refresh() }
    }

    fun onTyping(content: EditorContent) {
        if (content.currentWordText.isNotBlank() && !content.selection.isSelectionMode) {
            sharedActions.collapseForTyping()
        }
    }

    fun replaceCandidates(suggestions: List<SuggestionCandidate>) = replaceCandidates { suggestions }

    fun replaceCandidates(buildCandidates: () -> List<SuggestionCandidate>) {
        synchronized(internalSuggestionsGuard) {
            internalSuggestions = buildCandidates()
        }
    }

    fun clearCandidates() {
        synchronized(internalSuggestionsGuard) {
            internalSuggestions = emptyList()
            candidateAssemblyRevision.next { publishCandidates(emptyList()) }
        }
    }

    private fun publishCandidates(candidates: List<SuggestionCandidate>) {
        val visibleCandidates = candidates.filter(SuggestionCandidate::isVisible)
        autoCommitCandidate = candidates.firstOrNull { it.isEligibleForAutoCommit }
        _activeCandidatesFlow.value = visibleCandidates
        sharedActions.update(visibleCandidates, inlineSuggestionsFlow.value)
    }

    suspend fun refresh() {
        val revision = candidateAssemblyRevision.next()
        val candidates = when {
            isSuggestionOn() -> {
                val content = currentEditorContent()
                val wordCandidates = synchronized(internalSuggestionsGuard) {
                    internalSuggestions
                }
                val clipboardCandidates = clipboardSuggestionProvider.suggest(
                    subtype = Subtype.DEFAULT,
                    content = content,
                    maxCandidateCount = 8,
                    allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                    isPrivateSession = isIncognitoMode(),
                )
                val isWordBeingTyped = content.currentWordText.isNotBlank() ||
                    wordCandidates.any {
                        it.isExternalAutocorrect() && it.kind != SuggestionCandidateKind.NEXT_WORD
                    }
                if (isWordBeingTyped) wordCandidates else clipboardCandidates.ifEmpty { wordCandidates }
            }

            else -> emptyList()
        }
        candidateAssemblyRevision.publishIfCurrent(revision) {
            val publishableCandidates = if (canUseClipboardSuggestions(
                    isIncognitoMode(),
                )
            ) {
                candidates
            } else {
                candidates.filterNot { it is ClipboardSuggestionCandidate }
            }
            publishCandidates(publishableCandidates)
        }
    }

    private fun canUseClipboardSuggestions(isPrivateSession: Boolean): Boolean =
        prefs.clipboard.suggestionEnabled.get() &&
            !isPrivateSession &&
            runCatching {
                !keyguardManager.isDeviceLocked && !keyguardManager.isKeyguardLocked
            }.getOrDefault(false)

    private inner class ClipboardSuggestionProvider : SuggestionProvider {
        @Volatile
        private var suppressedClipboardCopy: ClipboardItem? = null

        override val providerId = "org.florisboard.nlp.providers.clipboard"

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

            val currentItem = validateClipboardItem(primaryClipFlow.value, suppressedClipboardCopy, content.text)
                ?: return emptyList()
            val now = System.currentTimeMillis()
            if ((now - currentItem.creationTimestampMs) >=
                TimeUnit.SECONDS.toMillis(prefs.clipboard.suggestionTimeout.get().toLong())
            ) {
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

        override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
            if (candidate is ClipboardSuggestionCandidate) {
                suppressedClipboardCopy = candidate.sourceClipboardItem
                return true
            }
            return false
        }

        private fun clipboardSuggestionCandidate(item: ClipboardItem, source: ClipboardItem) =
            ClipboardSuggestionCandidate(
                clipboardItem = item,
                sourceProvider = this,
                context = context,
                sourceClipboardItem = source,
            )

        private fun validateClipboardItem(
            currentItem: ClipboardItem?,
            suppressedCopy: ClipboardItem?,
            contentText: String,
        ) = currentItem?.takeIf {
            isNewClipboardSuggestionCopy(it, suppressedCopy) &&
                contentText.isBlank() &&
                !it.text.isNullOrBlank()
        }
    }
}

private fun SuggestionCandidate.isExternalAutocorrect(): Boolean =
    sourceProvider?.providerId == AutocorrectPluginManager.ProviderId
