/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.snygg.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector

/**
 * A theme-styled [Spacer] that uses the foreground color when no background is set.
 */
@Composable
fun SnyggSpacer(
    modifier: Modifier = Modifier,
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
) {
    ProvideSnyggStyle(elementName, attributes, selector) { style ->
        Spacer(
            modifier = modifier
                .snyggMargin(style)
                .snyggShadow(style)
                .snyggBackground(style, default = style.foreground())
                .snyggPadding(style),
        )
    }
}
