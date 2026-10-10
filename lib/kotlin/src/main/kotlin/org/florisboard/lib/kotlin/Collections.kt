/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.kotlin

@Throws(NoSuchElementException::class)
fun <K, V> Map<K, V>.getKeyByValue(value: V): K {
    for ((k, v) in this.entries) {
        if (value == v) return k
    }
    throw NoSuchElementException("Value $value is missing in the map.")
}
