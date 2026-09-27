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

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Settings
import org.florisboard.lib.kotlin.tryOrNull
import java.lang.reflect.Modifier
import kotlin.reflect.KClass

abstract class AndroidSettingsHelper(
    private val kClass: KClass<*>,
    val groupId: String,
) {
    abstract fun getString(context: Context, key: String): String?

    abstract fun getUriFor(key: String): Uri?

    fun getAllKeys(): Sequence<Pair<String, String>> = sequence {
        for (field in kClass.java.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) {
                val value = tryOrNull { field.get(null) } as? String ?: continue
                yield(field.name to value)
            }
        }
    }

    fun observe(context: Context, key: String, observer: SystemSettingsObserver) {
        getUriFor(key)?.let { uri ->
            context.contentResolver.registerContentObserver(uri, false, observer)
            observer.dispatchChange(false, uri)
        }
    }

    fun removeObserver(context: Context, observer: SystemSettingsObserver) {
        context.contentResolver.unregisterContentObserver(observer)
    }
}

object AndroidSettings {
    private fun group(
        kClass: KClass<*>,
        groupId: String,
        read: (ContentResolver, String) -> String?,
        uriFor: (String) -> Uri?,
    ): AndroidSettingsHelper = object : AndroidSettingsHelper(kClass, groupId) {
        override fun getString(context: Context, key: String): String? =
            tryOrNull { read(context.contentResolver, key) }

        override fun getUriFor(key: String): Uri? = tryOrNull { uriFor(key) }
    }

    val Global = group(Settings.Global::class, "global", Settings.Global::getString, Settings.Global::getUriFor)
    val Secure = group(Settings.Secure::class, "secure", Settings.Secure::getString, Settings.Secure::getUriFor)
    val System = group(Settings.System::class, "system", Settings.System::getString, Settings.System::getUriFor)
}
