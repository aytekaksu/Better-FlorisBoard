/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.lib.ext.ExtensionComponent
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import kotlinx.serialization.Serializable

typealias LayoutArrangement = List<List<AbstractKeyData>>

@Serializable
data class LayoutArrangementComponent(
    override val id: String,
    override val label: String,
    override val authors: List<String>,
    val direction: String,
    val modifier: ExtensionComponentName? = null,
    val arrangementFile: String? = null,
) : ExtensionComponent {
    fun arrangementFile(type: LayoutType) = arrangementFile ?: "layouts/${type.id}/$id.json"
}
