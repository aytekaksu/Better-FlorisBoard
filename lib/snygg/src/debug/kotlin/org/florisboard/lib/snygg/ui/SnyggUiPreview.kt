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

@file:Suppress("FunctionNaming", "MagicNumber")

package org.florisboard.lib.snygg.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.florisboard.lib.snygg.SnyggStylesheet

private val galleryStylesheet = SnyggStylesheet.v2 {
    "gallery" {
        background = rgbaColor(255, 255, 255)
        foreground = rgbaColor(32, 32, 32)
        fontSize = fontSize(16.sp)
        padding = padding(12.dp)
    }
    "card" {
        background = rgbaColor(240, 240, 240)
        margin = padding(0.dp, 0.dp, 0.dp, 8.dp)
        padding = padding(8.dp)
        shape = roundedCornerShape(8.dp)
    }
    "text"("tone" to listOf("accent")) {
        foreground = rgbaColor(0, 90, 180)
        fontWeight = fontWeight(FontWeight.Bold)
    }
    "row" {
        margin = padding(0.dp, 0.dp, 0.dp, 8.dp)
    }
    "divider" {
        margin = padding(horizontal = 8.dp, vertical = 0.dp)
    }
    "action" {
        background = rgbaColor(224, 224, 224)
        foreground = rgbaColor(32, 32, 32)
        padding = padding(6.dp)
        shape = roundedCornerShape(4.dp)
    }
    "chip" {
        background = rgbaColor(0, 90, 180)
        foreground = rgbaColor(255, 255, 255)
        margin = padding(8.dp, 0.dp, 0.dp, 0.dp)
        padding = padding(horizontal = 8.dp, vertical = 6.dp)
        shape = roundedCornerShape(50)
    }
    "list-item" {
        background = rgbaColor(240, 240, 240)
        padding = padding(8.dp)
    }
    "list-item-icon-leading" {
        padding = padding(0.dp, 0.dp, 8.dp, 0.dp)
    }
    "list-item-text" {
        textMaxLines = textMaxLines(1)
        textOverflow = textOverflow(TextOverflow.Ellipsis)
    }
}

@Preview(name = "Snygg components", showBackground = true, widthDp = 360)
@Composable
private fun SnyggComponentGalleryPreview() {
    ProvideSnyggTheme(rememberSnyggTheme(galleryStylesheet)) {
        SnyggColumn("gallery") {
            SnyggBox("card") {
                SnyggText(
                    elementName = "text",
                    attributes = mapOf("tone" to "accent"),
                    text = "Styled Snygg components",
                )
            }
            SnyggRow("row", verticalAlignment = Alignment.CenterVertically) {
                SnyggIcon("icon", imageVector = Icons.Default.Search)
                SnyggSpacer(
                    "divider",
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp),
                )
                SnyggIconButton("action", onClick = {}) {
                    SnyggIcon(imageVector = Icons.Default.Search)
                }
                SnyggChip(
                    elementName = "chip",
                    imageVector = Icons.Default.Search,
                    onClick = {},
                    text = "Chip",
                )
            }
            SnyggListItem(
                elementName = "list-item",
                leadingImageVector = Icons.Default.Search,
                onClick = {},
                text = "A list item with a deliberately long label that must ellipsize",
            )
        }
    }
}
