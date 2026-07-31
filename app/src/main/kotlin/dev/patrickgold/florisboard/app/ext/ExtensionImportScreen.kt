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

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.OwnedRoutePopResult
import dev.patrickgold.florisboard.app.popOwnedRoute
import dev.patrickgold.florisboard.app.popOwnedRouteWhenResumed
import dev.patrickgold.florisboard.cacheManager
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.keyboard.KeyboardExtension
import dev.patrickgold.florisboard.ime.nlp.LanguagePackExtension
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.io.FileRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.FlorisBulletSpacer
import org.florisboard.lib.compose.FlorisButtonBar
import org.florisboard.lib.compose.FlorisOutlinedBox
import org.florisboard.lib.compose.FlorisOutlinedButton
import org.florisboard.lib.compose.defaultFlorisOutlinedBox
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.kotlin.resultOk

enum class ExtensionImportScreenType(
    val id: String,
    @param:StringRes val titleResId: Int,
    val supportedFiles: List<FileRegistry.Entry>,
) {
    EXT_ANY(
        id = "ext-any",
        titleResId = R.string.ext__import__ext_any,
        supportedFiles = listOf(FileRegistry.FlexExtension),
    ),
    EXT_KEYBOARD(
        id = "ext-keyboard",
        titleResId = R.string.ext__import__ext_keyboard,
        supportedFiles = listOf(FileRegistry.FlexExtension),
    ),
    EXT_THEME(
        id = "ext-theme",
        titleResId = R.string.ext__import__ext_theme,
        supportedFiles = listOf(FileRegistry.FlexExtension),
    ),
    EXT_LANGUAGEPACK(
        id = "ext-languagepack",
        titleResId = R.string.ext__import__ext_languagepack,
        supportedFiles = listOf(FileRegistry.FlexExtension),
    );
}

@Composable
fun ExtensionImportScreen(
    type: ExtensionImportScreenType,
    initUuid: String?,
    routeEntry: NavBackStackEntry,
) = FlorisScreen {
    title = stringRes(type.titleResId)

    val navController = LocalNavController.current
    val context = LocalContext.current
    val cacheManager by context.cacheManager()
    val extensionManager by context.extensionManager()
    val genericErrorMessage = stringRes(R.string.error__snackbar_message)

    fun supportsExtension(ext: Extension): Boolean = when (type) {
        ExtensionImportScreenType.EXT_ANY -> true
        ExtensionImportScreenType.EXT_KEYBOARD -> ext is KeyboardExtension
        ExtensionImportScreenType.EXT_THEME -> ext is ThemeExtension
        ExtensionImportScreenType.EXT_LANGUAGEPACK -> ext is LanguagePackExtension
    }

    fun getSkipReason(fileInfo: CacheManager.FileInfo): Int? {
        if (!FileRegistry.matchesFileFilter(fileInfo, type.supportedFiles)) {
            return R.string.ext__import__file_skip_unsupported
        }
        val ext = fileInfo.ext ?: return R.string.ext__import__file_skip_ext_corrupted
        val installed = extensionManager.getExtensionById(ext.meta.id)
        return when {
            installed?.sourceRef?.isAssets == true -> R.string.ext__import__file_skip_ext_core
            installed != null && installed.serialType() != ext.serialType() -> {
                R.string.ext__import__file_skip_unsupported
            }
            !supportsExtension(ext) -> R.string.ext__import__file_skip_unsupported
            else -> null
        }
    }

    fun Result<CacheManager.ImporterWorkspace>.mapSkipReasons(): Result<CacheManager.ImporterWorkspace> {
        val workspace = getOrNull() ?: return this
        return runCatching {
            val acceptedIds = mutableSetOf<String>()
            workspace.inputFileInfos.forEach { fileInfo ->
                val skipReason = getSkipReason(fileInfo)
                fileInfo.skipReason = if (
                    skipReason == null &&
                    fileInfo.ext?.meta?.id?.let(acceptedIds::add) == false
                ) {
                    R.string.ext__import__file_skip_unsupported
                } else {
                    skipReason
                }
            }
            workspace
        }.onFailure {
            workspace.close()
        }
    }

    var importResult by remember(initUuid) {
        val workspace = initUuid?.let { cacheManager.importer.getWorkspaceByUuid(it) }
            ?.let { resultOk(it) }
            ?.mapSkipReasons()
        mutableStateOf(workspace)
    }
    var isReadingUris by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    val importScope = rememberCoroutineScope()
    val isBusy = isReadingUris || isImporting
    navigationIconVisible = !isBusy

    DisposableEffect(importResult?.getOrNull()) {
        val workspace = importResult?.getOrNull()
        onDispose {
            workspace?.close()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
        onResult = { uriList ->
            // If uri is null it indicates that the selection activity
            //  was cancelled (mostly by pressing the back button), so
            //  we don't display an error message here.
            if (uriList.isEmpty() || isReadingUris) return@rememberLauncherForActivityResult
            isReadingUris = true
            importResult?.getOrNull()?.close()
            importResult = null
            importScope.launch {
                try {
                    importResult = runCatching {
                        cacheManager.readFromUriIntoCache(uriList)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                    }.mapSkipReasons()
                } finally {
                    isReadingUris = false
                }
            }
        },
    )

    bottomBar {
        FlorisButtonBar {
            ButtonBarSpacer()
            ButtonBarTextButton(
                text = stringRes(R.string.action__cancel),
                enabled = !isBusy,
            ) {
                if (navController.popOwnedRoute(routeEntry) == OwnedRoutePopResult.POPPED) {
                    importResult?.getOrNull()?.close()
                }
            }
            val enabled = remember(importResult) {
                importResult?.getOrNull()?.takeIf { workspace ->
                    workspace.inputFileInfos.any { it.skipReason == null }
                } != null
            }
            ButtonBarButton(
                text = stringRes(R.string.action__import),
                enabled = enabled && !isImporting,
            ) {
                val workspace = importResult!!.getOrThrow()
                isImporting = true
                importScope.launch {
                    try {
                        runCatching {
                            for (fileInfo in workspace.inputFileInfos) {
                                if (fileInfo.skipReason == null) {
                                    fileInfo.ext?.let { extensionManager.import(it) }
                                }
                            }
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                        }.onSuccess {
                            context.showLongToast(R.string.ext__import__success)
                            if (
                                navController.popOwnedRouteWhenResumed(routeEntry) ==
                                OwnedRoutePopResult.POPPED
                            ) {
                                workspace.close()
                            }
                        }.onFailure {
                            context.showLongToast(
                                R.string.ext__import__failure,
                                "error_message" to genericErrorMessage,
                            )
                        }
                    } finally {
                        isImporting = false
                    }
                }
            }
        }
    }

    content {
        BackHandler(enabled = isBusy) { }
        if (initUuid == null) {
            FlorisOutlinedButton(
                onClick = {
                    importLauncher.launch("*/*")
                },
                enabled = !isBusy,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .align(Alignment.CenterHorizontally),
                text = stringRes(R.string.action__select_files),
            )
        }

        val result = importResult
        when {
            result == null -> {
                Text(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 16.dp),
                    text = stringRes(R.string.state__no_files_selected),
                    fontStyle = FontStyle.Italic,
                )
            }
            result.isSuccess -> {
                val workspace = result.getOrThrow()
                for (fileInfo in workspace.inputFileInfos) {
                    FileInfoView(fileInfo)
                }
            }
            result.isFailure -> {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = stringRes(R.string.ext__import__error_unexpected_exception),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FileInfoView(
    fileInfo: CacheManager.FileInfo,
) {
    FlorisOutlinedBox(
        modifier = Modifier.defaultFlorisOutlinedBox(),
        title = fileInfo.displayLabel,
        subtitle = fileInfo.mediaType ?: "application/unknown",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val grayColor = LocalContentColor.current.copy(alpha = 0.56f)
            val ext = fileInfo.ext
            Row {
                Text(
                    text = Formatter.formatShortFileSize(LocalContext.current, fileInfo.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = grayColor,
                )
                if (ext != null) {
                    FlorisBulletSpacer()
                    Text(
                        text = ext.meta.id,
                        style = MaterialTheme.typography.bodyMedium,
                        color = grayColor,
                    )
                    FlorisBulletSpacer()
                    Text(
                        text = ext.meta.version,
                        style = MaterialTheme.typography.bodyMedium,
                        color = grayColor,
                    )
                }
            }
            if (ext != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = ext.meta.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ext.meta.description?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val maintainers = remember(ext) {
                    ext.meta.maintainers.joinToString { it.name }
                }
                Text(
                    text = stringRes(R.string.ext__meta__maintainers_by, "maintainers" to maintainers),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                for (component in ext.components()) {
                    Text(
                        text = component.id,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            fileInfo.skipReason?.let { skipReason ->
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(19.dp)
                    .padding(top = 10.dp, bottom = 8.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.56f)))
                Text(
                    text = stringRes(R.string.ext__import__file_skip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringRes(skipReason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}
