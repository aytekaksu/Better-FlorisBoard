/*
 * Copyright (C) 2020-2025 The FlorisBoard Contributors
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

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceModel

internal const val DEFAULT_VERSION_NAME = "0.0.0"

object AppVersionUtils {
    private fun getRawVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName!!
        } catch (e: Exception) {
            "undefined"
        }
    }

    suspend fun updateVersionOnInstallAndLastUse(context: Context, prefs: FlorisPreferenceModel) {
        val currentVersion = getRawVersionName(context)
        if (prefs.internal.versionOnInstall.get() == DEFAULT_VERSION_NAME) {
            prefs.internal.versionOnInstall.set(currentVersion)
        }
        prefs.internal.versionLastUse.set(currentVersion)
    }
}
