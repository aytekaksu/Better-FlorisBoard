/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.ext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.lib.ext.ExtensionMaintainer
import dev.patrickgold.florisboard.lib.ext.safeMaintainerWebUrlOrNull
import dev.patrickgold.florisboard.lib.util.launchUrl
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.florisboard.lib.compose.FlorisChip

@Composable
fun ExtensionMaintainerChip(
    maintainer: ExtensionMaintainer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val safeUrl = remember(maintainer.url) {
        maintainer.url?.safeMaintainerWebUrlOrNull()
    }

    FlorisChip(
        modifier = modifier,
        text = maintainer.name,
        trailingIcons = when {
            maintainer.email != null && safeUrl != null -> listOf(
                Icons.Outlined.Mail,
                Icons.Default.Link,
            )
            maintainer.email != null -> listOf(Icons.Outlined.Mail)
            safeUrl != null -> listOf(Icons.Default.Link)
            else -> listOf()
        },
        onClick = { showDialog = !showDialog },
        enabled = maintainer.email != null || safeUrl != null,
        shape = RoundedCornerShape(4.dp),
    )

    if (showDialog) {
        JetPrefAlertDialog(
            title = maintainer.name,
            onDismiss = { showDialog = false },
        ) {
            Column {
                if (maintainer.email != null) {
                    FlorisChip(
                        onClick = { context.launchUrl("mailto:${maintainer.email}") },
                        text = maintainer.email,
                        leadingIcons = listOf(Icons.Outlined.Mail),
                        shape = RoundedCornerShape(4.dp),
                    )
                }
                if (safeUrl != null) {
                    FlorisChip(
                        onClick = { context.launchUrl(safeUrl) },
                        text = maintainer.url.orEmpty(),
                        leadingIcons = listOf(Icons.Default.Link),
                        shape = RoundedCornerShape(4.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewChipNameOnly() {
    val maintainer = ExtensionMaintainer(
        name = "Jane Doe",
        email = null,
        url = null,
    )
    ExtensionMaintainerChip(maintainer)
}

@Preview(showBackground = true)
@Composable
private fun PreviewChipNameAndEmail() {
    val maintainer = ExtensionMaintainer(
        name = "Jane Doe",
        email = "jane.doe@example.com",
        url = null,
    )
    ExtensionMaintainerChip(maintainer)
}

@Preview(showBackground = true)
@Composable
private fun PreviewChipNameAndUrl() {
    val maintainer = ExtensionMaintainer(
        name = "Jane Doe",
        email = null,
        url = "jane-doe.example.com",
    )
    ExtensionMaintainerChip(maintainer)
}
