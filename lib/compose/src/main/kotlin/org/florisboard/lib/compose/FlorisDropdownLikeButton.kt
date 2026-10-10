/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import dev.patrickgold.jetpref.material.ui.JetPrefDropdownMenuDefaults
import dev.patrickgold.jetpref.material.ui.JetPrefTextField
import dev.patrickgold.jetpref.material.ui.JetPrefTextFieldAppearance


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlorisDropdownLikeButton(
    item: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onClick: () -> Unit = { },
    appearance: JetPrefTextFieldAppearance = JetPrefDropdownMenuDefaults.filled(),
) {
    Box(
        modifier = modifier.wrapContentSize(Alignment.TopStart)
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val currentOnClick = rememberUpdatedState(onClick)
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Press) currentOnClick.value()
            }
        }

        JetPrefTextField(
            modifier = Modifier.fillMaxWidth(),
            value = item,
            onValueChange = {},
            enabled = true,
            readOnly = true,
            isError = isError,
            singleLine = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = true,
                    modifier = Modifier.rotate(90f), //Arrow to the right
                )
            },
            appearance = appearance,
            interactionSource = interactionSource,
        )
    }
}
