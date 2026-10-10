/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable

fun LazyGridScope.header(
    key: Any? = null,
    content: @Composable LazyGridItemScope.() -> Unit,
) {
    item(key, span = { GridItemSpan(this.maxLineSpan) }, content = content)
}
