/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.value

private const val VarKey = "varKey"

sealed interface SnyggVarValue : SnyggValue {
    companion object {
        val VariableNameRegex = """--[a-zA-Z0-9-]+""".toRegex()
    }
}

data class SnyggDefinedVarValue(val key: String) : SnyggVarValue {
    companion object : SnyggValueEncoder {
        override val spec = SnyggValueSpec {
            function(name = "var") { string(id = VarKey, regex = SnyggVarValue.VariableNameRegex) }
        }

        override fun defaultValue() = SnyggDefinedVarValue("")

        override fun serialize(v: SnyggValue) = encodeValue<SnyggDefinedVarValue>(v) {
            snyggIdToValueMapOf(VarKey to key)
        }

        override fun deserialize(v: String) = decodeValue(v) {
            SnyggDefinedVarValue(getString(VarKey))
        }
    }

    override fun encoder() = Companion
}
