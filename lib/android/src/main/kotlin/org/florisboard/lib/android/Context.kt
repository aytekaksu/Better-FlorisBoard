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

@file:Suppress("NOTHING_TO_INLINE")

package org.florisboard.lib.android

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import java.io.File
import org.florisboard.lib.kotlin.CurlyArg
import org.florisboard.lib.kotlin.curlyFormat
import kotlin.reflect.KClass

/** Returns the requested system service, or throws if the device does not provide it. */
fun <T : Any> Context.systemService(kClass: KClass<T>): T = getSystemService(kClass.java)!!

/** Returns the requested system service when the device provides it. */
fun <T : Any> Context.systemServiceOrNull(kClass: KClass<T>): T? {
    return try {
        getSystemService(kClass.java)
    } catch (_: Exception) {
        null
    }
}

/** Counts only space available now, without assuming other apps' caches can be evicted. */
@SuppressLint("UsableSpace")
fun File.conservativeUsableSpace(): Long = usableSpace

/** Returns plain resource text, or throws if [id] is missing. */
@Throws(android.content.res.Resources.NotFoundException::class)
inline fun Context.stringRes(@StringRes id: Int): String {
    return this.resources.getString(id)
}

/** Returns plain resource text with [String.curlyFormat] placeholders filled from [args]. */
@Throws(android.content.res.Resources.NotFoundException::class)
inline fun Context.stringRes(@StringRes id: Int, vararg args: CurlyArg): String {
    return this.resources.getString(id).curlyFormat(*args)
}
