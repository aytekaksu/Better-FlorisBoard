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

package dev.patrickgold.florisboard.app.ext

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.OwnedRoutePopResult
import dev.patrickgold.florisboard.app.popOwnedRouteWhenResumed
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionDefaults
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.compose.stringRes

internal enum class ExtensionExportState {
    WAITING_FOR_DESTINATION,
    WRITING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

internal class ExtensionExportViewModel : ViewModel() {
    var state by mutableStateOf(ExtensionExportState.WAITING_FOR_DESTINATION)
        private set

    private var pickerLaunched = false

    fun claimPickerLaunch(): Boolean {
        if (pickerLaunched || state != ExtensionExportState.WAITING_FOR_DESTINATION) {
            return false
        }
        pickerLaunched = true
        return true
    }

    fun cancel() {
        if (state == ExtensionExportState.WAITING_FOR_DESTINATION) {
            state = ExtensionExportState.CANCELLED
        }
    }

    fun export(
        extensionManager: ExtensionManager,
        extension: Extension,
        destination: android.net.Uri,
    ) {
        if (state != ExtensionExportState.WAITING_FOR_DESTINATION) return
        state = ExtensionExportState.WRITING
        viewModelScope.launch {
            withContext(NonCancellable) {
                state = try {
                    extensionManager.export(extension, destination)
                    ExtensionExportState.SUCCEEDED
                } catch (_: Exception) {
                    ExtensionExportState.FAILED
                }
            }
        }
    }
}

@Composable
fun ExtensionExportScreen(id: String, routeEntry: NavBackStackEntry) {
    val context = LocalContext.current
    val extensionManager by context.extensionManager()

    val ext = extensionManager.getExtensionById(id)
    if (ext != null) {
        ExportScreen(ext, routeEntry)
    } else {
        ExtensionNotFoundScreen(id)
    }
}

@Composable
private fun ExportScreen(
    ext: Extension,
    routeEntry: NavBackStackEntry,
) = FlorisScreen {
    title = ext.meta.title
    scrollable = false

    val navController = LocalNavController.current
    val context = LocalContext.current
    val extensionManager by context.extensionManager()
    val genericErrorMessage = stringRes(R.string.error__snackbar_message)
    val model = remember(routeEntry) {
        ViewModelProvider(routeEntry)[ExtensionExportViewModel::class.java]
    }
    val state = model.state
    navigationIconVisible = state != ExtensionExportState.WRITING

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri ->
            if (uri == null) {
                model.cancel()
            } else {
                model.export(extensionManager, ext, uri)
            }
        },
    )

    LaunchedEffect(state, routeEntry) {
        when (state) {
            ExtensionExportState.SUCCEEDED -> {
                if (
                    navController.popOwnedRouteWhenResumed(routeEntry) ==
                    OwnedRoutePopResult.POPPED
                ) {
                    context.showLongToast(R.string.ext__export__success)
                }
            }

            ExtensionExportState.FAILED -> {
                if (
                    navController.popOwnedRouteWhenResumed(routeEntry) ==
                    OwnedRoutePopResult.POPPED
                ) {
                    context.showLongToast(
                        R.string.ext__export__failure,
                        "error_message" to genericErrorMessage,
                    )
                }
            }

            ExtensionExportState.CANCELLED -> {
                navController.popOwnedRouteWhenResumed(routeEntry)
            }

            ExtensionExportState.WAITING_FOR_DESTINATION,
            ExtensionExportState.WRITING,
            -> Unit
        }
    }

    LaunchedEffect(ext.meta.id, model) {
        if (model.claimPickerLaunch()) {
            exportLauncher.launch(ExtensionDefaults.createFlexName(ext.meta.id))
        }
    }

    content {
        BackHandler(enabled = state == ExtensionExportState.WRITING) { }
        if (state == ExtensionExportState.WRITING) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
