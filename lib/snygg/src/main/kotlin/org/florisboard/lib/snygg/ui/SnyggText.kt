/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package org.florisboard.lib.snygg.ui

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector

/**
 * Simple text composable, which displays the given [text].
 *
 * This composable infers its style from the current [SnyggTheme][org.florisboard.lib.snygg.SnyggTheme], which is
 * required to be provided by [ProvideSnyggTheme].
 *
 * @param elementName The name of this element. If `null` the style will be inherited from the parent element.
 * @param attributes The attributes of the element used to refine the query.
 * @param selector A specific SnyggSelector to query the style for.
 * @param modifier The modifier to be applied to the Text.
 * @param text The text of the element.
 * @param textAlign Optional text alignment override.
 * @param maxLines Optional maximum line count override.
 * @param overflow Optional text overflow override.
 * @param autoSize Optional automatic text sizing strategy.
 * @param contentStyleElementName Optional element whose foreground and font style should be used. Font size and
 * layout properties continue to come from [elementName].
 * @param fontSizeScale Scale applied to the resolved font size.
 *
 * @since 0.5.0-alpha01
 *
 * @see [Text]
 */
@Composable
fun SnyggText(
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
    modifier: Modifier = Modifier,
    text: String,
    textAlign: TextAlign? = null,
    maxLines: Int? = null,
    overflow: TextOverflow? = null,
    autoSize: TextAutoSize? = null,
    contentStyleElementName: String? = null,
    fontSizeScale: Float = 1f,
) {
    ProvideSnyggStyle(elementName, attributes, selector) { style ->
        val contentStyle = if (contentStyleElementName != null) {
            rememberSnyggThemeQuery(contentStyleElementName)
        } else {
            style
        }
        Text(
            modifier = modifier
                .snyggMargin(style)
                .snyggShadow(style)
                .snyggBorder(style)
                .snyggBackground(style, allowClip = false)
                .snyggPadding(style),
            text = text,
            color = contentStyle.foreground(),
            fontSize = style.fontSize() * fontSizeScale,
            fontStyle = contentStyle.fontStyle(),
            fontWeight = contentStyle.fontWeight(),
            fontFamily = contentStyle.fontFamily(LocalSnyggPreloadedCustomFontFamilies.current),
            letterSpacing = contentStyle.letterSpacing(),
            lineHeight = style.lineHeight(),
            textAlign = textAlign ?: style.textAlign(),
            textDecoration = style.textDecorationLine(),
            maxLines = maxLines ?: style.textMaxLines(),
            overflow = overflow ?: style.textOverflow(),
            autoSize = autoSize,
        )
    }
}
