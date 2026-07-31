/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.lib.io

import java.io.Closeable

private const val EXTENSION_IMPORT_MEBIBYTE = 1_024 * 1_024
private const val EXTENSION_IMPORT_FAILURE = "Extension selection exceeds workspace limits."

internal class ExtensionImportLimitException : IllegalStateException(EXTENSION_IMPORT_FAILURE)

/**
 * One workspace-wide quota shared by every selected extension.
 *
 * Closing an uncommitted admission rolls its reservations back. Completed
 * provider-copy attempts commit their work even when archive or manifest
 * validation rejects the input; cancellation and workspace-aborting failures
 * leave the admission uncommitted.
 */
internal class ExtensionImportBudget(private val limits: Limits = Limits.Default) {
    internal class Limits(
        val maxInputs: Int,
        val maxSourceBytes: Long,
        val maxExpandedBytes: Long,
        val maxEntries: Int,
    ) {
        init {
            require(maxInputs >= 0)
            require(maxSourceBytes >= 0L)
            require(maxExpandedBytes >= 0L)
            require(maxEntries >= 0)
        }

        companion object {
            val Default = Limits(
                maxInputs = 64,
                maxSourceBytes = 256L * EXTENSION_IMPORT_MEBIBYTE,
                maxExpandedBytes = 512L * EXTENSION_IMPORT_MEBIBYTE,
                maxEntries = 16_384,
            )
        }
    }

    internal class Usage(val inputs: Int, val sourceBytes: Long, val expandedBytes: Long, val entries: Int)

    private var inputs = 0
    private var sourceBytes = 0L
    private var expandedBytes = 0L
    private var entries = 0

    @Synchronized
    fun beginInput(): Admission {
        ensureWithinLimit(1, limits.maxInputs - inputs)
        inputs++
        return Admission(this)
    }

    @Synchronized
    fun usage(): Usage = Usage(inputs, sourceBytes, expandedBytes, entries)

    @Synchronized
    private fun addSourceBytes(admission: Admission, byteCount: Int) {
        admission.ensureOpen()
        ensureWithinLimit(byteCount.toLong(), limits.maxSourceBytes - sourceBytes)
        sourceBytes += byteCount
        admission.sourceBytes += byteCount
        admission.hasSourceCharge = true
    }

    @Synchronized
    private fun remainingSourceBytes(admission: Admission): Long {
        admission.ensureOpen()
        return limits.maxSourceBytes - sourceBytes
    }

    @Synchronized
    private fun addArchive(admission: Admission, expandedByteCount: Long, entryCount: Int) {
        admission.ensureOpen()
        rejectUnless(!admission.hasArchiveReservation)
        ensureWithinLimit(expandedByteCount, limits.maxExpandedBytes - expandedBytes)
        ensureWithinLimit(entryCount, limits.maxEntries - entries)
        expandedBytes += expandedByteCount
        entries += entryCount
        admission.expandedBytes = expandedByteCount
        admission.entries = entryCount
        admission.hasArchiveReservation = true
    }

    @Synchronized
    private fun commit(admission: Admission) {
        admission.ensureOpen()
        rejectUnless(admission.hasArchiveReservation)
        admission.state = AdmissionState.COMMITTED
    }

    @Synchronized
    private fun commitAttempt(admission: Admission) {
        admission.ensureOpen()
        rejectUnless(admission.hasSourceCharge)
        admission.state = AdmissionState.COMMITTED
    }

    @Synchronized
    private fun close(admission: Admission) {
        if (admission.state != AdmissionState.OPEN) return
        inputs--
        sourceBytes -= admission.sourceBytes
        expandedBytes -= admission.expandedBytes
        entries -= admission.entries
        admission.state = AdmissionState.CLOSED
    }

    internal class Admission internal constructor(private val owner: ExtensionImportBudget) : Closeable {
        internal var state = AdmissionState.OPEN
        internal var sourceBytes = 0L
        internal var expandedBytes = 0L
        internal var entries = 0
        internal var hasArchiveReservation = false
        internal var hasSourceCharge = false

        fun addSourceBytes(byteCount: Int) = owner.addSourceBytes(this, byteCount)

        fun remainingSourceBytes(): Long = owner.remainingSourceBytes(this)

        fun reserveArchive(expandedBytes: Long, entries: Int) = owner.addArchive(this, expandedBytes, entries)

        fun commit() = owner.commit(this)

        /** Retains completed provider-copy work even when archive validation rejects the input. */
        fun commitAttempt() = owner.commitAttempt(this)

        override fun close() = owner.close(this)

        internal fun ensureOpen() = rejectUnless(state == AdmissionState.OPEN)

        override fun toString(): String = "ExtensionImportAdmission(state=$state, sourceBytes=$sourceBytes, " +
            "expandedBytes=$expandedBytes, entries=$entries)"
    }

    internal enum class AdmissionState {
        OPEN,
        COMMITTED,
        CLOSED,
    }

    private companion object {
        fun ensureWithinLimit(requested: Int, remaining: Int) = rejectUnless(requested >= 0 && requested <= remaining)

        fun ensureWithinLimit(requested: Long, remaining: Long) =
            rejectUnless(requested >= 0L && requested <= remaining)

        fun rejectUnless(condition: Boolean) {
            if (!condition) throw ExtensionImportLimitException()
        }
    }
}
