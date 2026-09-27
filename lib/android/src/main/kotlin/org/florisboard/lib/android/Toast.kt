/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
