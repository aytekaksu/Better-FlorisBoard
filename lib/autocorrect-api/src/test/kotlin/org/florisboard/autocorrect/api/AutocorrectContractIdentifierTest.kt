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
