/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar

import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal data class SharedActionsAnimationSuppression(val revision: Long, val targetExpanded: Boolean)

/** Orders shared-action changes and suppresses automatic animation until the UI acknowledges it. */
internal class SharedActionsController(
    private val prefs: FlorisPreferenceModel.Smartbar,
    private val scope: CoroutineScope,
    private val isSelectionMode: () -> Boolean,
) {
    private val revision = AtomicLong()
    private val mutationGuard = Mutex()
    private val mutableSuppression = MutableStateFlow<SharedActionsAnimationSuppression?>(null)
    val animationSuppression = mutableSuppression.asStateFlow()

    fun update(candidates: List<*>?, inlineSuggestions: List<*>?) {
        val isSelection = isSelectionMode()
        setExpanded(
            (candidates.isNullOrEmpty() && inlineSuggestions.isNullOrEmpty()) || isSelection,
            byUser = false,
        )
    }

    fun collapseForTyping() = setExpanded(false, byUser = false)

    fun setExpandedByUser(isExpanded: Boolean) = setExpanded(isExpanded, byUser = true)

    fun acknowledge(suppression: SharedActionsAnimationSuppression) {
        mutableSuppression.compareAndSet(suppression, null)
    }

    private fun setExpanded(isExpanded: Boolean, byUser: Boolean) {
        if (!byUser && !prefs.enabled.get()) return
        // Retire older queued work before this coroutine can start.
        val expectedRevision = revision.incrementAndGet()
        scope.launch {
            // Keep suppression and the suspend preference write in the same ordered change.
            mutationGuard.withLock {
                if (expectedRevision != revision.get()) return@withLock
                if (byUser) {
                    mutableSuppression.value = null
                } else {
                    if (prefs.sharedActionsExpanded.get() == isExpanded) return@withLock
                    mutableSuppression.value = SharedActionsAnimationSuppression(expectedRevision, isExpanded)
                }
                prefs.sharedActionsExpanded.set(isExpanded)
            }
        }
    }
}
