/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.theme

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName(ThemeExtension.SERIAL_TYPE)
@Serializable
class ThemeExtension(
    override val meta: ExtensionMeta,
    override val dependencies: List<String>? = null,
    val themes: List<ThemeExtensionComponentImpl>,
) : Extension() {

    companion object {
        const val SERIAL_TYPE = "ime.extension.theme"
    }

    override fun serialType() = SERIAL_TYPE

    override fun components() = themes

    fun edit() = ThemeExtensionEditor(
        meta = meta,
        dependencies = dependencies?.toMutableList() ?: mutableListOf(),
        themes = mutableStateListOf(*themes.map { it.edit() }.toTypedArray()),
    )
}

class ThemeExtensionEditor(
    var meta: ExtensionMeta,
    val dependencies: MutableList<String>,
    val themes: SnapshotStateList<ThemeExtensionComponentEditor>,
) {

    fun build() = ThemeExtension(
        meta = meta,
        dependencies = dependencies.takeUnless { it.isEmpty() }?.toList(),
        themes = themes.map { it.build() },
    )
}
