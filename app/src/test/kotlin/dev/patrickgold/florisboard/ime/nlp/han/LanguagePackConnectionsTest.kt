/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.nlp.han

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class LanguagePackConnectionsTest :
    FunSpec({
        test("retained usable packs are not loaded again") {
            val pack = TestPack("pack")
            val usable = mutableSetOf<TestPack>()
            val events = mutableListOf<String>()
            val connections = testConnections(usable, events)

            connections.replace(listOf(pack)) shouldBe setOf(pack)
            connections.replace(listOf(pack)) shouldBe setOf(pack)

            events shouldContainExactly listOf("connect:pack")
        }

        test("retained unusable packs are loaded again") {
            val pack = TestPack("pack")
            val usable = mutableSetOf<TestPack>()
            val events = mutableListOf<String>()
            val connections = testConnections(usable, events)
            connections.replace(listOf(pack))
            usable.clear()
            events.clear()

            connections.replace(listOf(pack)) shouldBe setOf(pack)

            events shouldContainExactly listOf("connect:pack")
        }

        test("failed packs are cleaned and never published as connected") {
            val usable = mutableSetOf<TestPack>()
            val events = mutableListOf<String>()
            val accepted = TestPack("accepted")
            val rejected = TestPack("rejected")
            val connections = testConnections(
                usable = usable,
                events = events,
                canConnect = { it !== rejected },
            )

            connections.replace(listOf(accepted, rejected)) shouldBe setOf(accepted)

            events shouldContainExactly listOf(
                "connect:accepted",
                "connect:rejected",
                "disconnect:rejected",
            )
        }

        test("replaced instances unload before the replacement loads") {
            val usable = mutableSetOf<TestPack>()
            val events = mutableListOf<String>()
            val old = TestPack("old")
            val replacement = TestPack("replacement")
            val connections = testConnections(usable, events)
            connections.replace(listOf(old))
            events.clear()

            connections.replace(listOf(replacement)) shouldBe setOf(replacement)

            events shouldContainExactly listOf(
                "disconnect:old",
                "connect:replacement",
            )
        }

        test("clear unloads each connected pack once and is idempotent") {
            val usable = mutableSetOf<TestPack>()
            val events = mutableListOf<String>()
            val first = TestPack("first")
            val second = TestPack("second")
            val connections = testConnections(usable, events)
            connections.replace(listOf(first, second))
            events.clear()

            connections.clear()
            connections.clear()

            events shouldContainExactly listOf(
                "disconnect:first",
                "disconnect:second",
            )
        }
    })

private class TestPack(val name: String)

private fun testConnections(
    usable: MutableSet<TestPack>,
    events: MutableList<String>,
    canConnect: (TestPack) -> Boolean = { true },
) = LanguagePackConnections(
    isUsable = usable::contains,
    connect = { pack ->
        events += "connect:${pack.name}"
        canConnect(pack).also { connected ->
            if (connected) {
                usable += pack
            }
        }
    },
    disconnect = { pack ->
        events += "disconnect:${pack.name}"
        usable -= pack
    },
)
