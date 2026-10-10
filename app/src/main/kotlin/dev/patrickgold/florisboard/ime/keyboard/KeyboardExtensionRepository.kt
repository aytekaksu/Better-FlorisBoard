/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import android.content.Context
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.core.SubtypePreset
import dev.patrickgold.florisboard.ime.nlp.PunctuationRule
import dev.patrickgold.florisboard.ime.popup.PopupMappingComponent
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.ext.ExtensionIndexState
import dev.patrickgold.florisboard.lib.io.FlorisRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.florisboard.lib.kotlin.collectIn

/** All keyboard-extension metadata is published together for one refresh generation. */
data class KeyboardExtensionSnapshot(
    val generation: Long,
    val sourceRefs: Map<String, FlorisRef?>,
    val composers: Map<ExtensionComponentName, Composer>,
    val currencySets: Map<ExtensionComponentName, CurrencySet>,
    val layouts: Map<LayoutType, Map<ExtensionComponentName, LayoutArrangementComponent>>,
    val popupMappings: Map<ExtensionComponentName, PopupMappingComponent>,
    val punctuationRules: Map<ExtensionComponentName, PunctuationRule>,
    val subtypePresets: List<SubtypePreset>,
)

class KeyboardExtensionRepository internal constructor(
    keyboardExtensions: StateFlow<ExtensionIndexState<KeyboardExtension>>,
    scope: CoroutineScope,
) {
    constructor(context: Context) : this(
        context.extensionManager().value.keyboardExtensions.refreshed,
        CoroutineScope(Dispatchers.Default + SupervisorJob()),
    )

    private val indexedSnapshot = MutableStateFlow(indexKeyboardExtensions(emptyList(), 0))
    val snapshot: StateFlow<KeyboardExtensionSnapshot> = indexedSnapshot.asStateFlow()

    init {
        keyboardExtensions.collectIn(scope) { state ->
            indexedSnapshot.value = indexKeyboardExtensions(state.extensions, state.generation)
        }
    }
}

internal fun indexKeyboardExtensions(
    extensions: List<KeyboardExtension>,
    generation: Long,
): KeyboardExtensionSnapshot {
    val sourceRefs = mutableMapOf<String, FlorisRef?>()
    val composers = mutableMapOf<ExtensionComponentName, Composer>()
    val currencySets = mutableMapOf<ExtensionComponentName, CurrencySet>()
    val layouts = LayoutType.entries.associateWith {
        mutableMapOf<ExtensionComponentName, LayoutArrangementComponent>()
    }
    val popupMappings = mutableMapOf<ExtensionComponentName, PopupMappingComponent>()
    val punctuationRules = mutableMapOf<ExtensionComponentName, PunctuationRule>()
    val subtypePresets = mutableListOf<SubtypePreset>()

    for (extension in extensions) {
        if (extension.meta.id !in sourceRefs) sourceRefs[extension.meta.id] = extension.sourceRef
        extension.composers.forEach { composers[ExtensionComponentName(extension.meta.id, it.id)] = it }
        extension.currencySets.forEach { currencySets[ExtensionComponentName(extension.meta.id, it.id)] = it }
        for ((type, components) in extension.layouts) {
            val entries = layouts[LayoutType.fromId(type)] ?: continue
            components.forEach { entries[ExtensionComponentName(extension.meta.id, it.id)] = it }
        }
        extension.popupMappings.forEach { popupMappings[ExtensionComponentName(extension.meta.id, it.id)] = it }
        extension.punctuationRules.forEach { punctuationRules[ExtensionComponentName(extension.meta.id, it.id)] = it }
        subtypePresets.addAll(extension.subtypePresets)
    }
    subtypePresets.sortBy { it.locale.displayName() }
    for (languageCode in listOf("en-CA", "en-AU", "en-UK", "en-US")) {
        val index = subtypePresets.indexOfFirst { it.locale.languageTag() == languageCode }
        if (index > 0) subtypePresets.add(0, subtypePresets.removeAt(index))
    }

    return KeyboardExtensionSnapshot(
        generation = generation,
        sourceRefs = sourceRefs.toMap(),
        composers = composers.toMap(),
        currencySets = currencySets.toMap(),
        layouts = layouts.mapValues { (_, entries) -> entries.toMap() },
        popupMappings = popupMappings.toMap(),
        punctuationRules = punctuationRules.toMap(),
        subtypePresets = subtypePresets.toList(),
    )
}
