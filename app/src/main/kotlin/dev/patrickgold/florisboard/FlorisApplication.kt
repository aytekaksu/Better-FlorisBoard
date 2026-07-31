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

package dev.patrickgold.florisboard

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.StrictMode
import androidx.core.os.UserManagerCompat
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.initAndroidWithLegacyMigrations
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.core.SubtypeManager
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.keyboard.KeyboardManager
import dev.patrickgold.florisboard.ime.media.emoji.FlorisEmojiCompat
import dev.patrickgold.florisboard.ime.nlp.NlpManager
import dev.patrickgold.florisboard.ime.nlp.plugin.AutocorrectPluginManager
import dev.patrickgold.florisboard.ime.text.gestures.GlideTypingManager
import dev.patrickgold.florisboard.ime.theme.ThemeManager
import dev.patrickgold.florisboard.lib.cache.CacheManager
import dev.patrickgold.florisboard.lib.crashutility.CrashUtility
import dev.patrickgold.florisboard.lib.devtools.Flog
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.lib.ext.ExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.florisboard.lib.kotlin.io.deleteContentsRecursively
import org.florisboard.lib.kotlin.tryOrNull
import java.io.FileInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global weak reference for the [FlorisApplication] class. This is needed as in certain scenarios an application
 * reference is needed, but the Android framework hasn't finished setting up
 */
private var FlorisApplicationReference = WeakReference<FlorisApplication?>(null)

private const val ASYNC_BOOTSTRAP_STAGE = 1
private const val RUNTIME_BOOTSTRAP_STAGE = 1 shl 1
private const val ALL_BOOTSTRAP_STAGES =
    ASYNC_BOOTSTRAP_STAGE or RUNTIME_BOOTSTRAP_STAGE
private const val CLIPBOARD_IMPORT_PROCESS_SUFFIX = ":clipboard_import"
private const val PROCESS_NAME_BYTE_LIMIT = 512
private const val PROC_SELF_CMDLINE = "/proc/self/cmdline"

/**
 * Only the exact private clipboard-import process skips the full app runtime.
 * Missing or unexpected process information deliberately falls through to the
 * normal bootstrap so a lookup failure cannot disable the main app.
 */
internal fun shouldInitializeFlorisApplication(
    packageName: String,
    processName: String?,
): Boolean {
    if (packageName.isEmpty()) return true
    return processName != packageName + CLIPBOARD_IMPORT_PROCESS_SUFFIX
}

internal fun currentFlorisApplicationProcessName(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return try {
            Application.getProcessName().takeIf(String::isNotEmpty)
        } catch (_: Exception) {
            null
        }
    }
    return currentProcessNameFromActivityManager(context) ?: currentProcessNameFromProc()
}

private fun currentProcessNameFromActivityManager(context: Context): String? {
    return try {
        val current = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(current)
        if (current.pid <= 0 || current.uid <= 0) {
            null
        } else {
            context.getSystemService(ActivityManager::class.java)
                ?.runningAppProcesses
                ?.firstOrNull { process ->
                    process.pid == current.pid && process.uid == current.uid
                }
                ?.processName
                ?.takeIf(String::isNotEmpty)
        }
    } catch (_: Exception) {
        null
    }
}

private fun currentProcessNameFromProc(): String? {
    return try {
        FileInputStream(PROC_SELF_CMDLINE).use { input ->
            readBoundedProcessName(input)
        }
    } catch (_: Exception) {
        null
    }
}

internal fun readBoundedProcessName(input: InputStream): String? {
    val bytes = ByteArray(PROCESS_NAME_BYTE_LIMIT + 1)
    var byteCount = 0
    var terminator = -1
    while (byteCount < bytes.size && terminator < 0) {
        val read = input.read(bytes, byteCount, bytes.size - byteCount)
        if (read <= 0) break
        val end = byteCount + read
        for (index in byteCount until end) {
            if (bytes[index] == 0.toByte()) {
                terminator = index
                break
            }
        }
        byteCount = end
    }
    if (terminator !in 1..PROCESS_NAME_BYTE_LIMIT) return null
    return String(bytes, 0, terminator, Charsets.UTF_8)
        .takeIf(String::isNotEmpty)
}

@Suppress("unused")
class FlorisApplication : Application() {
    private val mainHandler by lazy { Handler(mainLooper) }
    private val scope = CoroutineScope(Dispatchers.Default)
    private val initializationStarted = AtomicBoolean(false)
    private val completedBootstrapStages = AtomicInteger(0)
    private val clipboardInitializationFailure = TerminalFailureLatch<ClipboardManager> {
        it.failInitialization()
    }
    internal val applicationBootstrapState =
        MutableStateFlow(ApplicationBootstrapState.LOADING)
    internal val preferenceStoreInitializationState =
        MutableStateFlow(PreferenceStoreInitializationState.LOADING)

    val cacheManager = lazy { CacheManager(this) }
    val clipboardManager = lazy {
        clipboardInitializationFailure.register(ClipboardManager(this))
    }
    val dictionaryManager = lazy { DictionaryManager(this) }
    val editorInstance = lazy { EditorInstance(this) }
    val extensionManager = lazy { ExtensionManager(this) }
    val glideTypingManager = lazy { GlideTypingManager(this) }
    val keyboardManager = lazy { KeyboardManager(this) }
    val autocorrectPluginManager = lazy { AutocorrectPluginManager(this) }
    val nlpManager = lazy { NlpManager(this) }
    val subtypeManager = lazy { SubtypeManager(this) }
    val themeManager = lazy { ThemeManager(this) }

    override fun onCreate() {
        super.onCreate()
        FlorisApplicationReference = WeakReference(this)
        if (
            !shouldInitializeFlorisApplication(
                packageName = packageName,
                processName = currentFlorisApplicationProcessName(this),
            )
        ) {
            return
        }
        try {
            if (BuildConfig.DEBUG) {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .build(),
                )
                StrictMode.setVmPolicy(
                    StrictMode.VmPolicy.Builder()
                        .detectActivityLeaks()
                        .detectLeakedClosableObjects()
                        .detectLeakedRegistrationObjects()
                        .penaltyLog()
                        .build(),
                )
            }
            Flog.install(
                isLogcatEnabled = BuildConfig.DEBUG,
                isDiagnosticCaptureEnabled = true,
                flogTopics = LogTopic.ALL,
                flogLevels = if (BuildConfig.DEBUG) {
                    Flog.LEVEL_ALL
                } else {
                    Flog.LEVEL_ERROR or Flog.LEVEL_WARNING
                },
            )
            CrashUtility.install(this)
            FlorisEmojiCompat.init(this)

            if (!UserManagerCompat.isUserUnlocked(this)) {
                cacheDir?.deleteContentsRecursively()
                extensionManager.value.init()
                val unlockReceiver = BootComplete()
                registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
                // Unlock may complete between the first check and receiver
                // registration. Recheck after registration so neither path is
                // lost; the receiver's latch makes both paths idempotent.
                if (UserManagerCompat.isUserUnlocked(this)) {
                    unlockReceiver.handleUnlock()
                }
                return
            }

            init()
        } catch (error: Exception) {
            failApplicationBootstrap(error, stage = "platform")
        }
    }

    fun init() {
        if (!initializationStarted.compareAndSet(false, true)) return
        try {
            cacheDir?.deleteContentsRecursively()
            // Android 8 requires ClipboardManager to be created on a Looper thread.
            val initializedClipboardManager = clipboardManager.value
            scope.launch {
                initializePreferencesAndClipboard(initializedClipboardManager)
            }
            extensionManager.value.init()
            completeBootstrapStage(RUNTIME_BOOTSTRAP_STAGE)
        } catch (error: Exception) {
            failApplicationBootstrap(error, stage = "runtime")
        }
    }

    private suspend fun initializePreferencesAndClipboard(
        initializedClipboardManager: ClipboardManager,
    ) {
        val result = try {
            FlorisPreferenceStore.initAndroidWithLegacyMigrations(this)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
        val error = result.exceptionOrNull()
        if (error != null) {
            if (error is CancellationException) throw error
            if (error !is Exception) throw error
            preferenceStoreInitializationState.value =
                PreferenceStoreInitializationState.FAILED
            failApplicationBootstrap(error, stage = "preferences")
            return
        }

        preferenceStoreInitializationState.value =
            PreferenceStoreInitializationState.READY
        try {
            flogInfo { "Preference store initialization completed" }
        } catch (_: Exception) {
            // Diagnostics must never decide bootstrap success.
        }
        try {
            initializedClipboardManager.initializeForContext(this)
            initializedClipboardManager.awaitInitialization()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failApplicationBootstrap(error, stage = "clipboard")
            return
        }
        completeBootstrapStage(ASYNC_BOOTSTRAP_STAGE)
    }

    private fun completeBootstrapStage(stage: Int) {
        val completed = completedBootstrapStages.updateAndGet { it or stage }
        if (completed == ALL_BOOTSTRAP_STAGES) {
            applicationBootstrapState.compareAndSet(
                expect = ApplicationBootstrapState.LOADING,
                update = ApplicationBootstrapState.READY,
            )
        }
    }

    private fun failApplicationBootstrap(
        error: Exception,
        stage: String,
    ) {
        val firstFailure = applicationBootstrapState.compareAndSet(
            expect = ApplicationBootstrapState.LOADING,
            update = ApplicationBootstrapState.FAILED,
        )
        clipboardInitializationFailure.fail()
        if (!firstFailure) return
        try {
            flogError {
                "Application bootstrap failed at $stage: ${error::class.simpleName}"
            }
        } catch (_: Exception) {
            // Diagnostics must not replace the original failure state.
        }
    }

    private inner class BootComplete : BroadcastReceiver() {
        private val handled = AtomicBoolean(false)

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                handleUnlock()
            }
        }

        fun handleUnlock() {
            if (!handled.compareAndSet(false, true)) return
            try {
                unregisterReceiver(this)
            } catch (error: Exception) {
                try {
                    flogError {
                        "Failed to unregister unlock receiver: ${error::class.simpleName}"
                    }
                } catch (_: Exception) {
                    // Unlock initialization must still proceed.
                }
            }
            try {
                mainHandler.post { init() }
            } catch (error: Exception) {
                failApplicationBootstrap(error, stage = "unlock")
            }
        }
    }
}

private tailrec fun Context.florisApplication(): FlorisApplication {
    return when (this) {
        is FlorisApplication -> this
        is ContextWrapper -> when {
            this.baseContext != null -> this.baseContext.florisApplication()
            else -> FlorisApplicationReference.get()!!
        }
        else -> tryOrNull { this.applicationContext as FlorisApplication } ?: FlorisApplicationReference.get()!!
    }
}

fun Context.appContext() = lazyOf(this.florisApplication())

fun Context.cacheManager() = this.florisApplication().cacheManager

fun Context.clipboardManager() = this.florisApplication().clipboardManager

fun Context.dictionaryManager() = this.florisApplication().dictionaryManager

fun Context.editorInstance() = this.florisApplication().editorInstance

fun Context.extensionManager() = this.florisApplication().extensionManager

fun Context.glideTypingManager() = this.florisApplication().glideTypingManager

fun Context.keyboardManager() = this.florisApplication().keyboardManager

fun Context.nlpManager() = this.florisApplication().nlpManager
fun Context.autocorrectPluginManager() = this.florisApplication().autocorrectPluginManager

fun Context.subtypeManager() = this.florisApplication().subtypeManager

fun Context.themeManager() = this.florisApplication().themeManager
