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
