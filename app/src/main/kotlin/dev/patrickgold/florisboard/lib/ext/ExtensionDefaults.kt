/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.ext

object ExtensionDefaults {
    const val FILE_EXTENSION = "flex"
    const val MANIFEST_FILE_NAME = "extension.json"

    fun createFlexName(id: String) = "$id.$FILE_EXTENSION"
}
