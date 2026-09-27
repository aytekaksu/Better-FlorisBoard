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

package dev.patrickgold.florisboard.lib.io

import java.nio.charset.StandardCharsets

private val DRIVE_PREFIX = Regex("""^[A-Za-z]:""")

/** Checks portable path syntax only; callers still enforce archive metadata and filesystem safety. */
internal fun parsePortablePathSegments(
    rawPath: String,
    maxPathBytes: Int,
    maxSegmentBytes: Int,
    directory: Boolean = false,
    maxDepth: Int = Int.MAX_VALUE,
    rejectBlankSegments: Boolean = false,
): List<String>? {
    if (!isPortableRawPath(rawPath, maxPathBytes, directory)) return null

    val path = if (directory) rawPath.removeSuffix("/") else rawPath
    val segments = path.split('/')
    if (segments.size > maxDepth || segments.any { isUnsafeSegment(it, maxSegmentBytes, rejectBlankSegments) }) {
        return null
    }
    return segments
}

private fun isPortableRawPath(rawPath: String, maxPathBytes: Int, directory: Boolean): Boolean {
    if (rawPath.isEmpty() || rawPath.length > maxPathBytes ||
        rawPath.toByteArray(StandardCharsets.UTF_8).size > maxPathBytes || rawPath.startsWith('/')
    ) {
        return false
    }
    if (DRIVE_PREFIX.containsMatchIn(rawPath) || directory != rawPath.endsWith('/') ||
        '\\' in rawPath || rawPath.any(Char::isISOControl)
    ) {
        return false
    }
    return true
}

private fun isUnsafeSegment(segment: String, maxSegmentBytes: Int, rejectBlankSegments: Boolean): Boolean {
    if (segment.isEmpty() || segment == "." || segment == "..") return true
    if (rejectBlankSegments && segment.isBlank()) return true
    return segment.length > maxSegmentBytes ||
        segment.toByteArray(StandardCharsets.UTF_8).size > maxSegmentBytes
}
