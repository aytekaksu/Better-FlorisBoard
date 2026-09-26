/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.app.settings.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Padding
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamicColorScheme
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.material.ui.checkeredBackground
import org.florisboard.lib.color.getColor
import org.florisboard.lib.color.systemAccentOrDefault
import org.florisboard.lib.snygg.value.SnyggContentScaleValue
import org.florisboard.lib.snygg.value.SnyggCustomFontFamilyValue
import org.florisboard.lib.snygg.value.SnyggCutCornerDpShapeValue
import org.florisboard.lib.snygg.value.SnyggDefinedVarValue
import org.florisboard.lib.snygg.value.SnyggDpSizeValue
import org.florisboard.lib.snygg.value.SnyggDynamicDarkColorValue
import org.florisboard.lib.snygg.value.SnyggDynamicLightColorValue
import org.florisboard.lib.snygg.value.SnyggFontStyleValue
import org.florisboard.lib.snygg.value.SnyggFontWeightValue
import org.florisboard.lib.snygg.value.SnyggGenericFontFamilyValue
import org.florisboard.lib.snygg.value.SnyggNoValue
import org.florisboard.lib.snygg.value.SnyggPaddingValue
import org.florisboard.lib.snygg.value.SnyggRoundedCornerDpShapeValue
import org.florisboard.lib.snygg.value.SnyggShapeValue
import org.florisboard.lib.snygg.value.SnyggSpSizeValue
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.florisboard.lib.snygg.value.SnyggTextAlignValue
import org.florisboard.lib.snygg.value.SnyggTextDecorationLineValue
import org.florisboard.lib.snygg.value.SnyggTextOverflowValue
import org.florisboard.lib.snygg.value.SnyggUriValue
import org.florisboard.lib.snygg.value.SnyggValue
import org.florisboard.lib.snygg.value.SnyggYesValue

object SnyggValueIcon {
    interface Spec {
        val borderWith: Dp
        val boxShape: Shape
        val elevation: Dp
        val gridSize: Dp
        val iconSize: Dp
        val iconSizeMinusBorder: Dp
    }

    object Small : Spec {
        override val borderWith = Dp.Hairline
        override val boxShape = RoundedCornerShape(4.dp)
        override val elevation = 4.dp
        override val gridSize = 2.dp
        override val iconSize = 16.dp
        override val iconSizeMinusBorder = 16.dp
    }

    object Normal : Spec {
        override val borderWith = 1.dp
        override val boxShape = RoundedCornerShape(8.dp)
        override val elevation = 4.dp
        override val gridSize = 3.dp
        override val iconSize = 24.dp
        override val iconSizeMinusBorder = 22.dp
    }
}

@Composable
internal fun SnyggValueIcon(
    value: SnyggValue,
    definedVariables: Map<String, SnyggValue>,
    modifier: Modifier = Modifier,
    spec: SnyggValueIcon.Spec = SnyggValueIcon.Normal,
) {
    val prefs by FlorisPreferenceStore
    val accentColor by prefs.theme.accentColor.collectAsState()

    when (value) {
        is SnyggStaticColorValue -> {
            SnyggValueColorBox(modifier = modifier, spec = spec, backgroundColor = value.color)
        }
        is SnyggDynamicLightColorValue -> {
            val colorScheme = dynamicColorScheme(systemAccentOrDefault(accentColor), isDark = false)
            SnyggValueColorBox(modifier = modifier, spec = spec, backgroundColor = colorScheme.getColor(value.colorName))
        }
        is SnyggDynamicDarkColorValue -> {
            val colorScheme = dynamicColorScheme(systemAccentOrDefault(accentColor), isDark = true)
            SnyggValueColorBox(modifier = modifier, spec = spec, backgroundColor = colorScheme.getColor(value.colorName))
        }

        is SnyggShapeValue -> {
            Box(
                modifier = modifier
                    .requiredSize(spec.iconSizeMinusBorder)
                    .border(spec.borderWith, MaterialTheme.colorScheme.onBackground, value.alwaysPercentShape())
            )
        }

        is SnyggDefinedVarValue -> {
            val realValue = definedVariables[value.key]
            if (realValue == null) {
                Icon(
                    modifier = modifier.requiredSize(spec.iconSize),
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                )
            } else {
                val smallSpec = SnyggValueIcon.Small
                Box(modifier = modifier
                    .requiredSize(spec.iconSize)
                    .offset(y = (-2).dp)) {
                    SnyggValueIcon(
                        modifier = Modifier.offset(x = 8.dp, y = 8.dp),
                        value = realValue,
                        definedVariables = definedVariables,
                        spec = smallSpec,
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = 1.dp)
                            .requiredSize(smallSpec.iconSize)
                            .padding(vertical = 2.dp)
                            .background(MaterialTheme.colorScheme.background, spec.boxShape),
                    )
                    Icon(
                        modifier = Modifier.requiredSize(smallSpec.iconSize),
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                    )
                }
            }
        }

        else -> {
            plainSnyggValueIcon(value)?.let { icon ->
                Icon(
                    modifier = modifier.requiredSize(spec.iconSize),
                    imageVector = icon,
                    contentDescription = null,
                )
            }
        }
    }
}

internal fun plainSnyggValueIcon(value: SnyggValue): ImageVector? = when (value) {
    is SnyggGenericFontFamilyValue, is SnyggCustomFontFamilyValue -> Icons.Default.FontDownload
    is SnyggFontStyleValue -> Icons.Default.FormatItalic
    is SnyggFontWeightValue, is SnyggYesValue -> Icons.Default.FormatBold
    is SnyggPaddingValue -> Icons.Default.Padding
    is SnyggDpSizeValue -> Icons.Default.Straighten
    is SnyggSpSizeValue -> Icons.Default.FormatSize
    is SnyggTextAlignValue -> when (value.textAlign) {
        TextAlign.Left, TextAlign.Start -> Icons.AutoMirrored.Default.FormatAlignLeft
        TextAlign.Right, TextAlign.End -> Icons.AutoMirrored.Default.FormatAlignRight
        TextAlign.Justify -> Icons.Default.FormatAlignJustify
        else -> Icons.Default.FormatAlignCenter
    }
    is SnyggTextDecorationLineValue -> when (value.textDecoration) {
        TextDecoration.LineThrough -> Icons.Default.FormatStrikethrough
        else -> Icons.Default.FormatUnderlined
    }
    is SnyggTextOverflowValue -> Icons.AutoMirrored.Default.WrapText
    is SnyggUriValue -> Icons.Default.AttachFile
    is SnyggContentScaleValue -> Icons.Default.OpenInFull
    is SnyggNoValue -> Icons.Default.CheckBoxOutlineBlank
    else -> null
}

@Composable
internal fun SnyggValueColorBox(
    modifier: Modifier,
    spec: SnyggValueIcon.Spec,
    backgroundColor: Color
) {
    Surface(
        modifier = modifier.requiredSize(spec.iconSize),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = spec.elevation,
        shape = spec.boxShape,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .checkeredBackground(gridSize = spec.gridSize)
                .background(backgroundColor),
        )
    }
}

private const val UpscaleFactor = 3
private const val UpscaleMaxAbsoluteValue = 100

fun SnyggShapeValue.alwaysPercentShape(): Shape {
    return when (this) {
        is SnyggRoundedCornerDpShapeValue -> {
            RoundedCornerShape(
                (this.topStart.value.toInt() * UpscaleFactor).coerceAtMost(UpscaleMaxAbsoluteValue),
                (this.topEnd.value.toInt() * UpscaleFactor).coerceAtMost(UpscaleMaxAbsoluteValue),
                (this.bottomEnd.value.toInt() * UpscaleFactor).coerceAtMost(UpscaleMaxAbsoluteValue),
                (this.bottomStart.value.toInt() * UpscaleFactor).coerceAtMost(UpscaleMaxAbsoluteValue),
            )
        }
        is SnyggCutCornerDpShapeValue -> {
            CutCornerShape(
                (this.topStart.value.toInt() * UpscaleFactor).coerceAtMost(UpscaleMaxAbsoluteValue),
                (this.topEnd.value.toInt() * UpscaleFactor).coerceAtMost(UpscaleMaxAbsoluteValue),
                (this.bottomEnd.value.toInt() * UpscaleFactor).coerceAtMost(UpscaleMaxAbsoluteValue),
                (this.bottomStart.value.toInt() * UpscaleFactor).coerceAtMost(UpscaleMaxAbsoluteValue),
            )
        }
        else -> this.shape
    }
}
