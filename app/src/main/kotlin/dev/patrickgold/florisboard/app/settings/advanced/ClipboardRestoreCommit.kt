/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import android.content.Context
import dev.patrickgold.florisboard.ime.clipboard.ClipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ArchiveClipboardMediaRef
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.InstalledClipboardMedia
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ClipboardRestoreCommitFailure {
    MEDIA_INSTALL_FAILED,
    HISTORY_UPDATE_FAILED,
    MEDIA_CLEANUP_FAILED,
}

internal sealed interface ClipboardRestoreCommitResult {
    data object Committed : ClipboardRestoreCommitResult

    data class Failed(val failure: ClipboardRestoreCommitFailure) : ClipboardRestoreCommitResult
}

/**
 * Publishes one validated clipboard payload without exposing archive URIs to
 * Android or leaving a partial history when a commit step fails.
 */
internal object ClipboardRestoreCommit {
    suspend fun commit(
        context: Context,
        clipboardManager: ClipboardManager,
        payload: PreparedClipboardRestore,
        replaceSelected: Boolean,
    ): ClipboardRestoreCommitResult {
        val callerContext = currentCoroutineContext()
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                val installedMedia = mutableListOf<InstalledClipboardMedia>()
                val installedByRef =
                    mutableMapOf<ArchiveClipboardMediaRef, OwnedClipboardMediaUri>()
                var phase = CommitPhase.MEDIA

                try {
                    callerContext.ensureActive()
                    clipboardManager.awaitInitialization()
                    callerContext.ensureActive()
                    val requiredBytes = payload.media.sumOf(PreparedClipboardMedia::byteCount)
                    if (requiredBytes > 0L &&
                        !ClipboardFileStorage.canInstallBatch(context, requiredBytes)
                    ) {
                        return@withContext ClipboardRestoreCommitResult.Failed(
                            ClipboardRestoreCommitFailure.MEDIA_INSTALL_FAILED,
                        )
                    }
                    for (media in payload.media) {
                        callerContext.ensureActive()
                        val installed = ClipboardFileStorage.installFromBackup(
                            context = context,
                            source = media.stagedFile,
                            expectedBytes = media.byteCount,
                            type = media.ref.type,
                            mimeTypes = media.mimeTypes,
                            displayName = media.displayName,
                            checkActive = callerContext::ensureActive,
                        )
                        installedMedia += installed
                        installedByRef[media.ref] = installed.ownedUri
                    }

                    val items = payload.items.map { item ->
                        ClipboardItem(
                            id = 0,
                            type = item.type,
                            text = item.text,
                            uri = item.mediaRef?.let { ref ->
                                checkNotNull(installedByRef[ref]).uri
                            },
                            creationTimestampMs = item.creationTimestampMs,
                            isPinned = item.isPinned,
                            mimeTypes = item.mimeTypes,
                            isSensitive = item.isSensitive,
                            isRemoteDevice = item.isRemoteDevice,
                        )
                    }

                    callerContext.ensureActive()
                    phase = CommitPhase.HISTORY
                    clipboardManager.commitHistoryRestore(
                        items = items,
                        selectedTypes = payload.selectedTypes,
                        replaceSelected = replaceSelected,
                    )
                    ClipboardRestoreCommitResult.Committed
                } catch (error: Exception) {
                    val mediaCleaned = cleanInstalledMedia(installedMedia)
                    if (!mediaCleaned) {
                        clipboardManager.retryMediaCleanup(installedMedia)
                    }
                    if (error is CancellationException) throw error
                    when {
                        !mediaCleaned -> {
                            ClipboardRestoreCommitResult.Failed(
                                ClipboardRestoreCommitFailure.MEDIA_CLEANUP_FAILED,
                            )
                        }
                        phase == CommitPhase.MEDIA -> {
                            ClipboardRestoreCommitResult.Failed(
                                ClipboardRestoreCommitFailure.MEDIA_INSTALL_FAILED,
                            )
                        }
                        else -> {
                            ClipboardRestoreCommitResult.Failed(
                                ClipboardRestoreCommitFailure.HISTORY_UPDATE_FAILED,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun cleanInstalledMedia(installedMedia: List<InstalledClipboardMedia>): Boolean {
        var mediaCleaned = true
        for (installed in installedMedia.asReversed()) {
            val cleaned = runCatching { installed.cleanup() }.getOrDefault(false)
            mediaCleaned = cleaned && mediaCleaned
        }
        return mediaCleaned
    }

    private enum class CommitPhase {
        MEDIA,
        HISTORY,
    }
}
