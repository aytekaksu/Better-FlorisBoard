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

package dev.patrickgold.florisboard.lib.crashutility

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import java.lang.ref.WeakReference
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.FsFile
import org.florisboard.lib.kotlin.io.subDir
import org.florisboard.lib.kotlin.io.subFile
import kotlin.system.exitProcess

/**
 * Abstract class which holds several static methods used for handling unexpected errors.
 *
 * Parts of this class (especially the install() function and the uncaughtException() handler) have
 * been inspired by the great CustomActivityOnCrash library:
 *  https://github.com/Ereza/CustomActivityOnCrash (licensed under Apache 2.0)
 *  https://github.com/Ereza/CustomActivityOnCrash/blob/master/library/src/main/java/cat/ereza/customactivityoncrash/CustomActivityOnCrash.java
 */
abstract class CrashUtility private constructor() {
    companion object {
        private const val SHARED_PREFS_FILE = "crash_utility"
        private const val SHARED_PREFS_LAST_CRASH_TIMESTAMP = "last_crash_timestamp"

        private const val NOTIFICATION_CHANNEL_ID = "${BuildConfig.APPLICATION_ID}.lib.crashutility"
        private const val NOTIFICATION_ID = 0xFBAD0100

        private const val UNHANDLED_STACKTRACES_DIR_NAME = "unhandled_stacktraces"
        private const val UNHANDLED_STACKTRACE_FILE_EXT = "stacktrace"

        private var lastActivityCreated: WeakReference<Activity?> = WeakReference(null)

        /** Installs the crash handler and its notification channel. */
        fun install(application: Application): Boolean {
            val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (oldHandler is UncaughtExceptionHandler) {
                flogInfo(LogTopic.CRASH_UTILITY) {
                    "Crash handler is already installed, doing nothing!"
                }
                return true
            }
            try {
                Thread.setDefaultUncaughtExceptionHandler(
                    UncaughtExceptionHandler(
                        WeakReference(application),
                        application.getUstDir(),
                    )
                )
                flogInfo(LogTopic.CRASH_UTILITY) {
                    "Successfully installed crash handler for this application!"
                }
            } catch (e: SecurityException) {
                flogError(LogTopic.CRASH_UTILITY) {
                    "Failed to install crash handler: reason=security, error=${e.javaClass.simpleName}"
                }
                return false
            } catch (e: Exception) {
                flogError(LogTopic.CRASH_UTILITY) {
                    "Failed to install crash handler: reason=unexpected, error=${e.javaClass.simpleName}"
                }
                return false
            }
            application.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {
                    if (activity !is CrashDialogActivity) {
                        lastActivityCreated = WeakReference(activity)
                    }
                }
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            try {
                val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE)
                if (notificationManager is NotificationManager) {
                    val notificationChannel = NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        application.resources.getString(R.string.crash_notification_channel__title),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    notificationManager.createNotificationChannel(notificationChannel)
                }
                flogInfo(LogTopic.CRASH_UTILITY) {
                    "Successfully created crash handler notification channel!"
                }
            } catch (e: Exception) {
                flogError(LogTopic.CRASH_UTILITY) {
                    "Failed to create crash notification channel: error=${e.javaClass.simpleName}"
                }
            }
            return true
        }

        /** Reads and removes all stored unhandled crash reports. */
        fun getUnhandledStacktraces(context: Context): List<Stacktrace> {
            val retList = mutableListOf<Stacktrace>()
            val ustDir = context.getUstDir()
            if (ustDir.isDirectory) {
                (ustDir.listFiles { pathname ->
                    pathname.name.endsWith(".$UNHANDLED_STACKTRACE_FILE_EXT")
                })?.forEach { file ->
                    flogInfo(LogTopic.CRASH_UTILITY) {
                        "Reading stored unhandled crash report"
                    }
                    retList.add(Stacktrace(file.name, readFile(file)))
                    file.delete()
                }
            }
            return retList.toList()
        }

        private fun getLastCrashTimestamp(context: Context): Long {
            return context.getSharedPreferences(SHARED_PREFS_FILE, Context.MODE_PRIVATE)
                .getLong(SHARED_PREFS_LAST_CRASH_TIMESTAMP, 0)
        }

        @SuppressLint("ApplySharedPref")
        private fun setLastCrashTimestamp(context: Context, value: Long) {
            // A following crash may read this value immediately, so it must be written synchronously.
            context.getSharedPreferences(SHARED_PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .putLong(SHARED_PREFS_LAST_CRASH_TIMESTAMP, value)
                .commit()
        }

        /** Returns the directory that stores unhandled crash reports. */
        private fun Context.getUstDir(): FsDir {
            return this.noBackupFilesDir.subDir(UNHANDLED_STACKTRACES_DIR_NAME).also { it.mkdirs() }
        }

        /** Returns the crash-report file for [timestamp]. */
        private fun Context.getUstFile(timestamp: Long): FsFile {
            return this.getUstDir().subFile("$timestamp.$UNHANDLED_STACKTRACE_FILE_EXT")
        }

        /** Posts a notification that opens [CrashDialogActivity]. */
        private fun pushNotification(context: Context, id: Int, title: String, body: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            if (notificationManager is NotificationManager) {
                val notificationBuilder = Notification.Builder(context.applicationContext, NOTIFICATION_CHANNEL_ID)
                val crashDialogIntent = Intent(context, CrashDialogActivity::class.java)
                val notification = notificationBuilder.run {
                    setContentTitle(title)
                    style = Notification.BigTextStyle().bigText(body)
                    setContentText(body)
                    setSmallIcon(android.R.drawable.stat_notify_error)
                    setContentIntent(PendingIntent.getActivity(context, 0, crashDialogIntent, PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(
                        true
                    )
                    build()
                }
                notificationManager.notify(id, notification)
            }
        }

        private fun pushCrashOnceNotification(context: Context) {
            pushNotification(
                context,
                NOTIFICATION_ID.toInt(),
                context.resources.getString(R.string.crash_once_notification__title),
                context.resources.getString(R.string.crash_once_notification__body)
            )
        }

        private fun pushCrashMultipleNotification(context: Context) {
            pushNotification(
                context,
                NOTIFICATION_ID.toInt(),
                context.resources.getString(R.string.crash_multiple_notification__title),
                context.resources.getString(R.string.crash_multiple_notification__body)
            )
        }

        /** Reads [file], or returns an empty string if it does not exist. */
        private fun readFile(file: FsFile): String {
            val retText = StringBuilder()
            if (file.exists()) {
                val newLine = System.lineSeparator()
                file.forEachLine {
                    retText.append(it)
                    retText.append(newLine)
                }
            }
            return retText.toString()
        }

        /** Overwrites [file] with [text]. */
        private fun writeToFile(file: FsFile, text: String) {
            try {
                file.writeText(text)
            } catch (e: Exception) {
                flogError(LogTopic.CRASH_UTILITY) {
                    "Failed to store crash report: error=${e.javaClass.simpleName}"
                }
            }
        }
    }

    /**
     * A simple stacktrace data class capable of holding a [name] and the [details] of a stacktrace.
     */
    data class Stacktrace(
        val name: String,
        val details: String,
    )

    /**
     * Custom UncaughtExceptionHandler, which writes the captured stacktrace of the crash to the
     * internal storage, pushes a crash notification and kills the current process.
     */
    class UncaughtExceptionHandler(
        private val application: WeakReference<Application>,
        private val ustDir: FsDir,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            flogInfo(LogTopic.CRASH_UTILITY) {
                "Detected application crash, executing custom crash handler."
            }
            val timestamp = System.currentTimeMillis()
            val stacktrace = Log.getStackTraceString(throwable)
            val ustFile = ustDir.subFile("$timestamp.$UNHANDLED_STACKTRACE_FILE_EXT")
            writeToFile(ustFile, stacktrace)
            val application = application.get()
            if (application != null) {
                val lastTimestamp = getLastCrashTimestamp(application)
                if (lastTimestamp > 0) {
                    val lastFile = application.getUstFile(lastTimestamp)
                    val lastStacktrace = readFile(lastFile)
                    if (lastStacktrace == stacktrace) {
                        // Delete last stacktrace if it matches previous unhandled one
                        lastFile.delete()
                    }
                }
                setLastCrashTimestamp(application, timestamp)
                if (timestamp - lastTimestamp < 5000) {
                    pushCrashMultipleNotification(application)
                    FlorisImeService.switchToPrevInputMethod() || FlorisImeService.switchToNextInputMethod()
                } else {
                    pushCrashOnceNotification(application)
                }
            }
            val lastActivity = lastActivityCreated.get()
            if (lastActivity != null) {
                lastActivity.finish()
                lastActivityCreated.clear()
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
