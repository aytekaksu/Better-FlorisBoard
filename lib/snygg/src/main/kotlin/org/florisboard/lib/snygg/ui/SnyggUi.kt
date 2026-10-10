/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import com.materialkolor.dynamicColorScheme
import org.florisboard.lib.color.MaterialYouFlags
import org.florisboard.lib.color.systemAccentOrDefault
import org.florisboard.lib.snygg.CompiledFontFamilyData
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggRule
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.SnyggSinglePropertySet
import org.florisboard.lib.snygg.SnyggSinglePropertySetEditor
import org.florisboard.lib.snygg.SnyggStylesheet
import org.florisboard.lib.snygg.SnyggTheme
import org.florisboard.lib.snygg.value.SnyggAssetResolver
import org.florisboard.lib.snygg.value.SnyggDefaultAssetResolver
import org.florisboard.lib.snygg.value.SnyggDpSizeValue
import org.florisboard.lib.snygg.value.SnyggNoValue
import org.florisboard.lib.snygg.value.SnyggPaddingValue
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.florisboard.lib.snygg.value.SnyggUriValue
import org.florisboard.lib.snygg.value.SnyggValue

private fun <T> requiredSnyggLocal(): ProvidableCompositionLocal<T> =
    compositionLocalOf { error("ProvideSnyggTheme not called.") }

internal val LocalSnyggTheme = requiredSnyggLocal<SnyggTheme>()
internal val LocalSnyggDynamicLightColorScheme = requiredSnyggLocal<ColorScheme>()
internal val LocalSnyggDynamicDarkColorScheme = requiredSnyggLocal<ColorScheme>()
internal val LocalSnyggFontSizeMultiplier = requiredSnyggLocal<Float>()
internal val LocalSnyggAssetResolver = requiredSnyggLocal<SnyggAssetResolver>()
internal val LocalSnyggPreloadedCustomFontFamilies = requiredSnyggLocal<CompiledFontFamilyData>()
internal val LocalSnyggParentStyle = requiredSnyggLocal<SnyggSinglePropertySet>()

internal val LocalSnyggParentSelector: ProvidableCompositionLocal<SnyggSelector> =
    compositionLocalOf {
        SnyggSelector.NONE
    }

/**
 * Caches the compiled theme until its stylesheet or asset resolver changes.
 * Compilation is synchronous; file-font themes should be prepared before composition.
 */
@Composable
fun rememberSnyggTheme(
    stylesheet: SnyggStylesheet,
    assetResolver: SnyggAssetResolver = SnyggDefaultAssetResolver,
) = remember(stylesheet, assetResolver) {
    SnyggTheme.compileFrom(stylesheet, assetResolver)
}

/**
 * Provides the compiled theme to Snygg UI children. Use [rememberSnyggTheme] to compile a stylesheet.
 * [Color.Unspecified] uses the platform/default accent.
 */
@Composable
fun ProvideSnyggTheme(
    snyggTheme: SnyggTheme,
    dynamicAccentColor: Color = Color.Unspecified,
    fontSizeMultiplier: Float = 1.0f,
    assetResolver: SnyggAssetResolver = SnyggDefaultAssetResolver,
    rootAttributes: SnyggQueryAttributes = emptyMap(),
    materialYouFlags: MaterialYouFlags = MaterialYouFlags(),
    content: @Composable () -> Unit,
) {
    val (colorPalette, contrastLevel, specVersion) = materialYouFlags
    val lightScheme = dynamicColorScheme(
        primary = systemAccentOrDefault(dynamicAccentColor),
        isDark = false,
        style = colorPalette,
        contrastLevel = contrastLevel.value,
        specVersion = specVersion
    )
    val darkScheme = dynamicColorScheme(
        primary = systemAccentOrDefault(dynamicAccentColor),
        isDark = true,
        style = colorPalette,
        contrastLevel = contrastLevel.value,
        specVersion = specVersion
    )

    val initFontSize = MaterialTheme.typography.bodyMedium.fontSize
    val initParentStyle = remember(initFontSize) {
        SnyggSinglePropertySetEditor().run {
            fontSize = fontSize(initFontSize)
            build()
        }
    }

    CompositionLocalProvider(
        LocalSnyggTheme provides snyggTheme,
        LocalSnyggDynamicLightColorScheme provides lightScheme,
        LocalSnyggDynamicDarkColorScheme provides darkScheme,
        LocalSnyggFontSizeMultiplier provides fontSizeMultiplier,
        LocalSnyggAssetResolver provides assetResolver,
        LocalSnyggPreloadedCustomFontFamilies provides snyggTheme.fontFamilies,
        LocalSnyggParentStyle provides initParentStyle,
    ) {
        ProvideSnyggStyle("root", rootAttributes, SnyggSelector.NONE) {
            content()
        }
    }
}

/**
 * Queries the current theme, passes the style to [content], and provides the style,
 * selector, and foreground color to descendants.
 * A null [elementName] inherits the parent style; [attributes] refine the style query.
 * A null [selector] inherits the parent selector. Requires [ProvideSnyggTheme].
 */
@Composable
internal fun ProvideSnyggStyle(
    elementName: String?,
    attributes: SnyggQueryAttributes,
    selector: SnyggSelector?,
    content: @Composable (style: SnyggSinglePropertySet) -> Unit
) {
    val theme = LocalSnyggTheme.current
    val style = theme.rememberQuery(elementName, attributes, selector)
    val parentSelector = selector ?: LocalSnyggParentSelector.current
    CompositionLocalProvider(
        LocalSnyggParentStyle provides style,
        LocalSnyggParentSelector provides parentSelector,
        LocalContentColor provides style.foreground(),
    ) {
        content(style)
    }
}

val SnyggRule.Companion.Saver: Saver<SnyggRule?, String>
    get() = Saver(
        save = { it?.toString() ?: "" },
        restore = { fromOrNull(it) },
    )

val SnyggRule.Companion.NonNullSaver: Saver<SnyggRule, String>
    get() = Saver(
        save = { it.toString() },
        restore = { fromOrNull(it)!! },
    )

/** Returns the current style for [elementName], refined by [attributes] and [selector]. */
@Composable
fun rememberSnyggThemeQuery(
    elementName: String,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
): SnyggSinglePropertySet {
    val theme = LocalSnyggTheme.current
    return theme.rememberQuery(elementName, attributes, selector)
}

/**
 * Returns whether the active stylesheet defines at least one rule for [elementName].
 */
@Composable
fun isSnyggThemeElementDefined(elementName: String): Boolean {
    return LocalSnyggTheme.current.style.containsKey(elementName)
}

@Composable
internal fun SnyggTheme.rememberQuery(
    elementName: String?,
    attributes: SnyggQueryAttributes,
    selector: SnyggSelector? = null,
): SnyggSinglePropertySet {
    val mergedSelector = selector ?: LocalSnyggParentSelector.current
    val parentStyle = LocalSnyggParentStyle.current
    val dynamicLightColorScheme = LocalSnyggDynamicLightColorScheme.current
    val dynamicDarkColorScheme = LocalSnyggDynamicDarkColorScheme.current
    val fontSizeMultiplier = LocalSnyggFontSizeMultiplier.current
    return remember(this, elementName, attributes, mergedSelector, parentStyle, dynamicLightColorScheme, dynamicDarkColorScheme, fontSizeMultiplier) {
        query(
            elementName = elementName ?: "",
            attributes,
            mergedSelector,
            parentStyle,
            dynamicLightColorScheme,
            dynamicDarkColorScheme,
            fontSizeMultiplier,
        )
    }
}

/// Modifier helpers

internal fun Modifier.snyggBackground(
    style: SnyggSinglePropertySet,
    default: Color = Color.Unspecified,
    shape: Shape = style.shape(),
    allowClip: Boolean = true,
): Modifier {
    val modifier = when (val bg = style.background) {
        is SnyggStaticColorValue -> this.background(
            color = bg.color,
            shape = shape,
        )
        else if (default.isSpecified) -> {
            this.background(
                color = default,
                shape = shape,
            )
        }
        else -> this
    }
    if (allowClip && style.clip !is SnyggNoValue) {
        return modifier.clip(shape)
    }
    return modifier
}

internal fun Modifier.snyggBorder(
    style: SnyggSinglePropertySet,
    width: Dp = style.borderWidth.dpSize().takeOrElse { 0.dp }.coerceAtLeast(0.dp),
    color: Color = style.borderColor.colorOrDefault(default = Color.Unspecified),
    shape: Shape = style.shape(),
): Modifier {
    return if (color.isSpecified) {
        this.border(width, color, shape)
    } else {
        this
    }
}

internal fun Modifier.snyggMargin(
    style: SnyggSinglePropertySet,
): Modifier {
    return when (style.margin) {
        is SnyggPaddingValue -> this.padding(style.margin.values)
        else -> return this
    }
}

internal fun Modifier.snyggPadding(
    style: SnyggSinglePropertySet,
    default: PaddingValues? = null,
): Modifier {
    return when (style.padding) {
        is SnyggPaddingValue -> this.padding(style.padding.values)
        else if (default != null) -> this.padding(default)
        else -> return this
    }
}

internal fun Modifier.snyggShadow(
    style: SnyggSinglePropertySet,
    elevation: Dp = style.shadowElevation.dpSize().takeOrElse { 0.dp }.coerceAtLeast(0.dp),
    shape: Shape = style.shape(),
    color: Color = style.shadowColor.colorOrDefault(default = DefaultShadowColor),
): Modifier {
    return this.shadow(elevation, shape, clip = false, ambientColor = color, spotColor = color)
}

@Composable
internal fun Modifier.snyggIconSize(
    style: SnyggSinglePropertySet,
): Modifier {
    return with(LocalDensity.current) {
        val fontSize = style.fontSize(default = 0.sp)
        if (fontSize.isSp && fontSize >= 1.sp) {
            this@snyggIconSize.size(fontSize.toDp())
        } else {
            this@snyggIconSize
        }
    }
}

/// SnyggValue helpers

internal fun SnyggValue.colorOrDefault(default: Color): Color {
    return when (this) {
        is SnyggStaticColorValue -> this.color
        else -> default
    }
}

internal fun SnyggValue.dpSize(default: Dp = Dp.Unspecified): Dp {
    return when (this) {
        is SnyggDpSizeValue -> this.dp
        else -> default
    }
}

fun SnyggValue.uriOrNull(): String? {
    return when (this) {
        is SnyggUriValue -> this.uri
        else -> null
    }
}
