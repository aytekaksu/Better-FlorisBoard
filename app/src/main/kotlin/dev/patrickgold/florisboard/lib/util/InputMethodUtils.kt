/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.delay
import org.florisboard.lib.android.AndroidSettings
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.systemServiceOrNull
import org.florisboard.lib.compose.observeAsState

private const val DELIMITER = ':'
private const val IME_SERVICE_CLASS_NAME = "dev.patrickgold.florisboard.FlorisImeService"
private const val TIMED_QUERY_DELAY = 500L

object InputMethodUtils {
    fun isFlorisboardEnabled(context: Context): Boolean {
        return if (AndroidVersion.ATLEAST_API34_U) {
            context.systemServiceOrNull(InputMethodManager::class)
                ?.enabledInputMethodList
                ?.any { it.packageName == BuildConfig.APPLICATION_ID } ?: false
        } else {
            val enabledImeList = AndroidSettings.Secure.getString(
                context, Settings.Secure.ENABLED_INPUT_METHODS
            )
            enabledImeList != null && parseIsFlorisboardEnabled(context, enabledImeList)
        }
    }

    fun isFlorisboardSelected(context: Context): Boolean {
        return if (AndroidVersion.ATLEAST_API34_U) {
            context.systemServiceOrNull(InputMethodManager::class)
                ?.currentInputMethodInfo
                ?.packageName == BuildConfig.APPLICATION_ID
        } else {
            val selectedIme = AndroidSettings.Secure.getString(
                context, Settings.Secure.DEFAULT_INPUT_METHOD
            )
            selectedIme != null && parseIsFlorisboardSelected(context, selectedIme)
        }
    }

    @Composable
    fun observeIsFlorisboardEnabled(
        context: Context = LocalContext.current.applicationContext,
        foregroundOnly: Boolean = false,
    ): State<Boolean> {
        return if (AndroidVersion.ATLEAST_API34_U) {
            timedObserveIsFlorisboard(isSelected = false)
        } else {
            AndroidSettings.Secure.observeAsState(
                key = Settings.Secure.ENABLED_INPUT_METHODS,
                foregroundOnly = foregroundOnly,
                transform = { parseIsFlorisboardEnabled(context, it.toString()) },
            )
        }
    }

    @Composable
    fun observeIsFlorisboardSelected(
        context: Context = LocalContext.current.applicationContext,
        foregroundOnly: Boolean = false,
    ): State<Boolean> {
        return if (AndroidVersion.ATLEAST_API34_U) {
            timedObserveIsFlorisboard(isSelected = true)
        } else {
            AndroidSettings.Secure.observeAsState(
                key = Settings.Secure.DEFAULT_INPUT_METHOD,
                foregroundOnly = foregroundOnly,
                transform = { parseIsFlorisboardSelected(context, it.toString()) },
            )
        }
    }

    fun parseIsFlorisboardEnabled(context: Context, activeImeIds: String): Boolean {
        return activeImeIds.split(DELIMITER).map { componentStr ->
            ComponentName.unflattenFromString(componentStr)
        }.any { it?.packageName == context.packageName && it.className == IME_SERVICE_CLASS_NAME }
    }

    fun parseIsFlorisboardSelected(context: Context, selectedImeId: String): Boolean {
        val component = ComponentName.unflattenFromString(selectedImeId)
        return component?.packageName == context.packageName && component.className == IME_SERVICE_CLASS_NAME
    }

    fun showImeEnablerActivity(context: Context) {
        val intent = Intent()
        intent.action = Settings.ACTION_INPUT_METHOD_SETTINGS
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        context.startActivity(intent)
    }

    fun showImePicker(context: Context): Boolean {
        val imm = context.systemServiceOrNull(InputMethodManager::class)
        return if (imm != null) {
            imm.showInputMethodPicker()
            true
        } else {
            false
        }
    }

    @RequiresApi(api = 34)
    @Composable
    private fun timedObserveIsFlorisboard(isSelected: Boolean): State<Boolean> {
        val state = remember { mutableStateOf(false) }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            while (true) {
                state.value = if (isSelected) {
                    isFlorisboardSelected(context)
                } else {
                    isFlorisboardEnabled(context)
                }
                delay(TIMED_QUERY_DELAY)
            }
        }
        return state
    }
}
