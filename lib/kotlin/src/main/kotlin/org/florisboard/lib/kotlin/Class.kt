/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.kotlin

import kotlin.reflect.KClass

fun KClass<*>.simpleNameOrEnclosing(): String? {
    return if (this.simpleName == "Companion") {
        // Companion object => get the enclosing class
        this.java.enclosingClass.simpleName
    } else {
        // Normal object => directly get class
        this.simpleName
    }
}
