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

import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.patrickgold.florisboard.ime.window.ImeFormFactor
import dev.patrickgold.florisboard.ime.window.ImeInsets
import dev.patrickgold.florisboard.ime.window.ImeWindowConfig
import dev.patrickgold.florisboard.ime.window.ImeWindowConfigByType
import dev.patrickgold.florisboard.ime.window.ImeWindowConstraints
import dev.patrickgold.florisboard.ime.window.ImeWindowMode
import dev.patrickgold.florisboard.ime.window.ImeWindowProps

/**
 * Reconstructs preferences which used several JetPref entries before they became one compound value.
 *
 * The original payload is returned byte-for-byte when no conversion applies. A current target key always
 * wins, even when its value is malformed, so stale legacy entries can never replace newer state.
 */
internal object LegacyPreferencePayloadPreprocessor {
    private const val WINDOW_CONFIG_KEY = "keyboard__window_config"
    private const val HEIGHT_PORTRAIT_KEY = "keyboard__height_factor_portrait"
    private const val HEIGHT_LANDSCAPE_KEY = "keyboard__height_factor_landscape"
    private const val OFFSET_PORTRAIT_KEY = "keyboard__bottom_offset_portrait"
    private const val OFFSET_LANDSCAPE_KEY = "keyboard__bottom_offset_landscape"
    private const val ONE_HAND_MODE_KEY = "keyboard__one_handed_mode"
    private const val ONE_HAND_ENABLED_KEY = "keyboard__one_handed_mode_enabled"
    private const val ONE_HAND_SCALE_KEY = "keyboard__one_handed_mode_scale_factor"
    private const val VERSION_LAST_USE_KEY = "internal__version_last_use"
    private const val VERSION_ON_INSTALL_KEY = "internal__version_on_install"

    private const val LAST_COMBINED_ONE_HAND_VERSION_CODE = 104
    private const val LAST_SPLIT_ONE_HAND_VERSION_CODE = 117
    private const val SPLIT_ONE_HAND_MINOR_VERSION = 5
    private const val CURRENT_WINDOW_CONFIG_MINOR_VERSION = 6
    private const val DEFAULT_HEIGHT_PERCENT = 100
    private const val DEFAULT_BOTTOM_OFFSET = 0
    private const val DEFAULT_ONE_HAND_SCALE_PERCENT = 87

    fun process(
        payload: String,
        baseWindowConfig: ImeWindowConfigByType? = null,
        sourceVersionCode: Int? = null,
        sourceVersionName: String? = null,
    ): String {
        val entries = payload.lineSequence().mapNotNull(::parseEntry).toList()
        if (entries.any { it.key == WINDOW_CONFIG_KEY }) {
            return payload
        }

        val versionOnInstall = entries.lastString(VERSION_ON_INSTALL_KEY)
        val sourceSchema = resolveSourceSchema(
            sourceVersionCode = sourceVersionCode,
            sourceVersionName = sourceVersionName,
            versionLastUse = entries.lastString(VERSION_LAST_USE_KEY),
            versionOnInstall = versionOnInstall,
            hasSplitSchemaMarker = entries.lastBoolean(ONE_HAND_ENABLED_KEY) != null,
        )
        if (sourceSchema == SourceSchema.CURRENT) {
            return payload
        }

        val legacy = LegacyWindowValues(
            heightPortrait = entries.lastInt(HEIGHT_PORTRAIT_KEY)?.coerceIn(50, 150),
            heightLandscape = entries.lastInt(HEIGHT_LANDSCAPE_KEY)?.coerceIn(50, 150),
            offsetPortrait = entries.lastInt(OFFSET_PORTRAIT_KEY)?.coerceIn(0, 60),
            offsetLandscape = entries.lastInt(OFFSET_LANDSCAPE_KEY)?.coerceIn(0, 60),
            oneHandMode = entries.lastString(ONE_HAND_MODE_KEY)?.toLegacyOneHandMode(),
            oneHandEnabled = entries.lastBoolean(ONE_HAND_ENABLED_KEY),
            oneHandScale = entries.lastInt(ONE_HAND_SCALE_KEY)?.coerceIn(70, 90),
        )
        if (!legacy.hasAnyValue) {
            return payload
        }

        val migrated = migrateWindowConfig(
            base = baseWindowConfig,
            sourceSchema = sourceSchema,
            recoverCombinedOneHandIntent = sourceSchema == SourceSchema.SPLIT &&
                versionOnInstall?.toSourceSchema() == SourceSchema.COMBINED,
            legacy = legacy,
        )
        val serialized = ImeWindowConfig.ByTypeSerializer.serialize(migrated)
        return payload.appendEntry("s;$WINDOW_CONFIG_KEY;${encodeString(serialized)}")
    }

    private fun migrateWindowConfig(
        base: ImeWindowConfigByType?,
        sourceSchema: SourceSchema,
        recoverCombinedOneHandIntent: Boolean,
        legacy: LegacyWindowValues,
    ): ImeWindowConfigByType {
        val configs = base.orEmpty().toMutableMap()
        val isMerge = base != null
        val oneHand = resolveOneHand(
            sourceSchema = sourceSchema,
            legacy = legacy,
            isMerge = isMerge,
            recoverCombinedIntent = recoverCombinedOneHandIntent,
        )

        if (legacy.hasPortraitValue) {
            portraitTypes.forEach { type ->
                configs[type] = migratePortraitConfig(
                    type = type,
                    current = configs[type],
                    isMerge = isMerge,
                    legacy = legacy,
                    oneHand = oneHand,
                )
            }
        }
        if (legacy.hasLandscapeValue) {
            landscapeTypes.forEach { type ->
                configs[type] = migrateLandscapeConfig(
                    type = type,
                    current = configs[type],
                    isMerge = isMerge,
                    heightPercent = legacy.heightLandscape,
                    bottomOffset = legacy.offsetLandscape,
                )
            }
        }
        return configs
    }

    private fun migratePortraitConfig(
        type: ImeFormFactor.Type,
        current: ImeWindowConfig?,
        isMerge: Boolean,
        legacy: LegacyWindowValues,
        oneHand: OneHandState,
    ): ImeWindowConfig {
        val config = current ?: ImeWindowConfig.Default
        val rootInsets = baselineRootInsets(type)
        val normalConstraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.NORMAL)
        val compactConstraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.COMPACT)
        val currentNormal = config.fixedProps[ImeWindowMode.Fixed.NORMAL] ?: normalConstraints.defaultProps
        val currentCompact = config.fixedProps[ImeWindowMode.Fixed.COMPACT]
        val normal = migrateNormalProps(
            constraints = normalConstraints,
            current = currentNormal,
            isMerge = isMerge,
            heightPercent = legacy.heightPortrait,
            bottomOffset = legacy.offsetPortrait,
        )
        val compact = migrateCompactProps(
            rootInsets = rootInsets,
            normalConstraints = normalConstraints,
            compactConstraints = compactConstraints,
            currentNormal = currentNormal,
            currentCompact = currentCompact,
            migratedNormal = normal,
            isMerge = isMerge,
            legacy = legacy,
            side = oneHand.side,
        )

        val fixedProps = config.fixedProps.toMutableMap().apply {
            this[ImeWindowMode.Fixed.NORMAL] = normal
            this[ImeWindowMode.Fixed.COMPACT] = compact
        }
        return config.copy(
            fixedMode = resolveFixedMode(config.fixedMode, oneHand.active, isMerge),
            fixedProps = fixedProps,
        )
    }

    private fun migrateCompactProps(
        rootInsets: ImeInsets.Root,
        normalConstraints: ImeWindowConstraints.Fixed,
        compactConstraints: ImeWindowConstraints.Fixed,
        currentNormal: ImeWindowProps.Fixed,
        currentCompact: ImeWindowProps.Fixed?,
        migratedNormal: ImeWindowProps.Fixed,
        isMerge: Boolean,
        legacy: LegacyWindowValues,
        side: LegacyOneHandMode?,
    ): ImeWindowProps.Fixed {
        val seed = legacyCompactProps(
            rootInsets = rootInsets,
            normalHeight = migratedNormal.keyboardHeight,
            bottomOffset = legacy.offsetPortrait ?: DEFAULT_BOTTOM_OFFSET,
            scalePercent = legacy.oneHandScale ?: DEFAULT_ONE_HAND_SCALE_PERCENT,
            side = side ?: LegacyOneHandMode.END,
        )
        if (!isMerge || (currentCompact == null && legacy.controlsCompactGeometry)) {
            return seed
        }
        return mergeCompactProps(
            rootInsets = rootInsets,
            constraints = compactConstraints,
            currentNormal = currentNormal.takeIf { currentCompact != null } ?: normalConstraints.defaultProps,
            currentCompact = currentCompact ?: compactConstraints.defaultProps,
            migratedNormal = migratedNormal,
            legacy = legacy,
            side = side,
        )
    }

    private fun migrateNormalProps(
        constraints: ImeWindowConstraints.Fixed,
        current: ImeWindowProps.Fixed,
        isMerge: Boolean,
        heightPercent: Int?,
        bottomOffset: Int?,
    ): ImeWindowProps.Fixed {
        val props = if (isMerge) {
            current.copy(
                keyboardHeight = heightPercent
                    ?.let { constraints.defKeyboardHeight * (it / 100f) }
                    ?: current.keyboardHeight,
                paddingBottom = bottomOffset?.dp ?: current.paddingBottom,
            )
        } else {
            ImeWindowProps.Fixed(
                keyboardHeight = constraints.defKeyboardHeight *
                    ((heightPercent ?: DEFAULT_HEIGHT_PERCENT) / 100f),
                paddingLeft = 0.dp,
                paddingRight = 0.dp,
                paddingBottom = (bottomOffset ?: DEFAULT_BOTTOM_OFFSET).dp,
            )
        }
        return props.constrained(constraints)
    }

    private fun resolveFixedMode(
        current: ImeWindowMode.Fixed,
        active: Boolean?,
        isMerge: Boolean,
    ): ImeWindowMode.Fixed = when {
        active == true -> ImeWindowMode.Fixed.COMPACT

        active == false && (!isMerge || current == ImeWindowMode.Fixed.COMPACT) ->
            ImeWindowMode.Fixed.NORMAL

        else -> current
    }

    private fun mergeCompactProps(
        rootInsets: ImeInsets.Root,
        constraints: ImeWindowConstraints.Fixed,
        currentNormal: ImeWindowProps.Fixed,
        currentCompact: ImeWindowProps.Fixed,
        migratedNormal: ImeWindowProps.Fixed,
        legacy: LegacyWindowValues,
        side: LegacyOneHandMode?,
    ): ImeWindowProps.Fixed {
        val scale = legacy.oneHandScale?.div(100f)
        val height = when {
            scale != null -> migratedNormal.keyboardHeight * scale

            legacy.heightPortrait != null && currentNormal.keyboardHeight.value > 0f -> {
                val currentRatio = currentCompact.keyboardHeight.value / currentNormal.keyboardHeight.value
                migratedNormal.keyboardHeight * currentRatio
            }

            else -> currentCompact.keyboardHeight
        }
        val totalPadding = scale
            ?.let { rootInsets.boundsDp.width * (1f - it) }
            ?: (currentCompact.paddingLeft + currentCompact.paddingRight)
        val resolvedSide = side ?: currentCompact.side()
        return currentCompact.copy(
            keyboardHeight = height,
            paddingLeft = if (resolvedSide == LegacyOneHandMode.END) totalPadding else 0.dp,
            paddingRight = if (resolvedSide == LegacyOneHandMode.START) totalPadding else 0.dp,
            paddingBottom = legacy.offsetPortrait?.dp ?: currentCompact.paddingBottom,
        ).constrained(constraints)
    }

    private fun migrateLandscapeConfig(
        type: ImeFormFactor.Type,
        current: ImeWindowConfig?,
        isMerge: Boolean,
        heightPercent: Int?,
        bottomOffset: Int?,
    ): ImeWindowConfig {
        val config = current ?: ImeWindowConfig.Default
        val rootInsets = baselineRootInsets(type)
        val constraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.NORMAL)
        val currentNormal = config.fixedProps[ImeWindowMode.Fixed.NORMAL] ?: constraints.defaultProps
        val normal = migrateNormalProps(
            constraints = constraints,
            current = currentNormal,
            isMerge = isMerge,
            heightPercent = heightPercent,
            bottomOffset = bottomOffset,
        )
        return config.copy(
            fixedProps = config.fixedProps + (ImeWindowMode.Fixed.NORMAL to normal),
        )
    }

    private fun legacyCompactProps(
        rootInsets: ImeInsets.Root,
        normalHeight: androidx.compose.ui.unit.Dp,
        bottomOffset: Int,
        scalePercent: Int,
        side: LegacyOneHandMode,
    ): ImeWindowProps.Fixed {
        val constraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.COMPACT)
        val scale = scalePercent / 100f
        val totalPadding = rootInsets.boundsDp.width * (1f - scale)
        return ImeWindowProps.Fixed(
            keyboardHeight = normalHeight * scale,
            paddingLeft = if (side == LegacyOneHandMode.END) totalPadding else 0.dp,
            paddingRight = if (side == LegacyOneHandMode.START) totalPadding else 0.dp,
            paddingBottom = bottomOffset.dp,
        ).constrained(constraints)
    }

    private fun resolveOneHand(
        sourceSchema: SourceSchema,
        legacy: LegacyWindowValues,
        isMerge: Boolean,
        recoverCombinedIntent: Boolean,
    ): OneHandState = OneHandState(
        active = resolveOneHandActive(
            sourceSchema = sourceSchema,
            mode = legacy.oneHandMode,
            enabled = legacy.oneHandEnabled,
            isMerge = isMerge,
            recoverCombinedIntent = recoverCombinedIntent,
        ),
        side = resolveOneHandSide(legacy.oneHandMode, isMerge),
    )

    private fun resolveOneHandActive(
        sourceSchema: SourceSchema,
        mode: LegacyOneHandMode?,
        enabled: Boolean?,
        isMerge: Boolean,
        recoverCombinedIntent: Boolean,
    ): Boolean? = when {
        mode == LegacyOneHandMode.OFF -> false

        sourceSchema == SourceSchema.COMBINED -> when (mode) {
            LegacyOneHandMode.START, LegacyOneHandMode.END -> true
            LegacyOneHandMode.OFF -> false
            null -> if (isMerge) null else false
        }

        sourceSchema == SourceSchema.SPLIT -> when {
            enabled != null -> enabled
            recoverCombinedIntent && mode != null -> true
            isMerge -> null
            else -> false
        }

        enabled != null -> enabled

        else -> null
    }

    private fun resolveOneHandSide(mode: LegacyOneHandMode?, isMerge: Boolean): LegacyOneHandMode? = when (mode) {
        LegacyOneHandMode.START, LegacyOneHandMode.END -> mode
        LegacyOneHandMode.OFF, null -> if (isMerge) null else LegacyOneHandMode.END
    }

    private fun baselineRootInsets(type: ImeFormFactor.Type): ImeInsets.Root {
        val size = ImeWindowConstraints.BaselineScreens.getValue(type)
        val bounds = DpRect(0.dp, 0.dp, size.width, size.height)
        val measuredFormFactor = ImeFormFactor.of(bounds)
        return ImeInsets.Root(
            boundsDp = bounds,
            boundsPx = IntRect.Zero,
            formFactor = measuredFormFactor.copy(typeGuess = type),
        )
    }

    private fun resolveSourceSchema(
        sourceVersionCode: Int?,
        sourceVersionName: String?,
        versionLastUse: String?,
        versionOnInstall: String?,
        hasSplitSchemaMarker: Boolean,
    ): SourceSchema {
        val versionSchema = sourceVersionName?.toSourceSchema()
            ?: sourceVersionCode?.toSourceSchema()
            ?: versionLastUse?.toSourceSchema()
        val installedSchema = versionOnInstall
            ?.toSourceSchema()
            ?.takeUnless { it == SourceSchema.COMBINED }
        return versionSchema
            ?: installedSchema
            ?: if (hasSplitSchemaMarker) SourceSchema.SPLIT else SourceSchema.UNKNOWN
    }

    private fun Int.toSourceSchema(): SourceSchema = when {
        this <= LAST_COMBINED_ONE_HAND_VERSION_CODE -> SourceSchema.COMBINED
        this <= LAST_SPLIT_ONE_HAND_VERSION_CODE -> SourceSchema.SPLIT
        else -> SourceSchema.CURRENT
    }

    private fun String.toSourceSchema(): SourceSchema? {
        val match = versionPrefix.matchAt(this, 0) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        return when {
            major > 0 || minor >= CURRENT_WINDOW_CONFIG_MINOR_VERSION -> SourceSchema.CURRENT
            minor == SPLIT_ONE_HAND_MINOR_VERSION -> SourceSchema.SPLIT
            else -> SourceSchema.COMBINED
        }
    }

    private fun String.toLegacyOneHandMode(): LegacyOneHandMode? = when (uppercase()) {
        "OFF" -> LegacyOneHandMode.OFF
        "START" -> LegacyOneHandMode.START
        "END" -> LegacyOneHandMode.END
        else -> null
    }

    private fun List<WireEntry>.lastInt(key: String): Int? = lastOrNull {
        it.type == "i" && it.key == key
    }?.rawValue?.toIntOrNull()

    private fun List<WireEntry>.lastBoolean(key: String): Boolean? = when (
        lastOrNull {
            it.type == "b" && it.key == key
        }?.rawValue
    ) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun List<WireEntry>.lastString(key: String): String? = lastOrNull {
        it.type == "s" && it.key == key
    }?.rawValue?.let(::decodeString)

    private fun parseEntry(line: String): WireEntry? {
        if (line.isBlank()) return null
        val typeEnd = line.indexOf(';')
        if (typeEnd < 0) return null
        val keyEnd = line.indexOf(';', startIndex = typeEnd + 1)
        if (keyEnd < 0) return null
        return WireEntry(
            type = line.substring(0, typeEnd),
            key = line.substring(typeEnd + 1, keyEnd),
            rawValue = line.substring(keyEnd + 1),
        )
    }

    private fun decodeString(rawValue: String): String? {
        val value = rawValue.trim()
        if (value.length < 2 || value.first() != '"' || value.last() != '"') return null
        return value.substring(1, value.lastIndex)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\\\", "\\")
    }

    private fun encodeString(value: String): String = buildString(value.length + 2) {
        append('"')
        append(
            value
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\"", "\\\""),
        )
        append('"')
    }

    private fun String.appendEntry(entry: String): String = buildString(length + entry.length + 2) {
        append(this@appendEntry)
        if (isNotEmpty() && last() != '\n' && last() != '\r') {
            append('\n')
        }
        append(entry)
        append('\n')
    }

    private fun ImeWindowProps.Fixed.side(): LegacyOneHandMode =
        if (paddingLeft >= paddingRight) LegacyOneHandMode.END else LegacyOneHandMode.START

    private class WireEntry(val type: String, val key: String, val rawValue: String)

    private data class LegacyWindowValues(
        val heightPortrait: Int?,
        val heightLandscape: Int?,
        val offsetPortrait: Int?,
        val offsetLandscape: Int?,
        val oneHandMode: LegacyOneHandMode?,
        val oneHandEnabled: Boolean?,
        val oneHandScale: Int?,
    ) {
        val hasPortraitValue = heightPortrait != null ||
            offsetPortrait != null ||
            oneHandMode != null ||
            oneHandEnabled != null ||
            oneHandScale != null
        val hasLandscapeValue = heightLandscape != null || offsetLandscape != null
        val hasAnyValue = hasPortraitValue || hasLandscapeValue
        val controlsCompactGeometry = oneHandScale != null ||
            oneHandEnabled == true ||
            oneHandMode == LegacyOneHandMode.START ||
            oneHandMode == LegacyOneHandMode.END
    }

    private data class OneHandState(val active: Boolean?, val side: LegacyOneHandMode?)

    private enum class LegacyOneHandMode {
        OFF,
        START,
        END,
    }

    private enum class SourceSchema {
        COMBINED,
        SPLIT,
        CURRENT,
        UNKNOWN,
    }

    private val portraitTypes = listOf(
        ImeFormFactor.Type.PHONE_PORTRAIT,
        ImeFormFactor.Type.TABLET_PORTRAIT,
    )
    private val landscapeTypes = listOf(
        ImeFormFactor.Type.PHONE_LANDSCAPE,
        ImeFormFactor.Type.TABLET_LANDSCAPE,
        ImeFormFactor.Type.LARGE_TABLET,
    )
    private val versionPrefix = Regex("""(\d+)\.(\d+)""")
}
