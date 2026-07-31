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

package dev.patrickgold.florisboard.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import dev.patrickgold.florisboard.PreferenceStoreInitializationState
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import dev.patrickgold.florisboard.app.ext.ExtensionImportScreenType
import dev.patrickgold.florisboard.app.setup.NotificationPermissionState
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.cacheManager
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.compose.LocalPreviewFieldController
import dev.patrickgold.florisboard.lib.compose.PreviewKeyboardField
import dev.patrickgold.florisboard.lib.compose.rememberPreviewFieldController
import dev.patrickgold.florisboard.lib.util.AppVersionUtils
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ProvideDefaultDialogPrefStrings
import java.util.concurrent.atomic.AtomicBoolean
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.hideAppIcon
import org.florisboard.lib.android.showAppIcon
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.florisboard.lib.compose.conditional
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.collectIn

enum class AppTheme(val id: String) {
    AUTO("auto"),
    AUTO_AMOLED("auto_amoled"),
    LIGHT("light"),
    DARK("dark"),
    AMOLED_DARK("amoled_dark");
}

val LocalNavController = staticCompositionLocalOf<NavController> {
    error("LocalNavController not initialized")
}

class FlorisAppActivity : ComponentActivity() {
    private val prefs by FlorisPreferenceStore
    private val appContext by appContext()
    private val cacheManager by cacheManager()
    private var appTheme by mutableStateOf(AppTheme.AUTO)
    private var showAppIcon: Boolean? = null
    private var resourcesContext by mutableStateOf(this as Context)
    private var intentToBeHandled by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen should be installed before calling super.onCreate()
        installSplashScreen().apply {
            setKeepOnScreenCondition {
                appContext.applicationBootstrapState.value.keepsSplashVisible
            }
        }
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Defer content until application bootstrap reaches a terminal state.
        val hasRenderedTerminalState = AtomicBoolean(false)
        appContext.applicationBootstrapState.collectIn(lifecycleScope) { state ->
            if (state.keepsSplashVisible || hasRenderedTerminalState.getAndSet(true)) return@collectIn
            if (!state.canRenderPreferenceBackedUi) {
                val preferenceStoreFailed =
                    appContext.preferenceStoreInitializationState.value ==
                        PreferenceStoreInitializationState.FAILED
                setContent {
                    ApplicationBootstrapFailure(preferenceStoreFailed)
                }
                return@collectIn
            }
            observePreferences()
            // Check if android 13+ is running and the NotificationPermission is not set
            if (AndroidVersion.ATLEAST_API33_T &&
                prefs.internal.notificationPermissionState.get() == NotificationPermissionState.NOT_SET
            ) {
                // update pref value to show the setup screen again
                prefs.internal.isImeSetUp.set(false)
            }
            AppVersionUtils.updateVersionOnInstallAndLastUse(this, prefs)
            setContent {
                ProvideLocalizedResources(
                    resourcesContext,
                    appName = R.string.app_name,
                ) {
                    FlorisAppTheme(theme = appTheme) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            AppContent()
                        }
                    }
                }
            }
            onNewIntent(intent)
        }
    }

    private fun observePreferences() {
        prefs.other.settingsTheme.asFlow().collectIn(lifecycleScope) {
            appTheme = it
        }
        prefs.other.settingsLanguage.asFlow().collectIn(lifecycleScope) {
            val config = Configuration(resources.configuration)
            val locale = if (it == "auto") FlorisLocale.default() else FlorisLocale.fromTag(it)
            config.setLocale(locale.base)
            resourcesContext = createConfigurationContext(config)
        }
        if (AndroidVersion.ATMOST_API28_P) {
            prefs.other.showAppIcon.asFlow().collectIn(lifecycleScope) {
                showAppIcon = it
            }
        }
    }

    @Composable
    private fun ApplicationBootstrapFailure(preferenceStoreFailed: Boolean) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (preferenceStoreFailed) {
                                R.string.preference_store__load_failure_title
                            } else {
                                R.string.error__title
                            },
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            if (preferenceStoreFailed) {
                                R.string.preference_store__load_failure_message
                            } else {
                                R.string.send_to_clipboard__unknown_error
                            },
                        ),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    TextButton(onClick = ::closeFailedApplicationProcess) {
                        Text(text = stringResource(R.string.action__close))
                    }
                }
            }
        }
    }

    /**
     * Bootstrap is process-scoped, so merely recreating this activity cannot
     * retry it. A later launch starts a fresh process and a fresh bootstrap.
     */
    private fun closeFailedApplicationProcess() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations &&
            appContext.applicationBootstrapState.value.isTerminalFailure
        ) {
            // Back, task dismissal, and Close must all leave a fresh process
            // for the next bootstrap attempt.
            Process.killProcess(Process.myPid())
        }
    }

    override fun onPause() {
        super.onPause()

        // App icon visibility control was restricted in Android 10.
        // See https://developer.android.com/reference/android/content/pm/LauncherApps#getActivityList(java.lang.String,%20android.os.UserHandle)
        if (AndroidVersion.ATMOST_API28_P) {
            showAppIcon?.let { shouldShowAppIcon ->
                if (shouldShowAppIcon) {
                    this.showAppIcon()
                } else {
                    this.hideAppIcon()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.action == Intent.ACTION_VIEW && intent.categories?.contains(Intent.CATEGORY_BROWSABLE) == true) {
            intentToBeHandled = intent
            return
        }
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            intentToBeHandled = intent
            return
        }
        if (intent.action == Intent.ACTION_SEND && intent.clipData != null) {
            intentToBeHandled = intent
            return
        }
        intentToBeHandled = null
    }

    @Composable
    private fun AppContent() {
        val navController = rememberNavController()
        val previewFieldController = rememberPreviewFieldController()

        val isImeSetUp by prefs.internal.isImeSetUp.collectAsState()

        CompositionLocalProvider(
            LocalNavController provides navController,
            LocalPreviewFieldController provides previewFieldController,
        ) {
            ProvideDefaultDialogPrefStrings(
                confirmLabel = stringRes(R.string.action__ok),
                dismissLabel = stringRes(R.string.action__cancel),
                neutralLabel = stringRes(R.string.action__default),
            ) {
                Column(
                    modifier = Modifier
                        //.statusBarsPadding()
                        .navigationBarsPadding()
                        .conditional(LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                            displayCutoutPadding()
                        }
                        .imePadding(),
                ) {
                    Routes.AppNavHost(
                        modifier = Modifier.weight(1.0f),
                        navController = navController,
                        startDestination = if (isImeSetUp) Routes.Settings.Home::class else Routes.Setup.Screen::class,
                    )
                    PreviewKeyboardField(previewFieldController)
                }
            }
        }

        LaunchedEffect(intentToBeHandled) {
            val intent = intentToBeHandled
            if (intent != null) {
                if (intent.action == Intent.ACTION_VIEW && intent.categories?.contains(Intent.CATEGORY_BROWSABLE) == true) {
                    navController.handleDeepLink(intent)
                } else {
                    val data = if (intent.action == Intent.ACTION_VIEW) {
                        intent.data!!
                    } else {
                        intent.clipData!!.getItemAt(0).uri
                    }
                    val workspace = runCatching { cacheManager.readFromUriIntoCache(data) }.getOrNull()
                    navController.navigate(Routes.Ext.Import(ExtensionImportScreenType.EXT_ANY, workspace?.uuid))
                }
            }
            intentToBeHandled = null
        }
    }
}
