/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
