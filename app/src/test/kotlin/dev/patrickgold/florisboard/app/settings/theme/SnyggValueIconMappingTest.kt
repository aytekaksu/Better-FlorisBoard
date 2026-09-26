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

package dev.patrickgold.florisboard.app.settings.theme

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
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Padding
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.florisboard.lib.snygg.value.SnyggContentScaleValue
import org.florisboard.lib.snygg.value.SnyggCustomFontFamilyValue
import org.florisboard.lib.snygg.value.SnyggDpSizeValue
import org.florisboard.lib.snygg.value.SnyggFontStyleValue
import org.florisboard.lib.snygg.value.SnyggFontWeightValue
import org.florisboard.lib.snygg.value.SnyggGenericFontFamilyValue
import org.florisboard.lib.snygg.value.SnyggInheritValue
import org.florisboard.lib.snygg.value.SnyggNoValue
import org.florisboard.lib.snygg.value.SnyggPaddingValue
import org.florisboard.lib.snygg.value.SnyggSpSizeValue
import org.florisboard.lib.snygg.value.SnyggTextAlignValue
import org.florisboard.lib.snygg.value.SnyggTextDecorationLineValue
import org.florisboard.lib.snygg.value.SnyggTextOverflowValue
import org.florisboard.lib.snygg.value.SnyggUriValue
import org.florisboard.lib.snygg.value.SnyggYesValue

class SnyggValueIconMappingTest : FunSpec({
    test("plain icons keep theme editor meanings") {
        listOf(
            SnyggGenericFontFamilyValue.defaultValue() to Icons.Default.FontDownload,
            SnyggCustomFontFamilyValue.defaultValue() to Icons.Default.FontDownload,
            SnyggFontStyleValue.defaultValue() to Icons.Default.FormatItalic,
            SnyggFontWeightValue.defaultValue() to Icons.Default.FormatBold,
            SnyggPaddingValue.defaultValue() to Icons.Default.Padding,
            SnyggDpSizeValue.defaultValue() to Icons.Default.Straighten,
            SnyggSpSizeValue.defaultValue() to Icons.Default.FormatSize,
            SnyggTextAlignValue(TextAlign.Left) to Icons.AutoMirrored.Default.FormatAlignLeft,
            SnyggTextAlignValue(TextAlign.Start) to Icons.AutoMirrored.Default.FormatAlignLeft,
            SnyggTextAlignValue(TextAlign.Right) to Icons.AutoMirrored.Default.FormatAlignRight,
            SnyggTextAlignValue(TextAlign.End) to Icons.AutoMirrored.Default.FormatAlignRight,
            SnyggTextAlignValue(TextAlign.Justify) to Icons.Default.FormatAlignJustify,
            SnyggTextAlignValue(TextAlign.Center) to Icons.Default.FormatAlignCenter,
            SnyggTextDecorationLineValue(TextDecoration.LineThrough) to Icons.Default.FormatStrikethrough,
            SnyggTextDecorationLineValue(TextDecoration.None) to Icons.Default.FormatUnderlined,
            SnyggTextOverflowValue.defaultValue() to Icons.AutoMirrored.Default.WrapText,
            SnyggUriValue.defaultValue() to Icons.Default.AttachFile,
            SnyggContentScaleValue.defaultValue() to Icons.Default.OpenInFull,
            SnyggYesValue to Icons.Default.FormatBold,
            SnyggNoValue to Icons.Default.CheckBoxOutlineBlank,
        ).forEach { (value, icon) -> plainSnyggValueIcon(value) shouldBe icon }
        plainSnyggValueIcon(SnyggInheritValue) shouldBe null
    }
})
