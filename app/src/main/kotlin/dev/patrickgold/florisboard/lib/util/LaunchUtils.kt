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

package dev.patrickgold.florisboard.lib.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.io.FlorisRef
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.kotlin.CurlyArg
import java.net.URI
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.reflect.KClass

fun Context.launchUrl(url: String) {
    val intent = Intent().also {
        it.action = Intent.ACTION_VIEW
        it.data = FlorisRef.fromUrl(url).uri
        it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        this.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        flogError { "URL launch failed: error=${e.javaClass.simpleName}" }
        Toast.makeText(
            this,
            this.stringRes(R.string.general__no_browser_app_found_for_url, "url" to url),
            Toast.LENGTH_LONG,
        ).show()
    }
}

fun Context.launchUrl(@StringRes url: Int) {
    launchUrl(this.stringRes(url))
}

fun Context.launchUrl(@StringRes url: Int, vararg args: CurlyArg) {
    launchUrl(this.stringRes(url, *args))
}

fun String.safePluginHttpsUrlOrNull(): String? {
    val target = trim()
    val uri = runCatching { URI(target) }.getOrNull() ?: return null
    return target.takeIf {
        uri.isAbsolute &&
            !uri.isOpaque &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }?.replaceRange(0, uri.scheme.length, "https")
}

fun Context.launchPluginHttpsUrl(url: String) {
    val target = url.safePluginHttpsUrlOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        flogError { "Plugin URL launch failed: error=${error.javaClass.simpleName}" }
        Toast.makeText(
            this,
            stringRes(R.string.general__no_browser_app_found_for_url, "url" to target),
            Toast.LENGTH_LONG,
        ).show()
    } catch (error: SecurityException) {
        flogError { "Plugin URL launch failed: error=${error.javaClass.simpleName}" }
    }
}

inline fun <T : Any> Context.launchActivity(kClass: KClass<T>, intentModifier: (Intent) -> Unit = { }) {
    contract {
        callsInPlace(intentModifier, InvocationKind.AT_MOST_ONCE)
    }
    try {
        val intent = Intent(this, kClass.java)
        intentModifier(intent)
        this.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        flogError { "Activity launch failed: error=${e.javaClass.simpleName}" }
        Toast.makeText(this, e.localizedMessage, Toast.LENGTH_LONG).show()
    }
}

inline fun Context.launchActivity(intentModifier: (Intent) -> Unit) {
    contract {
        callsInPlace(intentModifier, InvocationKind.AT_MOST_ONCE)
    }
    try {
        val intent = Intent()
        intentModifier(intent)
        this.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        flogError { "Activity launch failed: error=${e.javaClass.simpleName}" }
        Toast.makeText(this, e.localizedMessage, Toast.LENGTH_LONG).show()
    }
}
