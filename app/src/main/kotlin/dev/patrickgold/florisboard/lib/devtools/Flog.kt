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

package dev.patrickgold.florisboard.lib.devtools

import android.util.Log
import dev.patrickgold.florisboard.lib.devtools.Flog.createTag
import dev.patrickgold.florisboard.lib.devtools.Flog.getStacktraceElement
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/** Bit mask selecting log topics. */
typealias FlogTopic = UInt

/** Bit mask selecting log levels. */
typealias FlogLevel = UInt

/**
 * Logs [block] at error level when its [topic] is enabled. The block runs at most once
 * and only when the message will be written.
 */
inline fun flogError(topic: FlogTopic = Flog.TOPIC_OTHER, block: () -> String = { "" }) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (Flog.checkShouldFlog(topic, Flog.LEVEL_ERROR)) {
        // quality: allow-sensitive-log -- central wrapper; public flog call sites are source-checked
        Flog.log(Flog.LEVEL_ERROR, block())
    }
}

/**
 * Logs [block] at warning level when its [topic] is enabled. The block runs at most
 * once and only when the message will be written.
 */
inline fun flogWarning(topic: FlogTopic = Flog.TOPIC_OTHER, block: () -> String = { "" }) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (Flog.checkShouldFlog(topic, Flog.LEVEL_WARNING)) {
        // quality: allow-sensitive-log -- central wrapper; public flog call sites are source-checked
        Flog.log(Flog.LEVEL_WARNING, block())
    }
}

/**
 * Logs [block] at info level when its [topic] is enabled. The block runs at most once
 * and only when the message will be written.
 */
inline fun flogInfo(topic: FlogTopic = Flog.TOPIC_OTHER, block: () -> String = { "" }) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (Flog.checkShouldFlog(topic, Flog.LEVEL_INFO)) {
        // quality: allow-sensitive-log -- central wrapper; public flog call sites are source-checked
        Flog.log(Flog.LEVEL_INFO, block())
    }
}

/**
 * Logs [block] at debug level when its [topic] is enabled. The block runs at most once
 * and only when the message will be written.
 */
inline fun flogDebug(topic: FlogTopic = Flog.TOPIC_OTHER, block: () -> String = { "" }) {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (Flog.checkShouldFlog(topic, Flog.LEVEL_DEBUG)) {
        // quality: allow-sensitive-log -- central wrapper; public flog call sites are source-checked
        Flog.log(Flog.LEVEL_DEBUG, block())
    }
}

private infix fun UInt.isSet(flag: UInt) = (this and flag) == flag

/**
 * Main helper object for FlorisBoard logging (=Flog). Manages the enabled
 * state and the active topics. Provides relevant helper functions for the
 * flog methods to properly work.
 *
 * This helper object uses some parts of the Timber library to assist in
 * logging. In particular:
 *  - [createTag] (converted to Kotlin, renamed from "createStackElementTag",
 *     removed manual tagging).
 *  - [getStacktraceElement] (converted to Kotlin, renamed from "getTag",
 *     method now returns stack trace element).
 *  - [log] (converted to Kotlin).
 * Timber is licensed under the Apache 2.0 license, see the repo here:
 *  https://github.com/JakeWharton/timber
 */
@Suppress("MemberVisibilityCanBePrivate")
object Flog {
    const val TOPIC_NONE: FlogTopic =               UInt.MIN_VALUE
    const val TOPIC_OTHER: FlogTopic =              0x80000000u
    const val TOPIC_ALL: FlogTopic =                UInt.MAX_VALUE

    const val LEVEL_NONE: FlogLevel =               UInt.MIN_VALUE
    const val LEVEL_ERROR: FlogLevel =              0x01u
    const val LEVEL_WARNING: FlogLevel =            0x02u
    const val LEVEL_INFO: FlogLevel =               0x04u
    const val LEVEL_DEBUG: FlogLevel =              0x08u
    const val LEVEL_ALL: FlogLevel =                UInt.MAX_VALUE

    /** The maximum log length limit. */
    private const val MAX_LOG_LENGTH: Int =         4000
    private const val MAX_DIAGNOSTIC_LINES: Int =   200
    private const val MAX_DIAGNOSTIC_LINE_LENGTH =  512

    private var isLogcatEnabled: Boolean = false
    private var isDiagnosticCaptureEnabled: Boolean = false
    private var flogTopics: FlogTopic = TOPIC_NONE
    private var flogLevels: FlogLevel = LEVEL_NONE
    private val diagnosticsLock = Any()
    private val diagnosticLines = ArrayDeque<String>(MAX_DIAGNOSTIC_LINES)

    /**
     * Installs the flog utility and sets the relevant configuration variables.
     *
     * @param isLogcatEnabled Whether messages are written to Android's Logcat.
     * @param isDiagnosticCaptureEnabled Whether messages are retained in the bounded,
     *  process-local diagnostic snapshot.
     * @param flogTopics The enabled topics for this installation. Use [TOPIC_ALL] to enable
     *  all topics. If this value is [TOPIC_NONE], this essentially disables all logging.
     * @param flogLevels The enabled levels for this installation. Use [LEVEL_ALL] to enable
     *  all levels. If this value is [LEVEL_NONE], this essentially disables all logging.
     */
    fun install(
        isLogcatEnabled: Boolean,
        isDiagnosticCaptureEnabled: Boolean,
        flogTopics: FlogTopic,
        flogLevels: FlogLevel,
    ) {
        this.isLogcatEnabled = isLogcatEnabled
        this.isDiagnosticCaptureEnabled = isDiagnosticCaptureEnabled
        this.flogTopics = flogTopics
        this.flogLevels = flogLevels
        synchronized(diagnosticsLock) {
            diagnosticLines.clear()
        }
    }

    /**
     * Checks if a message should be evaluated for either configured output, then matches
     * the given [topic] and [level] with the configured settings.
     *
     * @param topic The topic(s) to check for.
     * @param level The level(s) to check for.
     *
     * @return True if a log message should be evaluated, false otherwise.
     */
    fun checkShouldFlog(topic: FlogTopic, level: FlogLevel): Boolean {
        return (isLogcatEnabled || isDiagnosticCaptureEnabled) &&
            (flogTopics isSet topic) &&
            (flogLevels isSet level)
    }

    /**
     * Extract the tag which should be used for the message from the `element`.
     */
    private fun createTag(element: StackTraceElement): String {
        var tag = element.className
        tag = tag.substring(tag.lastIndexOf('.') + 1)
        return tag
    }

    private fun createMessage(element: StackTraceElement, msg: String): String {
        return StringBuilder().run {
            append(element.methodName)
            append('(')
            append(')')
            if (msg.isNotBlank()) {
                append(' ')
                append('-')
                append(' ')
                append(msg)
            }
            toString()
        }
    }

    private fun getStacktraceElement(): StackTraceElement {
        val stackTrace = Throwable().stackTrace
        val loggerClassName = Flog::class.java.name
        return stackTrace.firstOrNull { element ->
            element.className != loggerClassName && element.className != "${loggerClassName}Kt"
        } ?: StackTraceElement(loggerClassName, "unknown", "Flog.kt", -1)
    }

    @PublishedApi
    internal fun log(level: FlogLevel, msg: String) {
        if (msg.length < MAX_LOG_LENGTH) {
            writeLine(level, msg)
        } else {
            // Split by line, then ensure each line can fit into Log's maximum length.
            var i = 0
            val length: Int = msg.length
            while (i < length) {
                var newline: Int = msg.indexOf('\n', i)
                newline = if (newline != -1) newline else length
                do {
                    val end = newline.coerceAtMost(i + MAX_LOG_LENGTH)
                    val part: String = msg.substring(i, end)
                    writeLine(level, part)
                    i = end
                } while (i < newline)
                i++
            }
        }
    }

    internal fun diagnosticSnapshot(): List<String> = synchronized(diagnosticsLock) {
        diagnosticLines.toList()
    }

    private fun sanitizeDiagnosticLine(line: String): String {
        return buildString(line.length.coerceAtMost(MAX_DIAGNOSTIC_LINE_LENGTH)) {
            for (char in line) {
                if (length == MAX_DIAGNOSTIC_LINE_LENGTH) break
                append(if (char.isISOControl()) ' ' else char)
            }
        }
    }

    private fun writeLine(level: FlogLevel, msg: String) {
        val ste = getStacktraceElement()
        val tag = createTag(ste)
        val message = createMessage(ste, msg)
        val levelName = when {
            level isSet LEVEL_ERROR -> "E"
            level isSet LEVEL_WARNING -> "W"
            level isSet LEVEL_INFO -> "I"
            level isSet LEVEL_DEBUG -> "D"
            else -> "?"
        }
        if (isDiagnosticCaptureEnabled) {
            synchronized(diagnosticsLock) {
                if (diagnosticLines.size == MAX_DIAGNOSTIC_LINES) {
                    diagnosticLines.removeFirst()
                }
                diagnosticLines.addLast(sanitizeDiagnosticLine("$levelName/$tag: $message"))
            }
        }
        if (!isLogcatEnabled) return
        when {
            // quality: allow-sensitive-log -- central sink; callers are checked before this forwarding step
            level isSet LEVEL_ERROR ->      Log.e(tag, message)
            // quality: allow-sensitive-log -- central sink; callers are checked before this forwarding step
            level isSet LEVEL_WARNING ->    Log.w(tag, message)
            // quality: allow-sensitive-log -- central sink; callers are checked before this forwarding step
            level isSet LEVEL_INFO ->       Log.i(tag, message)
            // quality: allow-sensitive-log -- central sink; callers are checked before this forwarding step
            level isSet LEVEL_DEBUG ->      Log.d(tag, message)
        }
    }
}
