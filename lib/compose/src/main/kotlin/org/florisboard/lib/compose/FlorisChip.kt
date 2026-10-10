/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun FlorisChip(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { },
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    leadingIcons: List<ImageVector> = listOf(),
    trailingIcons: List<ImageVector> = listOf(),
) {
    InputChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        label = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        leadingIcon = {
            Row {
                for (leadingIcon in leadingIcons) {
                    Icon(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        imageVector = leadingIcon,
                        contentDescription = null,
                    )
                }
            }
        },
        trailingIcon = {
            Row {
                for (trailingIcon in trailingIcons) {
                    Icon(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(16.dp),
                        imageVector = trailingIcon,
                        contentDescription = null,
                    )
                }
            }
        }
    )
}
