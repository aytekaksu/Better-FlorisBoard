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

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector

/**
 * Draws a theme-tinted [imageVector].
 *
 * Give meaningful icons a [contentDescription] for accessibility; leave it null only for decoration.
 */
@Composable
fun SnyggIcon(
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
    modifier: Modifier = Modifier,
    imageVector: ImageVector,
    contentDescription: String? = null,
) {
    ProvideSnyggStyle(elementName, attributes, selector) { style ->
        Icon(
            modifier = modifier.snyggIconSize(style),
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = style.foreground(),
        )
    }
}

/**
 * Draws a theme-tinted [painter].
 *
 * Give meaningful icons a [contentDescription] for accessibility; leave it null only for decoration.
 */
@Composable
fun SnyggIcon(
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
    modifier: Modifier = Modifier,
    painter: Painter,
    contentDescription: String? = null,
) {
    ProvideSnyggStyle(elementName, attributes, selector) { style ->
        Icon(
            modifier = modifier.snyggIconSize(style),
            painter = painter,
            contentDescription = contentDescription,
            tint = style.foreground(),
        )
    }
}
