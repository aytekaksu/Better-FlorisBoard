/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.autocorrect.api

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocorrectContractIdentifierTest {
    @Test
    fun messageIdentifiersAreUniqueAndUseSeparateRequestAndResponseBands() {
        val messages = AutocorrectPluginContract::class.java.fields
            .filter { it.name.startsWith("MSG_") }
            .associate { it.name to it.getInt(null) }
        val responseMessages = setOf(
            "MSG_SUGGESTIONS",
            "MSG_REMOVE_RESULT",
            "MSG_PLUGIN_UI_RESULT",
            "MSG_FINISH_SESSION_RESULT",
            "MSG_HOST_USER_DICTIONARY_REQUEST",
        )

        assertEquals(messages.size, messages.values.toSet().size)
        assertEquals(responseMessages, messages.filterValues { it >= 100 }.keys)
        assertTrue(
            messages.filterKeys { it !in responseMessages }
                .values
                .all { it in 1..99 },
        )
        assertTrue(responseMessages.map(messages::getValue).all { it in 100..199 })
    }

    @Test
    fun discoveryIdentifiersAreNamespacedAndUnique() {
        val identifiers = AutocorrectPluginContract::class.java.fields
            .filter { it.name.startsWith("ACTION_") || it.name.startsWith("META_") }
            .associate { it.name to it.get(null) as String }

        assertEquals(identifiers.size, identifiers.values.toSet().size)
        assertTrue(identifiers.values.all { it.startsWith("org.florisboard.autocorrect.api.") })
    }

    @Test
    fun fieldIdentifiersAreUniqueWithinEveryWireObject() {
        listOf(
            "org.florisboard.autocorrect.api.Keys",
            "org.florisboard.autocorrect.api.TraceKeys",
            "org.florisboard.autocorrect.api.UiKeys",
            "org.florisboard.autocorrect.api.DictionaryKeys",
        ).forEach { className ->
            val fields = Class.forName(className).declaredFields
                .filter {
                    it.type == String::class.java &&
                        Modifier.isStatic(it.modifiers) &&
                        Modifier.isFinal(it.modifiers)
                }
                .associate { field ->
                    field.isAccessible = true
                    field.name to field.get(null) as String
                }
            val duplicates = fields.entries
                .groupBy(Map.Entry<String, String>::value)
                .filterValues { it.size > 1 }

            assertTrue("$className has duplicate field identifiers: $duplicates", duplicates.isEmpty())
        }
    }
}
