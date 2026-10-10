/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.popup

import dev.patrickgold.florisboard.ime.keyboard.AbstractKeyData
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.lib.ext.ExtensionComponent
import kotlinx.serialization.Serializable

/**
 * An object which maps each base key to its extended popups. This can be done for each
 * key variation. [KeyVariation.ALL] is always the fallback for each key.
 */
typealias PopupMapping = Map<KeyVariation, Map<String, PopupSet<AbstractKeyData>>>

@Serializable
data class PopupMappingComponent(
    override val id: String,
    override val label: String = id,
    override val authors: List<String>,
    val mappingFile: String? = null,
) : ExtensionComponent {
    fun mappingFile() = mappingFile ?: "popupMappings/$id.json"
}
