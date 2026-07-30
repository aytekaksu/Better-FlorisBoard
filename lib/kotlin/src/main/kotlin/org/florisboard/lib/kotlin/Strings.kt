/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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
