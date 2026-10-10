/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.ext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import org.florisboard.lib.compose.stringRes

@Composable
internal fun ExtensionNotFoundScreen(id: String) = FlorisScreen {
    title = stringRes(R.string.ext__error__not_found_title)

    content {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringRes(R.string.ext__error__not_found_description, "id" to id))
        }
    }
}
