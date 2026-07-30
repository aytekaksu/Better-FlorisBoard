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

package dev.patrickgold.florisboard.app

import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class LegacySmartbarMigrationBase(
    val layout: SmartbarLayout,
    val actionArrangement: QuickActionArrangement,
)

internal object LegacySmartbarPreferenceMigration {
    private const val LAYOUT_KEY = "smartbar__layout"
    private const val ARRANGEMENT_KEY = "smartbar__action_arrangement"
    private const val LEGACY_ACTIONS_KEY = "smartbar__actions"
    private const val QUICK_ACTIONS_KEY = "smartbar__quick_actions"
    private const val SECONDARY_ROW_ENABLED_KEY = "smartbar__secondary_row_enabled"
    private const val SECONDARY_ACTIONS_ENABLED_KEY = "smartbar__secondary_actions_enabled"
    private const val SECONDARY_ROW_EXPANDED_KEY = "smartbar__secondary_row_expanded"
    private const val SECONDARY_ACTIONS_EXPANDED_KEY = "smartbar__secondary_actions_expanded"
    private const val PRIMARY_ROW_TYPE_KEY = "smartbar__primary_actions_row_type"
    private const val SECONDARY_ROW_TYPE_KEY = "smartbar__secondary_actions_row_type"

    private const val FIRST_FIXED_ROWS_VERSION_CODE = 63
    private const val LAST_FIXED_ROWS_VERSION_CODE = 69
    private const val LAST_TYPED_ROWS_VERSION_CODE = 88
    private const val LEGACY_MAJOR_VERSION = 0
    private const val LEGACY_MINOR_VERSION = 3
    private const val CANONICAL_MINOR_VERSION = 4
    private const val ACTION_ROWS_PATCH_VERSION = 14
    private const val CANONICAL_PREVIEW_PATCH_VERSION = 0
    private const val FIRST_FIXED_ROWS_BETA = 7
    private const val LAST_FIXED_ROWS_BETA = 13
    private const val LAST_TYPED_ROWS_ALPHA = 2

    fun createEntries(
        wire: LegacyPreferencePayload,
        base: LegacySmartbarMigrationBase?,
        source: LegacyPreferenceSource,
    ): List<String> {
        val structuralEntries = createStructuralEntries(wire, base, source)
        return structuralEntries + preserveCanonicalScalars(wire)
    }

    private fun createStructuralEntries(
        wire: LegacyPreferencePayload,
        base: LegacySmartbarMigrationBase?,
        source: LegacyPreferenceSource,
    ): List<String> {
        if (wire.hasKey(LAYOUT_KEY) || wire.hasKey(ARRANGEMENT_KEY)) return emptyList()
        if (legacyMarkerKeys.none(wire::hasKey)) return emptyList()

        val schema = resolveSchema(wire, source)
        if (schema !in legacySchemas) return emptyList()

        val state = readState(wire, schema)
        val layout = resolveLayout(state, base)
        val arrangement = resolveArrangement(state, layout, base)
        return listOf(
            LegacyPreferencePayload.stringEntry(LAYOUT_KEY, layout.name),
            LegacyPreferencePayload.stringEntry(
                ARRANGEMENT_KEY,
                QuickActionArrangement.Serializer.serialize(arrangement),
            ),
        )
    }

    private fun preserveCanonicalScalars(wire: LegacyPreferencePayload): List<String> =
        scalarAliases.mapNotNull { (canonicalKey, aliases) ->
            val canonicalIndex = wire.entries.indexOfLast {
                it.type == canonicalScalarTypes.getValue(canonicalKey) &&
                    it.key == canonicalKey
            }
            val aliasFollowsCanonical = wire.entries
                .asSequence()
                .drop(canonicalIndex + 1)
                .any { it.key in aliases }
            wire.entries.getOrNull(canonicalIndex)
                ?.rawLine
                ?.takeIf { aliasFollowsCanonical }
        }

    private fun readState(wire: LegacyPreferencePayload, schema: Schema): LegacySmartbarState {
        val storedPrimaryType = if (schema == Schema.TYPED_ROWS) {
            wire.lastValidString(PRIMARY_ROW_TYPE_KEY)?.toRowType()
        } else {
            null
        }
        val storedSecondaryType = if (schema == Schema.TYPED_ROWS) {
            wire.lastValidString(SECONDARY_ROW_TYPE_KEY)?.toRowType()
        } else {
            null
        }
        val enabledKey = if (schema == Schema.TYPED_ROWS) {
            SECONDARY_ACTIONS_ENABLED_KEY
        } else {
            SECONDARY_ROW_ENABLED_KEY
        }
        val expandedKey = if (schema == Schema.TYPED_ROWS) {
            SECONDARY_ACTIONS_EXPANDED_KEY
        } else {
            SECONDARY_ROW_EXPANDED_KEY
        }
        val actions = readLegacyActions(wire, schema)
        val secondaryEnabled = wire.lastValidBoolean(enabledKey)
        val secondaryExpanded = wire.lastValidBoolean(expandedKey)
        return LegacySmartbarState(
            primaryType = storedPrimaryType ?: RowType.QUICK_ACTIONS,
            secondaryType = storedSecondaryType ?: RowType.CLIPBOARD_TOOLS,
            secondaryEnabled = secondaryEnabled,
            secondaryExpanded = secondaryExpanded,
            quickActions = actions,
            hasArrangementSignal = actions != null ||
                storedPrimaryType != null ||
                storedSecondaryType != null,
        )
    }

    private fun resolveLayout(state: LegacySmartbarState, base: LegacySmartbarMigrationBase?): SmartbarLayout {
        if (base != null) {
            return when {
                state.secondaryEnabled == false -> SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
                state.secondaryExpanded == true -> SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
                state.secondaryExpanded == false -> SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
                else -> base.layout
            }
        }
        return if (
            (state.secondaryEnabled ?: true) &&
            (state.secondaryExpanded ?: false)
        ) {
            SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
        } else {
            SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED
        }
    }

    private fun resolveArrangement(
        state: LegacySmartbarState,
        layout: SmartbarLayout,
        base: LegacySmartbarMigrationBase?,
    ): QuickActionArrangement {
        if (base != null && !state.hasArrangementSignal) {
            return base.actionArrangement
        }

        val quickActions = completeQuickActions(state.quickActions)
        val primary = state.primaryType.actions(quickActions)
        val secondary = state.secondaryType.actions(quickActions)
        val dynamic = when (layout) {
            SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED -> secondary + primary
            else -> primary + secondary.takeIf { state.secondaryEnabled != false }.orEmpty()
        }.stableDistinct()
            .filterNot { it.code == KeyCode.VOICE_INPUT }
        val hidden = (quickActions + legacyClipboardActions)
            .stableDistinct()
            .filterNot { action -> dynamic.any { it.code == action.code } }
            .filterNot { it.code == KeyCode.VOICE_INPUT }
        val reconstructed = QuickActionArrangement(
            stickyAction = QuickAction.InsertKey(TextKeyData.VOICE_INPUT),
            dynamicActions = dynamic.asQuickActions(),
            hiddenActions = hidden.asQuickActions(),
        )
        return if (base == null) {
            reconstructed.distinct().withAvailableActions()
        } else {
            overlayBaseArrangement(reconstructed, base.actionArrangement)
        }
    }

    private fun overlayBaseArrangement(
        reconstructed: QuickActionArrangement,
        base: QuickActionArrangement,
    ): QuickActionArrangement {
        val currentOnlyDynamic = base.dynamicActions.filterNot(::isLegacyUniverseAction)
        val currentOnlyHidden = base.hiddenActions.filterNot(::isLegacyUniverseAction)
        return QuickActionArrangement(
            stickyAction = base.stickyAction,
            dynamicActions = reconstructed.dynamicActions + currentOnlyDynamic,
            hiddenActions = reconstructed.hiddenActions + currentOnlyHidden,
        ).distinct().withAvailableActions()
    }

    private fun readLegacyActions(wire: LegacyPreferencePayload, schema: Schema): List<TextKeyData>? {
        val keys = if (schema == Schema.TYPED_ROWS) {
            listOf(QUICK_ACTIONS_KEY, LEGACY_ACTIONS_KEY)
        } else {
            listOf(LEGACY_ACTIONS_KEY)
        }
        return keys.firstNotNullOfOrNull { key ->
            wire.entries.asReversed()
                .asSequence()
                .filter { it.type == "s" && it.key == key }
                .mapNotNull(LegacyPreferencePayload.Entry::stringValue)
                .mapNotNull(::parseLegacyActions)
                .firstOrNull()
        }
    }

    private fun parseLegacyActions(raw: String): List<TextKeyData>? {
        val root = try {
            Json.parseToJsonElement(raw)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (root !is JsonArray) return null
        return root.mapNotNull(::parseLegacyAction).stableDistinct()
    }

    private fun parseLegacyAction(element: JsonElement): TextKeyData? {
        val action = element as? JsonObject
        val actionType = (action?.get("$") as? JsonPrimitive)?.contentOrNull
        val data = action?.get("data") as? JsonObject
        val dataType = (data?.get("$") as? JsonPrimitive)?.contentOrNull
        val code = (data?.get("code") as? JsonPrimitive)?.intOrNull
        val isRecognizedShape = actionType == "key" &&
            (dataType == null || dataType == "text_key")
        return code?.takeIf { isRecognizedShape }?.let(legacyKeyDataByCode::get)
    }

    private fun completeQuickActions(stored: List<TextKeyData>?): List<TextKeyData> {
        val recognized = stored.orEmpty().filter { it.code in legacyQuickActionCodes }
        return (recognized + legacyQuickActions).stableDistinct()
    }

    private fun RowType.actions(quickActions: List<TextKeyData>): List<TextKeyData> = when (this) {
        RowType.QUICK_ACTIONS -> quickActions
        RowType.CLIPBOARD_TOOLS -> legacyClipboardActions
    }

    private fun String.toRowType(): RowType? = when (uppercase()) {
        "QUICK_ACTIONS" -> RowType.QUICK_ACTIONS
        "CLIPBOARD_CURSOR_TOOLS" -> RowType.CLIPBOARD_TOOLS
        else -> null
    }

    private fun List<TextKeyData>.stableDistinct(): List<TextKeyData> = distinctBy(TextKeyData::code)

    private fun List<TextKeyData>.asQuickActions(): List<QuickAction> = map(QuickAction::InsertKey)

    private fun isLegacyUniverseAction(action: QuickAction): Boolean {
        if (action !is QuickAction.InsertKey) return false
        val code = if (action.data.code == KeyCode.COMPACT_LAYOUT_TO_RIGHT) {
            KeyCode.TOGGLE_COMPACT_LAYOUT
        } else {
            action.data.code
        }
        return code in legacyUniverseCodes
    }

    private fun resolveSchema(wire: LegacyPreferencePayload, source: LegacyPreferenceSource): Schema {
        val declaredSchema = source.versionName?.toSchema()
            ?: source.versionCode?.toSchema()
            ?: source.versionLastUse?.toSchema()
        if (declaredSchema != null) return declaredSchema

        val installedSchema = source.versionOnInstall?.toSchema()
        return when {
            installedSchema == Schema.CURRENT -> Schema.CURRENT
            typedRowMarkerKeys.any(wire::hasKey) -> Schema.TYPED_ROWS
            fixedRowMarkerKeys.any(wire::hasKey) -> Schema.FIXED_ROWS
            else -> installedSchema ?: Schema.UNKNOWN
        }
    }

    private fun Int.toSchema(): Schema = when {
        this < FIRST_FIXED_ROWS_VERSION_CODE -> Schema.UNSUPPORTED
        this <= LAST_FIXED_ROWS_VERSION_CODE -> Schema.FIXED_ROWS
        this <= LAST_TYPED_ROWS_VERSION_CODE -> Schema.TYPED_ROWS
        else -> Schema.CURRENT
    }

    private fun String.toSchema(): Schema? = toSemanticVersion()
        ?.takeUnless(SemanticVersion::isPlaceholder)
        ?.toSchema()

    private fun String.toSemanticVersion(): SemanticVersion? {
        val match = semanticVersion.matchEntire(this) ?: return null
        val numbers = match.groupValues
            .subList(1, 4)
            .map(String::toIntOrNull)
        if (numbers.any { it == null }) return null
        return SemanticVersion(
            major = requireNotNull(numbers[0]),
            minor = requireNotNull(numbers[1]),
            patch = requireNotNull(numbers[2]),
            qualifier = match.groupValues[4].lowercase(),
            qualifierValue = match.groupValues[5].toIntOrNull(),
        )
    }

    private fun SemanticVersion.toSchema(): Schema = when {
        major != LEGACY_MAJOR_VERSION -> Schema.CURRENT
        minor == LEGACY_MINOR_VERSION -> toSchemaForLegacyMinor()
        minor == CANONICAL_MINOR_VERSION -> toSchemaForCanonicalMinor()
        minor < LEGACY_MINOR_VERSION -> Schema.UNSUPPORTED
        else -> Schema.CURRENT
    }

    private fun SemanticVersion.toSchemaForLegacyMinor(): Schema = when {
        patch < ACTION_ROWS_PATCH_VERSION -> Schema.UNSUPPORTED
        patch > ACTION_ROWS_PATCH_VERSION -> Schema.TYPED_ROWS
        qualifier == "beta" -> qualifierValue?.toSchemaForActionRowsBeta() ?: Schema.UNSUPPORTED
        else -> Schema.TYPED_ROWS
    }

    private fun SemanticVersion.toSchemaForCanonicalMinor(): Schema = when {
        patch == CANONICAL_PREVIEW_PATCH_VERSION &&
            qualifier == "alpha" &&
            qualifierValue != null &&
            qualifierValue <= LAST_TYPED_ROWS_ALPHA -> Schema.TYPED_ROWS

        else -> Schema.CURRENT
    }

    private fun Int.toSchemaForActionRowsBeta(): Schema = when {
        this < FIRST_FIXED_ROWS_BETA -> Schema.UNSUPPORTED
        this <= LAST_FIXED_ROWS_BETA -> Schema.FIXED_ROWS
        else -> Schema.TYPED_ROWS
    }

    private data class LegacySmartbarState(
        val primaryType: RowType,
        val secondaryType: RowType,
        val secondaryEnabled: Boolean?,
        val secondaryExpanded: Boolean?,
        val quickActions: List<TextKeyData>?,
        val hasArrangementSignal: Boolean,
    )

    private enum class Schema {
        FIXED_ROWS,
        TYPED_ROWS,
        CURRENT,
        UNSUPPORTED,
        UNKNOWN,
    }

    private enum class RowType {
        QUICK_ACTIONS,
        CLIPBOARD_TOOLS,
    }

    private data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val qualifier: String,
        val qualifierValue: Int?,
    ) {
        val isPlaceholder = major == 0 && minor == 0 && patch == 0
    }

    private val legacyQuickActions = listOf(
        TextKeyData.UNDO,
        TextKeyData.REDO,
        TextKeyData.SETTINGS,
        TextKeyData.IME_UI_MODE_MEDIA,
        TextKeyData.VOICE_INPUT,
        TextKeyData.TOGGLE_COMPACT_LAYOUT,
        TextKeyData.IME_UI_MODE_CLIPBOARD,
    )
    private val legacyClipboardActions = listOf(
        TextKeyData.CLIPBOARD_SELECT_ALL,
        TextKeyData.CLIPBOARD_COPY,
        TextKeyData.CLIPBOARD_CUT,
        TextKeyData.ARROW_LEFT,
        TextKeyData.ARROW_RIGHT,
        TextKeyData.CLIPBOARD_PASTE,
        TextKeyData.CLIPBOARD_CLEAR_PRIMARY_CLIP,
        TextKeyData.IME_UI_MODE_CLIPBOARD,
    )
    private val legacyKeyDataByCode = (legacyQuickActions + legacyClipboardActions)
        .associateBy(TextKeyData::code) +
        (KeyCode.COMPACT_LAYOUT_TO_RIGHT to TextKeyData.TOGGLE_COMPACT_LAYOUT)
    private val legacyQuickActionCodes = legacyQuickActions.mapTo(mutableSetOf(), TextKeyData::code)
    private val legacyUniverseCodes = (legacyQuickActions + legacyClipboardActions)
        .mapTo(mutableSetOf(), TextKeyData::code)
    private val fixedRowMarkerKeys = setOf(
        "smartbar__primary_row_flip_toggles",
        SECONDARY_ROW_ENABLED_KEY,
        SECONDARY_ROW_EXPANDED_KEY,
        "smartbar__secondary_row_placement",
        "smartbar__action_row_expanded",
        "smartbar__action_row_expand_with_animation",
        "smartbar__action_row_auto_expand_collapse",
        LEGACY_ACTIONS_KEY,
    )
    private val typedRowMarkerKeys = setOf(
        "smartbar__primary_actions_expanded",
        PRIMARY_ROW_TYPE_KEY,
        "smartbar__primary_actions_auto_expand_collapse",
        "smartbar__primary_actions_expand_with_animation",
        SECONDARY_ACTIONS_ENABLED_KEY,
        SECONDARY_ACTIONS_EXPANDED_KEY,
        "smartbar__secondary_actions_placement",
        SECONDARY_ROW_TYPE_KEY,
        QUICK_ACTIONS_KEY,
    )
    private val legacyMarkerKeys = fixedRowMarkerKeys + typedRowMarkerKeys
    private val legacySchemas = setOf(Schema.FIXED_ROWS, Schema.TYPED_ROWS)
    private val scalarAliases = mapOf(
        "smartbar__flip_toggles" to setOf(
            "smartbar__primary_row_flip_toggles",
        ),
        "smartbar__shared_actions_expanded" to setOf(
            "smartbar__action_row_expanded",
            "smartbar__primary_actions_expanded",
        ),
        "smartbar__extended_actions_expanded" to setOf(
            "smartbar__secondary_row_expanded",
            "smartbar__secondary_actions_expanded",
        ),
        "smartbar__extended_actions_placement" to setOf(
            "smartbar__secondary_row_placement",
            "smartbar__secondary_actions_placement",
        ),
    )
    private val canonicalScalarTypes = mapOf(
        "smartbar__flip_toggles" to "b",
        "smartbar__shared_actions_expanded" to "b",
        "smartbar__extended_actions_expanded" to "b",
        "smartbar__extended_actions_placement" to "s",
    )
    private val semanticVersion = Regex(
        pattern = """v?(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(\d+))?""",
        option = RegexOption.IGNORE_CASE,
    )
}

private fun LegacyPreferencePayload.lastValidBoolean(key: String): Boolean? = entries.asReversed()
    .asSequence()
    .filter { it.type == "b" && it.key == key }
    .mapNotNull(LegacyPreferencePayload.Entry::booleanValue)
    .firstOrNull()

private fun LegacyPreferencePayload.lastValidString(key: String): String? = entries.asReversed()
    .asSequence()
    .filter { it.type == "s" && it.key == key }
    .mapNotNull(LegacyPreferencePayload.Entry::stringValue)
    .firstOrNull()
