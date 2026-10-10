/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.value

import org.florisboard.lib.kotlin.toStringWithoutDotZero

typealias SnyggIdToValueMap = MutableMap<String, String>

fun snyggIdToValueMapOf(vararg pairs: Pair<String, Any>): SnyggIdToValueMap {
    val map = mutableMapOf<String, String>()
    map.add(*pairs)
    return map
}

fun SnyggIdToValueMap.getInt(id: String): Int {
    return getValue(id).toInt()
}

fun SnyggIdToValueMap.getFloat(id: String): Float {
    return getValue(id).toFloat()
}

fun SnyggIdToValueMap.getString(id: String): String {
    return getValue(id)
}

fun SnyggIdToValueMap.add(vararg pairs: Pair<String, Any>) {
    pairs.forEach { (id, value) ->
        if (value is Number) {
            put(id, value.toStringWithoutDotZero())
        } else {
            put(id, value.toString())
        }
    }
}
