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

package dev.patrickgold.florisboard.ime.theme

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.smartbar.CachedInlineSuggestionsChipStyleSet
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.io.BoundedExtensionArchive
import dev.patrickgold.florisboard.lib.io.ZipUtils
import dev.patrickgold.florisboard.lib.util.TimeUtils.javaLocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.florisboard.lib.android.conservativeUsableSpace
import org.florisboard.lib.kotlin.collectIn
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.snygg.SnyggStylesheet
import org.florisboard.lib.snygg.value.SnyggStaticColorValue
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Core class which manages the keyboard theme. Note, that this does not affect the UI theme of the
 * Settings Activities.
 */
class ThemeManager(context: Context) {
    private val prefs by FlorisPreferenceStore
    private val appContext by context.appContext()
    private val extensionManager by context.extensionManager()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _indexedThemeConfigs = MutableStateFlow(mapOf<ExtensionComponentName, ThemeExtensionComponent>() to 0)
    val indexedThemeConfigs get() = _indexedThemeConfigs.asStateFlow()
    private val indexedThemeConfigVersion = AtomicInteger(0)

    val previewThemeId = MutableStateFlow<ExtensionComponentName?>(null)
    val previewThemeInfo = MutableStateFlow<ThemeInfo?>(null)
    val configurationChangeCounter = MutableStateFlow(0)

    private val cachedThemeInfos = mutableListOf<ThemeInfo>()
    private val materializationRoot = appContext.cacheDir.subDir("theme-materializations")
    private var materializationRootPrepared = false
    private var activeMaterializationLease: ThemeMaterialization.Lease? = null
    private val activeThemeGuard = Mutex(locked = false)
    private val _activeThemeInfo = MutableStateFlow(ThemeInfo.DEFAULT)
    val activeThemeInfo get() = _activeThemeInfo.asStateFlow()

    init {
        extensionManager.themes.collectIn(scope) { themeExtensions ->
            val version = indexedThemeConfigVersion.incrementAndGet()
            _indexedThemeConfigs.value = buildMap {
                for (themeExtension in themeExtensions) {
                    for (themeComponent in themeExtension.themes) {
                        put(ExtensionComponentName(themeExtension.meta.id, themeComponent.id), themeComponent)
                    }
                }
            } to version
        }
        indexedThemeConfigs.collectIn(scope) {
            updateActiveTheme { clearCachedThemes() }
        }
        combine(
            prefs.theme.mode.asFlow(),
            prefs.theme.dayThemeId.asFlow(),
            prefs.theme.nightThemeId.asFlow(),
            previewThemeId,
            previewThemeInfo,
            configurationChangeCounter,
        ) {}.collectIn(scope) {
            updateActiveTheme()
        }
    }

    /**
     * Updates the current theme ref and loads the corresponding theme, as well as notifies all
     * callback receivers about the new theme.
     */
    suspend fun updateActiveTheme(action: () -> Unit = { }) = activeThemeGuard.withLock {
        action()
        previewThemeInfo.value?.let { previewThemeInfo ->
            publishTheme(previewThemeInfo)
            return@withLock
        }
        val activeName = evaluateActiveThemeName()
        val cachedInfo = cachedThemeInfos.find { it.name == activeName }
        if (cachedInfo != null) {
            cachedThemeInfos.remove(cachedInfo)
            cachedThemeInfos.add(cachedInfo)
            publishTheme(cachedInfo)
            return@withLock
        }
        val themeExt = extensionManager.getExtensionById(activeName.extensionId) as? ThemeExtension
        val themeExtRef = themeExt?.sourceRef
        if (themeExtRef == null) {
            publishTheme(ThemeInfo.DEFAULT)
            return@withLock
        }
        val themeConfig = themeExt.themes.find { it.id == activeName.componentId }
        if (themeConfig == null) {
            publishTheme(ThemeInfo.DEFAULT)
            return@withLock
        }
        val pendingMaterialization = AtomicReference<ThemeMaterialization?>()
        val loaded = try {
            val assets = runInterruptible(Dispatchers.IO) {
                prepareMaterializationRoot()
                check(
                    materializationRoot.conservativeUsableSpace() >=
                        BoundedExtensionArchive.DefaultLimits.maxExpandedBytes + MinFreeSpaceBytes,
                ) {
                    "Not enough space to load theme assets."
                }
                val loadedDir = materializationRoot.subDir(UUID.randomUUID().toString())
                try {
                    val stylesheetJson = ZipUtils.readFileFromArchive(
                        appContext,
                        themeExtRef,
                        themeConfig.stylesheetPath(),
                    ).getOrThrow()
                    val stylesheet = SnyggStylesheet.fromJson(stylesheetJson).getOrThrow()
                    ZipUtils.unzip(appContext, themeExtRef, loadedDir).getOrThrow()
                    val materialization = ThemeMaterialization(loadedDir, ::scheduleMaterializationDelete)
                    pendingMaterialization.set(materialization)
                    Triple(stylesheet, loadedDir, materialization)
                } catch (error: Throwable) {
                    loadedDir.deleteRecursively()
                    throw error
                }
            }
            currentCoroutineContext().ensureActive()
            check(pendingMaterialization.compareAndSet(assets.third, null)) {
                "Theme asset ownership transfer failed."
            }
            Result.success(assets)
        } catch (error: CancellationException) {
            pendingMaterialization.getAndSet(null)?.retire()
            throw error
        } catch (error: InterruptedException) {
            pendingMaterialization.getAndSet(null)?.retire()
            throw error
        } catch (error: Exception) {
            pendingMaterialization.getAndSet(null)?.retire()
            Result.failure(error)
        }
        loaded.fold(
            onSuccess = { (newStylesheet, loadedDir, materialization) ->
                flogInfo { "Theme extension loaded" }
                val newInfo = ThemeInfo(
                    activeName,
                    themeConfig,
                    newStylesheet,
                    loadedDir,
                    null,
                    materialization,
                )
                cacheTheme(newInfo)
                publishTheme(newInfo)
            },
            onFailure = { cause ->
                publishTheme(
                    ThemeInfo.DEFAULT.copy(
                        loadFailure = LoadFailure(cause),
                    ),
                )
            },
        )
    }

    private fun prepareMaterializationRoot() {
        if (materializationRootPrepared) return
        check(!materializationRoot.exists() || materializationRoot.deleteRecursively()) {
            "Unable to clean stale theme assets."
        }
        check(materializationRoot.mkdirs()) { "Unable to create theme asset cache." }
        materializationRootPrepared = true
    }

    private fun cacheTheme(info: ThemeInfo) {
        while (cachedThemeInfos.size >= MaxCachedThemes) {
            cachedThemeInfos.removeAt(0).materialization?.retire()
        }
        cachedThemeInfos.add(info)
    }

    private fun clearCachedThemes() {
        cachedThemeInfos.forEach { it.materialization?.retire() }
        cachedThemeInfos.clear()
    }

    private fun publishTheme(info: ThemeInfo) {
        val nextLease = info.materialization?.acquire()
        val previousLease = activeMaterializationLease
        activeMaterializationLease = nextLease
        _activeThemeInfo.value = info
        previousLease?.close()
    }

    private fun scheduleMaterializationDelete(directory: FsDir) {
        scope.launch(Dispatchers.IO) {
            repeat(2) {
                if (!directory.exists()) return@launch
                directory.deleteRecursively()
            }
        }
    }

    private fun evaluateActiveThemeName(): ExtensionComponentName {
        previewThemeId.value?.let { return it }
        return when (prefs.theme.mode.get()) {
            ThemeMode.ALWAYS_DAY -> {
                prefs.theme.dayThemeId.get()
            }
            ThemeMode.ALWAYS_NIGHT -> {
                prefs.theme.nightThemeId.get()
            }
            ThemeMode.FOLLOW_SYSTEM -> if (appContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ) {
                prefs.theme.nightThemeId.get()
            } else {
                prefs.theme.dayThemeId.get()
            }
            ThemeMode.FOLLOW_TIME -> {
                val current = LocalTime.now()
                val sunrise = prefs.theme.sunriseTime.get().javaLocalTime
                val sunset = prefs.theme.sunsetTime.get().javaLocalTime
                if (current in sunrise..sunset) {
                    prefs.theme.dayThemeId.get()
                } else {
                    prefs.theme.nightThemeId.get()
                }
            }
        }
    }

    /**
     * Creates a new inline suggestion UI bundle.
     *
     * @param context The context of the parent view/controller.
     *
     * @return A bundle containing all necessary attributes for the inline suggestion views to properly display.
     */
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    fun createInlineSuggestionUiStyleBundle(context: Context): Bundle? {
        val styleSet = CachedInlineSuggestionsChipStyleSet ?: return null
        val bgColor = styleSet.background(default = Color.White)
        val fgColor = styleSet.foreground(default = Color.Black)
        val bgHorizontalPadding = context.resources
            .getDimension(R.dimen.suggestion_chip_bg_padding_horizontal)
            .toInt()
        val fgHorizontalMargin = context.resources
            .getDimension(R.dimen.suggestion_chip_fg_margin_horizontal)
            .toInt()

        val bgDrawableId = R.drawable.inline_autofill_chip_bg
        val bgDrawable = Icon.createWithResource(context, bgDrawableId).apply {
            setTint(bgColor.toArgb())
        }
        val chipStyle = ViewStyle.Builder().run {
            setBackground(bgDrawable)
            setPadding(
                bgHorizontalPadding,
                0,
                bgHorizontalPadding,
                0,
            )
            build()
        }
        val iconStyle = ImageViewStyle.Builder().run {
            setLayoutMargin(0, 0, 0, 0)
            build()
        }
        val titleStyle = TextViewStyle.Builder().run {
            setLayoutMargin(
                fgHorizontalMargin,
                0,
                fgHorizontalMargin,
                0,
            )
            setTextColor(fgColor.toArgb())
            setTextSize(16f)
            build()
        }
        val subtitleStyle = TextViewStyle.Builder().run {
            setLayoutMargin(
                fgHorizontalMargin,
                0,
                fgHorizontalMargin,
                0,
            )
            setTextColor(ColorUtils.setAlphaComponent(fgColor.toArgb(), 150))
            setTextSize(14f)
            build()
        }
        val suggestionStyle = InlineSuggestionUi.newStyleBuilder().run {
            setSingleIconChipStyle(chipStyle)
            setChipStyle(chipStyle)
            setStartIconStyle(iconStyle)
            setEndIconStyle(iconStyle)
            setTitleStyle(titleStyle)
            setSubtitleStyle(subtitleStyle)
            build()
        }
        return UiVersions.newStylesBuilder().run {
            addStyle(suggestionStyle)
            build()
        }
    }

    data class ThemeInfo(
        val name: ExtensionComponentName,
        val config: ThemeExtensionComponent,
        val stylesheet: SnyggStylesheet,
        val loadedDir: FsDir?,
        val loadFailure: LoadFailure?,
        val materialization: ThemeMaterialization? = null,
    ) {
        override fun toString(): String {
            return "ThemeInfo(hasAssets=${loadedDir != null}, failed=${loadFailure != null})"
        }

        companion object {
            val DEFAULT = ThemeInfo(
                name = extCoreTheme("base"),
                config = ThemeExtensionComponentImpl(id = "base", label = "Base", authors = listOf()),
                stylesheet = FlorisImeThemeBaseStyle,
                loadedDir = null,
                loadFailure = null,
            )
        }
    }

    class LoadFailure(val cause: Throwable) {
        override fun toString() = "LoadFailure(type=${cause.javaClass.simpleName})"
    }

    data class RemoteColors(
        val packageName: String,
        val colorPrimary: SnyggStaticColorValue?,
        val colorPrimaryVariant: SnyggStaticColorValue?,
        val colorSecondary: SnyggStaticColorValue?,
    ) {
        companion object {
            val DEFAULT = RemoteColors("undefined", null, null, null)
        }
    }

    private companion object {
        const val MaxCachedThemes = 2
        const val MinFreeSpaceBytes = 128L * 1_024 * 1_024
    }
}
