/*
 * Copyright (C) 2020-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.util

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceModel

internal const val DEFAULT_VERSION_NAME = "0.0.0"

object AppVersionUtils {
    // Package lookup failures must not interrupt startup or settings migration.
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
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
