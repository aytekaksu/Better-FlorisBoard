/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.kotlin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

typealias DeferredResult<T> = Deferred<Result<T>>

inline fun <T> CoroutineScope.runCatchingAsync(
    crossinline block: suspend CoroutineScope.() -> T,
): DeferredResult<T> {
    return this.async {
        runCatching { block() }
    }
}
