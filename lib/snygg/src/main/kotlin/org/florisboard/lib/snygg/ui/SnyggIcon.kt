/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
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
    painter: Painter,
    modifier: Modifier = Modifier,
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
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
