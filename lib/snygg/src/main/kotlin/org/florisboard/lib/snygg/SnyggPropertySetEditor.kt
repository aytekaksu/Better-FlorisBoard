/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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

package org.florisboard.lib.snygg

import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import org.florisboard.lib.snygg.value.RgbaColor
import org.florisboard.lib.snygg.value.SnyggCircleShapeValue
import org.florisboard.lib.snygg.value.SnyggDefinedVarValue
import org.florisboard.lib.snygg.value.SnyggDpSizeValue
import org.florisboard.lib.snygg.value.SnyggDynamicDarkColorValue
import org.florisboard.lib.snygg.value.SnyggDynamicLightColorValue
import org.florisboard.lib.snygg.value.SnyggFontStyleValue
import org.florisboard.lib.snygg.value.SnyggFontWeightValue
import org.florisboard.lib.snygg.value.SnyggGenericFontFamilyValue
import org.florisboard.lib.snygg.value.SnyggInheritValue
import org.florisboard.lib.snygg.value.SnyggPaddingValue
import org.florisboard.lib.snygg.value.SnyggRectangleShapeValue
import org.florisboard.lib.snygg.value.SnyggRoundedCornerDpShapeValue
import org.florisboard.lib.snygg.value.SnyggRoundedCornerPercentShapeValue
import org.florisboard.lib.snygg.value.SnyggSpSizeValue
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import org.florisboard.lib.snygg.value.SnyggTextAlignValue
import org.florisboard.lib.snygg.value.SnyggTextMaxLinesValue
import org.florisboard.lib.snygg.value.SnyggTextOverflowValue
import org.florisboard.lib.snygg.value.SnyggUriValue
import org.florisboard.lib.snygg.value.SnyggValue
import org.florisboard.lib.snygg.value.isInherit
import org.florisboard.lib.snygg.value.isUndefined
import java.util.UUID
import kotlin.reflect.KProperty

sealed interface SnyggPropertySetEditor {
    fun build(): SnyggPropertySet
}

class SnyggSinglePropertySetEditor(initProperties: Map<String, SnyggValue>? = null) : SnyggPropertySetEditor {
    val uuid = UUID.randomUUID().toString()
    val properties = mutableMapOf<String, SnyggValue>()

    init {
        if (initProperties != null) {
            properties.putAll(initProperties)
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline operator fun String.getValue(thisRef: SnyggSinglePropertySetEditor, property: KProperty<*>): SnyggValue? =
        thisRef.properties[this]

    @Suppress("NOTHING_TO_INLINE")
    private inline operator fun String.setValue(
        thisRef: SnyggSinglePropertySetEditor,
        property: KProperty<*>,
        value: SnyggValue?,
    ) = thisRef.setProperty(this, value)

    private fun setProperty(key: String, value: SnyggValue?) {
        if (value == null) {
            properties.remove(key)
        } else {
            properties[key] = value
        }
    }

    internal fun applyAll(
        thisStyle: SnyggSinglePropertySet,
        parentStyle: SnyggSinglePropertySet,
        fontSizeMultiplier: Float,
    ) {
        for ((property, value) in thisStyle.properties) {
            val transformedValue = when {
                value.isUndefined() -> null
                value.isInherit() -> parentStyle.properties[property]
                value is SnyggSpSizeValue && value.sp.isSpecified ->
                    SnyggSpSizeValue(value.sp * fontSizeMultiplier)
                else -> value
            }
            setProperty(property, transformedValue)
        }
        inheritImplicitly(parentStyle)
    }

    internal fun inheritImplicitly(parentStyle: SnyggSinglePropertySet) {
        // TODO: pattern properties??
        for ((property, propertySpec) in SnyggSpec.elementsSpec.properties) {
            if (propertySpec.inheritsImplicitly() && !properties.contains(property)) {
                // inherit implicitly
                setProperty(property, parentStyle.properties[property])
            }
        }
    }

    override fun build() = SnyggSinglePropertySet(properties.toMap())

    infix fun String.to(v: SnyggValue) {
        properties[this] = v
    }

    @Deprecated(
        level = DeprecationLevel.ERROR,
        message = "Only snygg values are allowed",
    )
    infix fun String.to(v: Any): Nothing {
        throw IllegalArgumentException("Only snygg values are allowed (given value: $v)")
    }

    var background: SnyggValue? by Snygg.Background
    var foreground: SnyggValue? by Snygg.Foreground

    var borderColor: SnyggValue? by Snygg.BorderColor
    var borderWidth: SnyggValue? by Snygg.BorderWidth

    var fontFamily: SnyggValue? by Snygg.FontFamily
    var fontSize: SnyggValue? by Snygg.FontSize
    var fontStyle: SnyggValue? by Snygg.FontStyle
    var fontWeight: SnyggValue? by Snygg.FontWeight
    var margin: SnyggValue? by Snygg.Margin
    var padding: SnyggValue? by Snygg.Padding

    var shadowColor: SnyggValue? by Snygg.ShadowColor
    var shadowElevation: SnyggValue? by Snygg.ShadowElevation

    var shape: SnyggValue? by Snygg.Shape
    var src: SnyggValue? by Snygg.Src

    var textAlign: SnyggValue? by Snygg.TextAlign
    var textMaxLines: SnyggValue? by Snygg.TextMaxLines
    var textOverflow: SnyggValue? by Snygg.TextOverflow

    fun rgbaColor(
        @IntRange(from = RgbaColor.ColorRangeMin.toLong(), to = RgbaColor.ColorRangeMax.toLong())
        r: Int,
        @IntRange(from = RgbaColor.ColorRangeMin.toLong(), to = RgbaColor.ColorRangeMax.toLong())
        g: Int,
        @IntRange(from = RgbaColor.ColorRangeMin.toLong(), to = RgbaColor.ColorRangeMax.toLong())
        b: Int,
        @FloatRange(from = RgbaColor.AlphaRangeMin.toDouble(), to = RgbaColor.AlphaRangeMax.toDouble())
        a: Float = RgbaColor.AlphaRangeMax,
    ): SnyggStaticColorValue {
        require(r in RgbaColor.ColorRange)
        require(g in RgbaColor.ColorRange)
        require(b in RgbaColor.ColorRange)
        require(a in RgbaColor.AlphaRange)
        val red = r.toFloat() / RgbaColor.ColorRangeMax
        val green = g.toFloat() / RgbaColor.ColorRangeMax
        val blue = b.toFloat() / RgbaColor.ColorRangeMax
        return SnyggStaticColorValue(Color(red, green, blue, a))
    }

    fun dynamicLightColor(name: String): SnyggDynamicLightColorValue = SnyggDynamicLightColorValue(name)

    fun dynamicDarkColor(name: String): SnyggDynamicDarkColorValue = SnyggDynamicDarkColorValue(name)

    fun genericFontFamily(fontFamily: FontFamily): SnyggGenericFontFamilyValue = SnyggGenericFontFamilyValue(fontFamily)

    fun fontStyle(fontStyle: FontStyle): SnyggFontStyleValue = SnyggFontStyleValue(fontStyle)

    fun fontWeight(fontWeight: FontWeight): SnyggFontWeightValue = SnyggFontWeightValue(fontWeight)

    fun textAlign(textAlign: TextAlign): SnyggTextAlignValue = SnyggTextAlignValue(textAlign)

    fun textMaxLines(maxLines: Int): SnyggTextMaxLinesValue {
        require(maxLines >= 1)
        return SnyggTextMaxLinesValue(maxLines)
    }

    fun textOverflow(textOverflow: TextOverflow): SnyggTextOverflowValue = SnyggTextOverflowValue(textOverflow)

    fun rectangleShape(): SnyggRectangleShapeValue = SnyggRectangleShapeValue()

    fun circleShape(): SnyggCircleShapeValue = SnyggCircleShapeValue()

    fun roundedCornerShape(cornerSize: Dp): SnyggRoundedCornerDpShapeValue =
        SnyggRoundedCornerDpShapeValue(cornerSize, cornerSize, cornerSize, cornerSize)

    fun roundedCornerShape(
        topStart: Dp,
        topEnd: Dp,
        bottomEnd: Dp,
        bottomStart: Dp,
    ): SnyggRoundedCornerDpShapeValue = SnyggRoundedCornerDpShapeValue(topStart, topEnd, bottomEnd, bottomStart)

    fun roundedCornerShape(cornerSize: Int): SnyggRoundedCornerPercentShapeValue =
        SnyggRoundedCornerPercentShapeValue(cornerSize, cornerSize, cornerSize, cornerSize)

    fun padding(
        start: Dp,
        top: Dp,
        end: Dp,
        bottom: Dp,
    ): SnyggPaddingValue = SnyggPaddingValue(PaddingValues(start, top, end, bottom))

    fun padding(
        horizontal: Dp,
        vertical: Dp,
    ): SnyggPaddingValue = SnyggPaddingValue(PaddingValues(horizontal, vertical))

    fun padding(all: Dp): SnyggPaddingValue = SnyggPaddingValue(PaddingValues(all))

    fun size(dp: Dp): SnyggDpSizeValue = SnyggDpSizeValue(dp)

    fun fontSize(sp: TextUnit): SnyggSpSizeValue = SnyggSpSizeValue(sp)

    fun uri(uri: String): SnyggUriValue = SnyggUriValue(uri)

    fun `var`(key: String): SnyggDefinedVarValue = SnyggDefinedVarValue(key)

    fun inherit(): SnyggInheritValue = SnyggInheritValue
}

class SnyggMultiplePropertySetsEditor(initSets: List<SnyggSinglePropertySet>? = null) : SnyggPropertySetEditor {
    val sets = mutableListOf<SnyggSinglePropertySetEditor>()

    init {
        initSets?.forEach { sets.add(it.edit()) }
    }

    fun add(configure: SnyggSinglePropertySetEditor.() -> Unit) {
        val editor = SnyggSinglePropertySetEditor()
        editor.configure()
        sets.add(editor)
    }

    override fun build() = SnyggMultiplePropertySets(sets.map { it.build() })
}
