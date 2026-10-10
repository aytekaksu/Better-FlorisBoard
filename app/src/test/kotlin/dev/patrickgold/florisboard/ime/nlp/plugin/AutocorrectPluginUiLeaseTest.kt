/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AutocorrectPluginUiLeaseTest :
    FunSpec({
        test("picker leases only match their active provider lifecycle") {
            val lease = PluginUiPickerLease(
                id = 4,
                providerId = "provider-a",
                lifecycleRevision = 8,
            )
            fun isCurrent(
                activeIds: Set<Long> = setOf(lease.id),
                selectedProviderId: String = "provider-a",
                boundProviderId: String = "provider-a",
                lifecycleRevision: Long = 8,
            ) = isCurrentPluginUiPickerLease(
                lease,
                activeIds,
                selectedProviderId,
                boundProviderId,
                lifecycleRevision,
            )

            isCurrent() shouldBe true
            isCurrent(activeIds = emptySet()) shouldBe false
            isCurrent(selectedProviderId = "provider-b") shouldBe false
            isCurrent(boundProviderId = "provider-b") shouldBe false
            isCurrent(lifecycleRevision = 9) shouldBe false
        }

        test("only the newest UI read can own a reply") {
            val ledger = PluginUiOperationLedger()
            ledger.start(1, PluginUiOperationLedger.Kind.READ)
            ledger.start(2, PluginUiOperationLedger.Kind.READ)

            ledger.owns(1) shouldBe false
            ledger.owns(2) shouldBe true
            ledger.start(3, PluginUiOperationLedger.Kind.MUTATION)
            ledger.owns(2) shouldBe false
            ledger.owns(3) shouldBe true
        }

        test("out-of-order actions keep independent grants and only the latest identity") {
            val ledger = PluginUiOperationLedger()
            ledger.start(10, PluginUiOperationLedger.Kind.ACTION, "provider")
            ledger.start(11, PluginUiOperationLedger.Kind.ACTION, "provider")

            ledger.finish(10)?.kind shouldBe PluginUiOperationLedger.Kind.ACTION
            ledger.grantFor(10) shouldBe null
            ledger.grantFor(11) shouldBe "provider"
            ledger.isLatest(10) shouldBe false
            ledger.isLatest(11) shouldBe true
            ledger.finish(11)
            ledger.owns(11) shouldBe false
            ledger.isLatest(11) shouldBe true // The issued ID remains a stale-reply tombstone.
        }

        test("document invalidation leaves unrelated action work") {
            val ledger = PluginUiOperationLedger()
            ledger.start(20, PluginUiOperationLedger.Kind.ACTION, "provider")
            ledger.start(21, PluginUiOperationLedger.Kind.DOCUMENT)

            ledger.hasDocument shouldBe true
            ledger.invalidateDocuments()
            ledger.hasDocument shouldBe false
            ledger.owns(20) shouldBe true
            ledger.owns(21) shouldBe false
            ledger.grantFor(20) shouldBe "provider"
        }

        test("dictionary grant revocation is scoped to the matching action and provider") {
            val ledger = PluginUiOperationLedger()
            ledger.start(30, PluginUiOperationLedger.Kind.ACTION, "provider-a")
            ledger.start(31, PluginUiOperationLedger.Kind.ACTION, "provider-a")

            ledger.revokeGrant(30, "provider-b")
            ledger.grantFor(30) shouldBe "provider-a"
            ledger.revokeGrant(30, "provider-a")
            ledger.grantFor(30) shouldBe null
            ledger.owns(30) shouldBe true
            ledger.grantFor(31) shouldBe "provider-a"
        }

        test("a failed send or malformed reply retires ownership without resurrecting older work") {
            val ledger = PluginUiOperationLedger()
            ledger.start(40, PluginUiOperationLedger.Kind.ACTION, "provider")
            ledger.start(41, PluginUiOperationLedger.Kind.ACTION, "provider")
            ledger.finish(41) // Simulate a send failure after assigning the latest ID.

            ledger.owns(40) shouldBe true
            ledger.isLatest(40) shouldBe false
            ledger.isLatest(41) shouldBe true
            ledger.clear() // A malformed reply without an ID fails all pending operations.
            ledger.tombstone(42)
            ledger.hasPending shouldBe false
            ledger.actionGrantIds() shouldBe emptySet()
            ledger.isLatest(40) shouldBe false
            ledger.isLatest(42) shouldBe true
        }
    })
