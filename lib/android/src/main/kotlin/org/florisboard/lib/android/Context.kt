/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
