/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.kotlin

fun String.safeSubstring(startIndex: Int): String {
    return try {
        this.substring(startIndex)
    } catch (_: IndexOutOfBoundsException) {
        ""
    }
}

fun String.safeSubstring(startIndex: Int, endIndex: Int): String {
    return try {
        this.substring(startIndex, endIndex)
    } catch (_: IndexOutOfBoundsException) {
        ""
    }
}

private val curlyArgRegex = """\{([^{}]*)\}""".toRegex()

typealias CurlyArg = Pair<String, Any?>

fun String.curlyFormat(argValueFactory: (argName: String) -> String?): String {
    return curlyArgRegex.replace(this) { match ->
        argValueFactory(match.groupValues[1]) ?: match.value
    }
}

fun String.curlyFormat(vararg args: CurlyArg): String {
    return this.curlyFormat(args.asList())
}

fun String.curlyFormat(args: List<CurlyArg>): String {
    if (args.isEmpty()) return this
    val values = mutableMapOf<String, String>()
    for ((n, arg) in args.withIndex()) {
        val (argName, argValue) = arg
        values.putIfAbsent(n.toString(), argValue.toString())
        if (argName.isNotBlank()) {
            values.putIfAbsent(argName, argValue.toString())
        }
    }
    return curlyFormat(values::get)
}
