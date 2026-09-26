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

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector

/**
 * A theme-styled list item, similar to [ListItem], with optional leading and trailing icons.
 *
 * Disabling it blocks clicks and queries the [SnyggSelector.DISABLED] style.
 */
@Composable
fun SnyggListItem(
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    text: String,
    leadingImageVector: ImageVector? = null,
    trailingImageVector: ImageVector? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    indication: Indication = ripple(),
    enabled: Boolean = true,
) {
    val selector = if (enabled) SnyggSelector.NONE else SnyggSelector.DISABLED
    val decoratedModifier = modifier.clickable(
        interactionSource = interactionSource,
        indication = indication,
        enabled = enabled,
        onClickLabel = null,
        role = null,
        onClick = onClick,
    )
    SnyggRow(elementName, attributes, selector,
        modifier = decoratedModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingImageVector != null) {
            SnyggBox(
                elementName = "$elementName-icon-leading",
                attributes = attributes,
                selector = selector,
            ) {
                SnyggIcon(imageVector = leadingImageVector)
            }
        }
        SnyggText(
            elementName = "$elementName-text",
            attributes = attributes,
            selector = selector,
            modifier = Modifier.fillMaxWidth(),
            text = text,
        )
        if (trailingImageVector != null) {
            SnyggBox(
                elementName = "$elementName-icon-trailing",
                attributes = attributes,
                selector = selector,
            ) {
                SnyggIcon(imageVector = trailingImageVector)
            }
        }
    }
}
