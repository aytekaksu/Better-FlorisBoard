/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.kotlin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

fun <T> Flow<T>.collectIn(scope: CoroutineScope, collector: FlowCollector<T>) {
    scope.launch { collect(collector) }
}

fun <T> StateFlow<T>.collectLatestIn(scope: CoroutineScope, action: suspend (value: T) -> Unit) {
    scope.launch { collectLatest(action) }
}
