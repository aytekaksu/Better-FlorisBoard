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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.florisboard.lib.snygg.SnyggQueryAttributes
import org.florisboard.lib.snygg.SnyggSelector

/**
 * A horizontal [Row] styled by the current Snygg theme.
 *
 * [clickAndSemanticsModifier] is applied after the background and before padding.
 */
@Composable
fun SnyggRow(
    elementName: String? = null,
    attributes: SnyggQueryAttributes = emptyMap(),
    selector: SnyggSelector? = null,
    modifier: Modifier = Modifier,
    clickAndSemanticsModifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    ProvideSnyggStyle(elementName, attributes, selector) { style ->
        Row(
            modifier = modifier
                .snyggMargin(style)
                .snyggShadow(style)
                .snyggBorder(style)
                .snyggBackground(style)
                .then(clickAndSemanticsModifier)
                .snyggPadding(style),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}
