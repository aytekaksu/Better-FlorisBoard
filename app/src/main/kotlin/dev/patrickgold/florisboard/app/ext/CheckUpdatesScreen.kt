/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import org.florisboard.lib.compose.stringRes

@Composable
fun CheckUpdatesScreen() = FlorisScreen {
    title = stringRes(R.string.ext__check_updates__title)

    val context = LocalContext.current
    val extensionManager by context.extensionManager()
    val extensionIndex by extensionManager.extensions.collectAsState()

    content {
        UpdateBox(extensionIndex)
    }
}
