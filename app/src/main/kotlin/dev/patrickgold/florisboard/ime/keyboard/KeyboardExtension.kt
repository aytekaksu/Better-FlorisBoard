/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.core.SubtypePreset
import dev.patrickgold.florisboard.ime.nlp.PunctuationRule
import dev.patrickgold.florisboard.ime.popup.PopupMappingComponent
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionComponent
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.ext.ExtensionMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SerialName(KeyboardExtension.SERIAL_TYPE)
@Serializable
data class KeyboardExtension(
    override val meta: ExtensionMeta,
    override val dependencies: List<String>? = null,
    val composers: List<Composer> = listOf(),
    val currencySets: List<CurrencySet> = listOf(),
    val layouts: Map<String, List<LayoutArrangementComponent>> = mapOf(),
    val punctuationRules: List<PunctuationRule> = listOf(),
    val popupMappings: List<PopupMappingComponent> = listOf(),
    val subtypePresets: List<SubtypePreset> = listOf(),
) : Extension() {

    companion object {
        const val SERIAL_TYPE = "ime.extension.keyboard"
    }

    override fun serialType() = SERIAL_TYPE

    override fun components(): List<ExtensionComponent> {
        return emptyList()
    }

}

@Suppress("NOTHING_TO_INLINE")
inline fun extCoreComposer(id: String): ExtensionComponentName {
    return ExtensionComponentName(
        extensionId = "org.florisboard.composers",
        componentId = id,
    )
}

@Suppress("NOTHING_TO_INLINE")
inline fun extCoreCurrencySet(id: String): ExtensionComponentName {
    return ExtensionComponentName(
        extensionId = "org.florisboard.currencysets",
        componentId = id,
    )
}

@Suppress("NOTHING_TO_INLINE")
inline fun extCoreLayout(id: String): ExtensionComponentName {
    return ExtensionComponentName(
        extensionId = "org.florisboard.layouts",
        componentId = id,
    )
}

@Suppress("NOTHING_TO_INLINE")
inline fun extCorePunctuationRule(id: String): ExtensionComponentName {
    return ExtensionComponentName(
        extensionId = "org.florisboard.localization",
        componentId = id,
    )
}

@Suppress("NOTHING_TO_INLINE")
inline fun extCorePopupMapping(id: String): ExtensionComponentName {
    return ExtensionComponentName(
        extensionId = "org.florisboard.localization",
        componentId = id,
    )
}
