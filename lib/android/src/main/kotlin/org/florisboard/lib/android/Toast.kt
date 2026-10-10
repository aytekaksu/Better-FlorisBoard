/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.android

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.kotlin.CurlyArg

private suspend fun Context.showToast(text: String, duration: Int): Toast =
    withContext(Dispatchers.Main.immediate) {
        Toast.makeText(this@showToast, text, duration).also { it.show() }
    }

/** Shows text in a short toast. */
suspend fun Context.showShortToast(text: String): Toast = showToast(text, Toast.LENGTH_SHORT)

/** Shows a string resource in a short toast; [id] must not be 0. */
suspend fun Context.showShortToast(@StringRes id: Int): Toast = showShortToast(stringRes(id))

/** Formats a string resource ([id] must not be 0), then shows a short toast. */
suspend fun Context.showShortToast(@StringRes id: Int, vararg args: CurlyArg): Toast =
    showShortToast(stringRes(id, *args))

/** Shows text in a long toast. */
suspend fun Context.showLongToast(text: String): Toast = showToast(text, Toast.LENGTH_LONG)

/** Shows a string resource in a long toast; [id] must not be 0. */
suspend fun Context.showLongToast(@StringRes id: Int): Toast = showLongToast(stringRes(id))

/** Formats a string resource ([id] must not be 0), then shows a long toast. */
suspend fun Context.showLongToast(@StringRes id: Int, vararg args: CurlyArg): Toast =
    showLongToast(stringRes(id, *args))
