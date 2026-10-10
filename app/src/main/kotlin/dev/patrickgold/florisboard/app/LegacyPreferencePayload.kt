/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app

import kotlinx.serialization.json.Json

internal class LegacyPreferencePayload private constructor(private val raw: String, val entries: List<Entry>) {
    fun hasKey(key: String): Boolean = entries.any { it.key == key }

    fun lastInt(key: String): Int? = entries.lastOrNull {
        it.type == INT_TYPE && it.key == key
    }?.rawValue?.toIntOrNull()

    fun lastBoolean(key: String): Boolean? = entries.lastOrNull {
        it.type == BOOLEAN_TYPE && it.key == key
    }?.booleanValue()

    fun lastString(key: String): String? = entries.lastOrNull {
        it.type == STRING_TYPE && it.key == key
    }?.stringValue()

    fun append(additions: List<String>): String {
        if (additions.isEmpty()) return raw
        return buildString(raw.length + additions.sumOf(String::length) + additions.size + 1) {
            append(raw)
            if (raw.isNotEmpty() && raw.last() != '\n' && raw.last() != '\r') {
                append('\n')
            }
            additions.forEach {
                append(it)
                append('\n')
            }
        }
    }

    data class Entry(val type: String, val key: String, val rawValue: String, val rawLine: String) {
        fun booleanValue(): Boolean? = when (rawValue) {
            "true" -> true
            "false" -> false
            else -> null
        }

        fun stringValue(): String? = try {
            Json.decodeFromString<String>(rawValue.trim())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val BOOLEAN_TYPE = "b"
        private const val INT_TYPE = "i"
        private const val STRING_TYPE = "s"

        fun parse(raw: String): LegacyPreferencePayload {
            val entries = raw.lineSequence().mapNotNull(::parseEntry).toList()
            return LegacyPreferencePayload(raw, entries)
        }

        fun stringEntry(key: String, value: String): String = "$STRING_TYPE;$key;${Json.encodeToString(value)}"

        private fun parseEntry(line: String): Entry? {
            if (line.isBlank()) return null
            val typeEnd = line.indexOf(';')
            if (typeEnd < 0) return null
            val keyEnd = line.indexOf(';', startIndex = typeEnd + 1)
            if (keyEnd < 0) return null
            return Entry(
                type = line.substring(0, typeEnd),
                key = line.substring(typeEnd + 1, keyEnd),
                rawValue = line.substring(keyEnd + 1),
                rawLine = line,
            )
        }
    }
}

internal data class LegacyPreferenceSource(
    val versionCode: Int?,
    val versionName: String?,
    val versionLastUse: String?,
    val versionOnInstall: String?,
)
