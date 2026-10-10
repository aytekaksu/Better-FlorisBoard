/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FlorisButtonBar(content: @Composable FlorisButtonBarScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 8.dp,
    ) {
        Column {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {}
            Row(
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 4.dp, start = 0.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val scope = FlorisButtonBarScope(this)
                content(scope)
            }
        }
    }
}

class FlorisButtonBarScope(rowScope: RowScope) : RowScope by rowScope {
    @Composable
    fun ButtonBarButton(
        text: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        Button(
            modifier = Modifier.padding(start = 16.dp),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            onClick = onClick,
        ) {
            Text(text = text)
        }
    }

    @Composable
    fun ButtonBarTextButton(
        text: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        TextButton(
            modifier = Modifier.padding(start = 16.dp),
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            onClick = onClick,
        ) {
            Text(text = text)
        }
    }

    @Composable
    fun ButtonBarSpacer() {
        Spacer(modifier = Modifier.weight(1f))
    }
}
