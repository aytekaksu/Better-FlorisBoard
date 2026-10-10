/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.value.SnyggStaticColorValue

/**
 * A theme-styled icon button with optional [onLongClick].
 *
 * Disabling it blocks clicks and queries the [SnyggSelector.DISABLED] style. A static
 * foreground color from that style is provided to the content as its local content color.
 */
@Composable
fun SnyggIconButton(
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = interactionSource ?: remember { MutableInteractionSource() }
    val selector = if (enabled) SnyggSelector.NONE else SnyggSelector.DISABLED
    ProvideSnyggStyle(elementName, attributes, selector) { style ->
        Box(
            modifier = Modifier
                .snyggMargin(style)
                .snyggShadow(style)
                .snyggBorder(style)
                .snyggBackground(style, allowClip = true)
                .then(modifier)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = ripple(),
                )
                .snyggPadding(style),
            contentAlignment = Alignment.Center,
        ) {
            val foreground = style.foreground
            if (foreground is SnyggStaticColorValue) {
                CompositionLocalProvider(LocalContentColor provides foreground.color, content = content)
            } else {
                content()
            }
        }
    }
}
