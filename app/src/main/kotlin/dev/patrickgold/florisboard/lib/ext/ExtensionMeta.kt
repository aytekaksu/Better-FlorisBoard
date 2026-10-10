/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.ext

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Metadata in `extension.json` for bundled asset directories and installed `.flex` packages. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionMeta(
    /** Package ID; the import validator checks its syntax and length. */
    val id: String,

    val version: String,

    /** Settings title. Recommended length: up to 50 characters. */
    val title: String,

    /** Package summary. Recommended length: up to 80 characters. */
    val description: String? = null,

    /** Package keywords, shown in extension details. Recommended length: up to 30 characters each. */
    val keywords: List<String>? = null,

    /** Extension or author's homepage. */
    val homepage: String? = null,

    /** Extension issue tracker. */
    val issueTracker: String? = null,

    /**
     * Package maintainers, separate from each component's authors.
     * Format: `Name <email> (URL)`. Name is required; email and URL are optional, in that order.
     */
    @JsonNames("authors")
    val maintainers: List<ExtensionMaintainer>,

    /** [SPDX](https://spdx.org/licenses/) license ID, or an expression for multiple licenses. */
    val license: String,
) {
    fun getUpdateJsonPair(): String {
        return "\"${id}\":\"${version}\""
    }
}
