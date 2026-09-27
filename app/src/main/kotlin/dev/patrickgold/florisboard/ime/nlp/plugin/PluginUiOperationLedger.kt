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

package dev.patrickgold.florisboard.ime.nlp.plugin

/** Request identity and dictionary grants for one provider UI lifecycle. Caller owns synchronization. */
internal class PluginUiOperationLedger {
    enum class Kind { READ, MUTATION, ACTION, DOCUMENT }

    data class Operation(val kind: Kind, val grantProviderId: String? = null)

    private val pending = mutableMapOf<Long, Operation>()
    var latestId = -1L
        private set

    val hasPending get() = pending.isNotEmpty()
    val hasDocument get() = pending.values.any { it.kind == Kind.DOCUMENT }

    fun start(id: Long, kind: Kind, grantProviderId: String? = null) {
        require((kind == Kind.ACTION) == (grantProviderId != null))
        pending.entries.removeAll { it.value.kind == Kind.READ }
        latestId = id
        pending[id] = Operation(kind, grantProviderId)
    }

    fun finish(id: Long): Operation? = pending.remove(id)
    fun owns(id: Long) = id in pending
    fun isLatest(id: Long) = id == latestId
    fun tombstone(id: Long) { latestId = id }
    fun clear() = pending.clear()
    fun invalidateDocuments() = pending.entries.removeAll { it.value.kind == Kind.DOCUMENT }
    fun grantFor(id: Long) = pending[id]?.grantProviderId

    fun revokeGrant(id: Long, providerId: String) {
        val operation = pending[id] ?: return
        if (operation.grantProviderId == providerId) {
            pending[id] = operation.copy(grantProviderId = null)
        }
    }

    fun pendingMutationIds() = pending.filterValues { it.kind != Kind.READ }.keys.toSet()
    fun actionGrantIds() = pending.filterValues { it.grantProviderId != null }.keys.toSet()
}
