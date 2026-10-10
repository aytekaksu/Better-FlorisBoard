/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.kotlin.io

import kotlinx.serialization.StringFormat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Public typealias for [java.io.File]. As a file object can either be a file
 * or a directory, this typealias allows you to be more verbose on what you
 * expect, in this case a file system directory.
 */
typealias FsDir = java.io.File

/**
 * Public typealias for [java.io.File]. As a file object can either be a file
 * or a directory, this typealias allows you to be more verbose on what you
 * expect, in this case a file system file.
 */
typealias FsFile = java.io.File

@Suppress("NOTHING_TO_INLINE")
inline fun FsDir.subDir(relPath: String) = FsDir(this, relPath)

@Suppress("NOTHING_TO_INLINE")
inline fun FsDir.subFile(relPath: String) = FsFile(this, relPath)

inline fun <reified T> FsFile.writeJson(value: T, config: StringFormat = Json) {
    val text = config.encodeToString(value)
    return this.writeText(text)
}
