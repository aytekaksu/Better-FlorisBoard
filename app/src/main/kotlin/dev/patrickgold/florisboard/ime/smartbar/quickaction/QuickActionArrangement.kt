/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.lib.io.DefaultJsonConfig
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic

val QuickActionJsonConfig = Json(DefaultJsonConfig) {
    classDiscriminator = "$"
    encodeDefaults = false
    ignoreUnknownKeys = true
    isLenient = false

    serializersModule += SerializersModule {
        polymorphic(QuickAction::class) {
            subclass(QuickAction.InsertKey::class, QuickAction.InsertKey.serializer())
            subclass(QuickAction.InsertText::class, QuickAction.InsertText.serializer())
            defaultDeserializer { QuickAction.InsertKey.serializer() }
        }
    }
}

@Serializable
data class QuickActionArrangement(
    val stickyAction: QuickAction?,
    val dynamicActions: List<QuickAction>,
    val hiddenActions: List<QuickAction>,
) {
    internal fun overflowActions(shownDynamicActionsCount: Int): List<QuickAction> =
        dynamicActions.drop(shownDynamicActionsCount.coerceAtLeast(0))

    operator fun contains(action: QuickAction): Boolean =
        stickyAction == action || dynamicActions.contains(action) || hiddenActions.contains(action)

    fun distinct(): QuickActionArrangement {
        val distinctSet = mutableSetOf<QuickAction>()
        val normalizedStickyAction = stickyAction?.normalized()
        if (normalizedStickyAction != null) {
            distinctSet.add(normalizedStickyAction)
        }
        val distinctDynamicActions = dynamicActions
            .map(QuickAction::normalized)
            .filter(distinctSet::add)
        val distinctHiddenActions = hiddenActions
            .map(QuickAction::normalized)
            .filter(distinctSet::add)
        return QuickActionArrangement(
            stickyAction = normalizedStickyAction,
            dynamicActions = distinctDynamicActions,
            hiddenActions = distinctHiddenActions,
        )
    }

    fun withAvailableActions(): QuickActionArrangement {
        val present = buildSet {
            stickyAction?.let(::add)
            addAll(dynamicActions)
            addAll(hiddenActions)
        }
        val available = buildList {
            Default.stickyAction?.let(::add)
            addAll(Default.dynamicActions)
            addAll(Default.hiddenActions)
        }
        return copy(hiddenActions = hiddenActions + available.filterNot(present::contains))
    }

    companion object {
        val Default = QuickActionArrangement(
            stickyAction = QuickAction.InsertKey(TextKeyData.VOICE_INPUT),
            dynamicActions = listOf(
                QuickAction.InsertKey(TextKeyData.UNDO),
                QuickAction.InsertKey(TextKeyData.REDO),
                QuickAction.InsertKey(TextKeyData.SETTINGS),
                QuickAction.InsertKey(TextKeyData.TOGGLE_FLOATING_WINDOW),
                QuickAction.InsertKey(TextKeyData.TOGGLE_RESIZE_MODE),
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_CLIPBOARD),
                QuickAction.InsertKey(TextKeyData.IME_UI_MODE_MEDIA),
                QuickAction.InsertKey(TextKeyData.TOGGLE_COMPACT_LAYOUT),
                QuickAction.InsertKey(TextKeyData.TOGGLE_AUTOCORRECT),
                QuickAction.InsertKey(TextKeyData.TOGGLE_INCOGNITO_MODE),
                QuickAction.InsertKey(TextKeyData.ARROW_UP),
                QuickAction.InsertKey(TextKeyData.ARROW_DOWN),
                QuickAction.InsertKey(TextKeyData.ARROW_LEFT),
                QuickAction.InsertKey(TextKeyData.ARROW_RIGHT),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_CLEAR_PRIMARY_CLIP),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_COPY),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_CUT),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_PASTE),
                QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                QuickAction.InsertKey(TextKeyData.LANGUAGE_SWITCH),
                QuickAction.InsertKey(TextKeyData.FORWARD_DELETE),
                QuickAction.InsertKey(TextKeyData.IME_HIDE_UI),
            ),
            hiddenActions = emptyList(),
        )
    }

    object Serializer : PreferenceSerializer<QuickActionArrangement> {
        override fun serialize(value: QuickActionArrangement): String = QuickActionJsonConfig.encodeToString(value)

        override fun deserialize(value: String): QuickActionArrangement =
            QuickActionJsonConfig.decodeFromString<QuickActionArrangement>(value).distinct()
    }
}

private fun QuickAction.normalized(): QuickAction = if (
    this is QuickAction.InsertKey &&
    data.code == KeyCode.AUTOCORRECT_PLUGIN_UI
) {
    QuickAction.InsertKey(TextKeyData.TOGGLE_AUTOCORRECT)
} else {
    this
}
