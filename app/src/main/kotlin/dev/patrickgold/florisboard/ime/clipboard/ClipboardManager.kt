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

package dev.patrickgold.florisboard.ime.clipboard

import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardExternalMediaImporter
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardHistoryDao
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardHistoryDatabase
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItemImportPlan
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardPasteAdmissionReceipt
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardShareOperationToken
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.InstalledClipboardMedia
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.clipboard.provider.isValidClipboardTimestamp
import dev.patrickgold.florisboard.ime.clipboard.provider.resolveObservedSystemClipboardMedia
import dev.patrickgold.florisboard.ime.clipboard.provider.systemClipboardMediaUri
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.AndroidClipboardManager
import org.florisboard.lib.android.AndroidClipboardManager_OnPrimaryClipChangedListener
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.clearPrimaryClipAnyApi
import org.florisboard.lib.android.setOrClearPrimaryClip
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.android.systemService
import org.florisboard.lib.kotlin.tryOrNull

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MEDIA_IMPORT_TIMEOUT_MS = 30_000L
private const val PASTE_BUSY_RETRY_DELAY_MS = 10L
private const val MAX_SYSTEM_CLIP_ITEMS = 10_000
private const val MAX_SYSTEM_EVENTS_PER_ACTOR_TURN = 16
private const val MAX_FOREIGN_OBSERVATION_MIME_TYPES = 16
private const val MAX_FOREIGN_OBSERVATION_MIME_TYPE_CHARS = 127
private const val READ_CLIPBOARD_APP_OP = "android:read_clipboard"
private const val LEGACY_READ_CLIPBOARD_APP_OP_FIELD = "OP_READ_CLIPBOARD"
private const val LEGACY_CHECK_APP_OP_METHOD = "checkOpNoThrow"

internal fun canConfirmEmptySystemClipboard(
    isFlorisboardSelected: Boolean,
    isDeviceLocked: Boolean,
    isKeyguardLocked: Boolean,
    isReadAllowed: Boolean,
    hasPrimaryClip: Boolean,
): Boolean =
    isFlorisboardSelected &&
        !isDeviceLocked &&
        !isKeyguardLocked &&
        isReadAllowed &&
        !hasPrimaryClip

internal suspend fun <T> convergeSystemClipboardPoll(
    isStable: () -> Boolean,
    awaitFence: suspend () -> Unit,
    observe: () -> T,
    apply: (T) -> Boolean,
): Boolean {
    if (!isStable()) return false
    awaitFence()
    if (!isStable()) return false
    val observation = observe()
    if (!isStable()) return false
    return apply(observation) && isStable()
}

internal fun readClipboardAppOpMode(
    appOpsManager: AppOpsManager,
    uid: Int,
    packageName: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Int? {
    return try {
        if (sdkInt >= Build.VERSION_CODES.P) {
            appOpsManager.checkOpNoThrow(READ_CLIPBOARD_APP_OP, uid, packageName)
        } else {
            val operation = AppOpsManager::class.java
                .getField(LEGACY_READ_CLIPBOARD_APP_OP_FIELD)
                .getInt(null)
            AppOpsManager::class.java
                .getMethod(
                    LEGACY_CHECK_APP_OP_METHOD,
                    Integer.TYPE,
                    Integer.TYPE,
                    String::class.java,
                )
                .invoke(appOpsManager, operation, uid, packageName) as Int
        }
    } catch (_: Exception) {
        null
    }
}

private sealed interface SystemClipboardEvent {
    data class Observed(val clipData: ClipData?) : SystemClipboardEvent

    data object OpaqueChange : SystemClipboardEvent

    data object Unavailable : SystemClipboardEvent
}

private data class SystemClipboardSignal(
    val callbackSequence: Long,
    val localWriteEpoch: Long,
)

internal class BoundedCoalescingDrain<T : Any>(
    private val lock: Any,
    private val maxBatchSize: Int,
) {
    private var pending: T? = null
    private var draining = false

    init {
        require(maxBatchSize > 0)
    }

    /**
     * Replaces any pending value and reports whether a drain command must be
     * enqueued. A running or queued drain owns all later values until it
     * finishes its bounded batch.
     */
    fun offer(value: T): Boolean = synchronized(lock) {
        pending = value
        if (draining) {
            false
        } else {
            draining = true
            true
        }
    }

    /**
     * Processes one bounded batch and reports whether another drain command
     * must be enqueued at the actor tail.
     */
    suspend fun drainBatch(process: suspend (T) -> Unit): Boolean {
        repeat(maxBatchSize) {
            val value = synchronized(lock) {
                pending?.also { pending = null }
            } ?: return finishBatch()
            process(value)
        }
        return finishBatch()
    }

    fun isIdle(): Boolean = synchronized(lock) {
        !draining && pending == null
    }

    fun close() = synchronized(lock) {
        pending = null
        draining = false
    }

    private fun finishBatch(): Boolean = synchronized(lock) {
        if (pending == null) {
            draining = false
            false
        } else {
            true
        }
    }
}

internal class ForeignMediaObservationIdentity private constructor(
    private val sourceFingerprint: ByteArray,
    private val type: ItemType,
    private val mimeTypes: List<String>,
    private val itemCount: Int,
    private val descriptionTimestamp: Long,
    private val hasText: Boolean,
    private val isSensitive: Boolean,
    private val isRemoteDevice: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is ForeignMediaObservationIdentity &&
            sourceFingerprint.contentEquals(other.sourceFingerprint) &&
            type == other.type &&
            mimeTypes == other.mimeTypes &&
            itemCount == other.itemCount &&
            descriptionTimestamp == other.descriptionTimestamp &&
            hasText == other.hasText &&
            isSensitive == other.isSensitive &&
            isRemoteDevice == other.isRemoteDevice

    override fun hashCode(): Int {
        var result = sourceFingerprint.contentHashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + mimeTypes.hashCode()
        result = 31 * result + itemCount
        result = 31 * result + descriptionTimestamp.hashCode()
        result = 31 * result + hasText.hashCode()
        result = 31 * result + isSensitive.hashCode()
        return 31 * result + isRemoteDevice.hashCode()
    }

    override fun toString(): String =
        "ForeignMediaObservationIdentity(type=$type, mimeTypeCount=${mimeTypes.size}, " +
            "itemCount=$itemCount, hasText=$hasText, isSensitive=$isSensitive, " +
            "isRemoteDevice=$isRemoteDevice, source=<redacted>)"

    companion object {
        fun create(
            sourceUri: String,
            type: ItemType,
            mimeTypes: List<String>,
            itemCount: Int,
            descriptionTimestamp: Long,
            hasText: Boolean,
            isSensitive: Boolean,
            isRemoteDevice: Boolean,
        ): ForeignMediaObservationIdentity? {
            if (sourceUri.isEmpty() ||
                mimeTypes.size !in 1..MAX_FOREIGN_OBSERVATION_MIME_TYPES ||
                mimeTypes.any {
                    it.length !in 1..MAX_FOREIGN_OBSERVATION_MIME_TYPE_CHARS
                }
            ) {
                return null
            }
            return ForeignMediaObservationIdentity(
                sourceFingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(sourceUri.toByteArray(Charsets.UTF_8)),
                type = type,
                mimeTypes = mimeTypes.sorted(),
                itemCount = itemCount,
                descriptionTimestamp = descriptionTimestamp,
                hasText = hasText,
                isSensitive = isSensitive,
                isRemoteDevice = isRemoteDevice,
            )
        }
    }
}

internal class ForeignMediaObservationCache<T : Any> {
    private data class Entry<T>(
        val identity: ForeignMediaObservationIdentity,
        val owner: T,
    )

    private var entry: Entry<T>? = null

    fun shouldSkip(
        identity: ForeignMediaObservationIdentity?,
        currentOwner: T?,
    ): Boolean {
        val cached = entry
        return identity != null &&
            currentOwner != null &&
            cached?.identity == identity &&
            cached.owner == currentOwner
    }

    fun record(
        identity: ForeignMediaObservationIdentity?,
        owner: T?,
    ) {
        entry = if (identity != null && owner != null) {
            Entry(identity, owner)
        } else {
            null
        }
    }

    fun invalidate() {
        entry = null
    }
}

internal fun findReusableClipboardHistoryItem(
    items: List<ClipboardItem>,
    newItem: ClipboardItem,
): ClipboardItem? {
    val candidates = when (newItem.type) {
        ItemType.TEXT ->
            items.asSequence().filter { item ->
                item.type == ItemType.TEXT &&
                    item.text == newItem.text &&
                    item.isSensitive == newItem.isSensitive
            }
        ItemType.IMAGE, ItemType.VIDEO -> {
            val newOwner = newItem.uri?.let {
                OwnedClipboardMediaUri.parse(it, newItem.type)
            } ?: return null
            items.asSequence().filter { item ->
                item.type == newItem.type &&
                    item.uri?.let { OwnedClipboardMediaUri.parse(it, item.type) } == newOwner
            }
        }
    }
    return candidates.maxByOrNull(ClipboardItem::creationTimestampMs)
}

private enum class MediaPasteResolution {
    PENDING,
    ABANDONED,
    COMMITTING,
    RESOLVED,
}

private class SystemEventGateAccess(
    val beginWrite: () -> Unit,
    val isCurrent: (SystemClipboardSignal) -> Boolean,
    val isStable: (Long) -> Boolean,
    val awaitMainFence: suspend () -> Unit,
)

internal class ClipboardMediaPasteLease internal constructor(
    internal val media: OwnedClipboardMediaUri,
    private val renewAction: () -> Unit,
    private val releaseAction: () -> Unit,
) : Closeable {
    private val released = AtomicBoolean(false)

    fun renew() {
        if (!released.get()) {
            renewAction()
        }
    }

    override fun close() {
        if (released.compareAndSet(false, true)) {
            releaseAction()
        }
    }
}

internal sealed interface ClipboardMediaPasteLeaseAcquisition {
    data class Acquired(val lease: ClipboardMediaPasteLease) :
        ClipboardMediaPasteLeaseAcquisition

    data object Busy : ClipboardMediaPasteLeaseAcquisition

    data object Rejected : ClipboardMediaPasteLeaseAcquisition
}

internal class ClipboardMediaPasteAccess internal constructor(
    val mimeTypes: List<String>,
    private val acceptAction: () -> Unit,
    private val rejectAction: () -> Unit,
    private val resolvedAction: () -> Unit = {},
) {
    private val resolved = AtomicBoolean(false)

    fun commitSucceededOrMayHaveSucceeded() {
        if (resolved.compareAndSet(false, true)) {
            try {
                acceptAction()
            } finally {
                resolvedAction()
            }
        }
    }

    fun commitRejected() {
        if (resolved.compareAndSet(false, true)) {
            try {
                rejectAction()
            } finally {
                resolvedAction()
            }
        }
    }
}

internal class ClipboardMediaPasteAccessRegistry : Closeable {
    private val lock = Any()
    private val active = linkedSetOf<ClipboardMediaPasteAccess>()
    private var closed = false

    fun register(access: ClipboardMediaPasteAccess): Boolean = synchronized(lock) {
        if (closed) {
            false
        } else {
            active += access
            true
        }
    }

    fun unregister(access: ClipboardMediaPasteAccess) = synchronized(lock) {
        active -= access
        Unit
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                // A concurrent actor completion must not complete the rollback
                // scope before this caller has scheduled every rejection.
                active.toList().forEach(ClipboardMediaPasteAccess::commitRejected)
            }
        }
    }
}

private data class ClipboardBackupLeaseData(
    val id: Long,
    val items: List<ClipboardItem>,
)

internal class ClipboardBackupSnapshot internal constructor(
    val items: List<ClipboardItem>,
    private val releaseAction: suspend () -> Unit,
) {
    private val released = AtomicBoolean(false)

    suspend fun release() {
        if (released.compareAndSet(false, true)) {
            try {
                releaseAction()
            } catch (error: Exception) {
                released.set(false)
                throw error
            }
        }
    }
}

internal class ClipboardMediaPasteLeaseRegistry {
    private data class Entry(
        val media: OwnedClipboardMediaUri,
        val expiresAtNanos: Long,
        val isResolved: Boolean,
    )

    private val lock = Any()
    private val entries = linkedMapOf<Long, Entry>()
    private val deleting = mutableSetOf<OwnedClipboardMediaUri>()
    private var nextId = 0L

    internal fun acquireForPaste(
        media: OwnedClipboardMediaUri,
    ): ClipboardMediaPasteLeaseAcquisition {
        return synchronized(lock) {
            pruneExpired()
            if (media in deleting || entries.size >= MAX_ACTIVE_PASTE_LEASES) {
                return@synchronized ClipboardMediaPasteLeaseAcquisition.Rejected
            }
            if (entries.values.any { it.media == media && !it.isResolved }) {
                return@synchronized ClipboardMediaPasteLeaseAcquisition.Busy
            }
            nextId = if (nextId == Long.MAX_VALUE) 1L else nextId + 1L
            while (entries.containsKey(nextId)) {
                nextId = if (nextId == Long.MAX_VALUE) 1L else nextId + 1L
            }
            val leaseId = nextId
            val expiresAtNanos = System.nanoTime() + PASTE_LEASE_NANOS
            entries[leaseId] = Entry(
                media = media,
                expiresAtNanos = expiresAtNanos,
                isResolved = false,
            )
            ClipboardMediaPasteLeaseAcquisition.Acquired(
                ClipboardMediaPasteLease(
                    media = media,
                    renewAction = {
                        synchronized(lock) {
                            entries[leaseId]?.let { entry ->
                                entries[leaseId] = entry.copy(
                                    expiresAtNanos = System.nanoTime() + PASTE_LEASE_NANOS,
                                    isResolved = true,
                                )
                            }
                        }
                    },
                    releaseAction = {
                        synchronized(lock) {
                            entries.remove(leaseId)
                        }
                    },
                ),
            )
        }
    }

    fun reserveDeletion(media: OwnedClipboardMediaUri): Boolean = synchronized(lock) {
        pruneExpired()
        if (media in deleting || entries.values.any { it.media == media }) {
            false
        } else {
            deleting += media
            true
        }
    }

    fun releaseDeletion(media: OwnedClipboardMediaUri) = synchronized(lock) {
        deleting.remove(media)
        Unit
    }

    fun activeMedia(): Set<OwnedClipboardMediaUri> = synchronized(lock) {
        pruneExpired()
        entries.values.mapTo(mutableSetOf(), Entry::media)
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        deleting.clear()
    }

    private fun pruneExpired() {
        val now = System.nanoTime()
        entries.values.removeAll { entry ->
            now - entry.expiresAtNanos >= 0L
        }
    }

    companion object {
        private const val MAX_ACTIVE_PASTE_LEASES = 128
        private val PASTE_LEASE_NANOS = TimeUnit.MINUTES.toNanos(2L)
    }
}

/**
 * No suspension or cancellation check may split these publication steps.
 */
internal fun commitSystemClipboardMediaPublication(
    prepareRoot: () -> Unit,
    markActive: () -> Unit,
    verifyReadableRoot: () -> Unit,
    publish: () -> Unit,
) {
    prepareRoot()
    markActive()
    verifyReadableRoot()
    publish()
}

/**
 * Owns clipboard state, history mutations, and private clipboard-media lifetimes.
 *
 * State-changing work runs through one FIFO actor so Room writes and media
 * ownership changes cannot overtake each other.
 */
class ClipboardManager(
    context: Context,
) : AndroidClipboardManager_OnPrimaryClipChangedListener, Closeable {
    private val prefs by FlorisPreferenceStore
    private val appContext by context.appContext()
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val systemClipboardManager = context.systemService(AndroidClipboardManager::class)
    private val appOpsManager = context.systemService(AppOpsManager::class)
    private val keyguardManager = context.systemService(AndroidKeyguardManager::class)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pasteRollbackJob = SupervisorJob()
    private val pasteRollbackScope = CoroutineScope(Dispatchers.IO + pasteRollbackJob)
    private val acceptingCommands = AtomicBoolean(true)
    private val lifecycleLock = Any()
    private var listenerRegistered = false
    private val initializationContext = CompletableDeferred<Context>()
    private val initializationComplete = CompletableDeferred<Unit>()
    private val commandChannel = Channel<ClipboardCommand>(Channel.UNLIMITED)
    private val systemEventLock = Any()
    private val systemCallbackGeneration = AtomicLong(0L)
    private val systemGateClosed = AtomicBoolean(false)
    private var latestCallbackSequence = 0L
    private var latestLocalWriteEpoch = 0L
    private var latestWriteCallbackBoundary = 0L
    private val systemSignalDrain = BoundedCoalescingDrain<SystemClipboardSignal>(
        lock = systemEventLock,
        maxBatchSize = MAX_SYSTEM_EVENTS_PER_ACTOR_TURN,
    )
    private val pasteLeaseRegistry = ClipboardMediaPasteLeaseRegistry()
    private val pasteAccessRegistry = ClipboardMediaPasteAccessRegistry()
    private val externalMediaImporter =
        ClipboardExternalMediaImporter(context, MEDIA_IMPORT_TIMEOUT_MS)

    val historyFlow: StateFlow<ClipboardHistory>
        field = MutableStateFlow(ClipboardHistory.EMPTY)
    val currentHistory: ClipboardHistory
        get() = historyFlow.value

    val primaryClipFlow: StateFlow<ClipboardItem?>
        field = MutableStateFlow(null)
    inline var primaryClip
        get() = primaryClipFlow.value
        private set(v) {
            primaryClipFlow.value = v
        }

    private val historyActorJob = ioScope.launch {
        runClipboardActor()
    }
    private val cleanUpJob: Job

    init {
        cleanUpJob = ioScope.launch {
            while (isActive) {
                delay(MILLIS_PER_MINUTE)
                enqueueCommand {
                    maintain()
                }
            }
        }
        historyActorJob.invokeOnCompletion {
            closeSystemEventGate()
            externalMediaImporter.close()
            pasteAccessRegistry.close()
            pasteLeaseRegistry.clear()
            removeSystemClipboardListener()
            pasteRollbackJob.complete()
            ioScope.cancel()
        }
    }

    fun initializeForContext(context: Context) {
        synchronized(lifecycleLock) {
            if (!acceptingCommands.get()) return
            if (!listenerRegistered) {
                try {
                    systemClipboardManager.addPrimaryClipChangedListener(this)
                    listenerRegistered = true
                } catch (_: Exception) {
                    failInitialization()
                    return
                }
            }
            initializationContext.complete(context.applicationContext)
        }
    }

    private fun removeSystemClipboardListener() {
        synchronized(lifecycleLock) {
            if (!listenerRegistered) return
            tryOrNull {
                systemClipboardManager.removePrimaryClipChangedListener(this)
            }
            listenerRegistered = false
        }
    }

    private suspend fun runClipboardActor() {
        var database: ClipboardHistoryDatabase? = null
        try {
            val context = initializationContext.await()
            database = ClipboardHistoryDatabase.new(context)
            val state = ClipboardActorState(
                context = appContext,
                database = database,
                dao = database.clipboardItemDao(),
                prefs = prefs,
                systemClipboardManager = systemClipboardManager,
                appOpsManager = appOpsManager,
                keyguardManager = keyguardManager,
                externalMediaImporter = externalMediaImporter,
                systemEventGate = SystemEventGateAccess(
                    beginWrite = ::beginSystemClipboardWrite,
                    isCurrent = ::isCurrentSystemClipboardSignal,
                    isStable = ::isSystemClipboardStable,
                    awaitMainFence = ::awaitSystemClipboardCallbackFence,
                ),
                activePasteMedia = pasteLeaseRegistry::activeMedia,
                reservePasteDeletion = pasteLeaseRegistry::reserveDeletion,
                releasePasteDeletion = pasteLeaseRegistry::releaseDeletion,
                readPrimaryClip = { primaryClip },
                writePrimaryClip = { primaryClip = it },
                publishHistoryState = { historyFlow.value = it },
            )
            state.initialize()
            initializationComplete.complete(Unit)
            for (command in commandChannel) {
                command.execute(state)
            }
        } catch (error: Exception) {
            initializationComplete.completeExceptionally(error)
            // Pending commands are rejected below. Individual commands contain
            // their own failures so one bad operation cannot stop the actor.
        } finally {
            acceptingCommands.set(false)
            commandChannel.close()
            rejectPendingCommands()
            tryOrNull { database?.close() }
        }
    }

    internal suspend fun awaitInitialization() {
        initializationComplete.await()
    }

    /**
     * Publishes an already installed image through the clipboard actor. Once
     * accepted, cancellation cannot split durable ownership from the Binder
     * write or race ownership reconciliation.
     */
    internal suspend fun publishOwnedClipboardShare(
        ownedUri: OwnedClipboardMediaUri,
        operationToken: ClipboardShareOperationToken,
        requestFingerprint: ClipboardShareRequestFingerprint,
    ): Boolean = withContext(NonCancellable) {
        awaitCommand {
            val published =
                publishOwnedClipboardShare(ownedUri, operationToken, requestFingerprint)
            finishHistoryMutation()
            published
        }
    }

    private fun enqueueCommand(action: suspend ClipboardActorState.() -> Unit) {
        if (!acceptingCommands.get()) return
        commandChannel.trySend(FireAndForgetCommand(action))
    }

    private suspend fun <T> awaitCommand(action: suspend ClipboardActorState.() -> T): T {
        if (!acceptingCommands.get()) throw ClipboardHistoryUnavailableException()
        val response = CompletableDeferred<T>()
        if (commandChannel.trySend(AwaitedCommand(response, action)).isFailure) {
            throw ClipboardHistoryUnavailableException()
        }
        return response.await()
    }

    private abstract inner class ClipboardCommand {
        abstract suspend fun execute(state: ClipboardActorState)

        open fun reject() = Unit
    }

    private inner class FireAndForgetCommand(
        private val action: suspend ClipboardActorState.() -> Unit,
    ) : ClipboardCommand() {
        override suspend fun execute(state: ClipboardActorState) {
            try {
                state.action()
            } catch (_: Exception) {
                // Public clipboard operations are asynchronous. Keep the actor
                // alive and leave the last fully applied state intact.
            }
        }
    }

    private inner class AwaitedCommand<T>(
        private val response: CompletableDeferred<T>,
        private val action: suspend ClipboardActorState.() -> T,
    ) : ClipboardCommand() {
        override suspend fun execute(state: ClipboardActorState) {
            try {
                response.complete(state.action())
            } catch (error: Exception) {
                response.completeExceptionally(error)
            }
        }

        override fun reject() {
            response.completeExceptionally(ClipboardHistoryUnavailableException())
        }
    }

    private class ClipboardHistoryUnavailableException :
        IllegalStateException("Clipboard history is unavailable.")

    private inner class AbortPasteAdmissionCommand(
        private val receipt: ClipboardPasteAdmissionReceipt,
        private val lease: ClipboardMediaPasteLease,
    ) : ClipboardCommand() {
        override suspend fun execute(state: ClipboardActorState) {
            state.registerPasteAdmissionAbort(receipt, lease)
        }

        override fun reject() {
            rollbackPasteAdmissionDirectly(receipt, lease)
        }
    }

    /**
     * Sets the current primary clip without updating the internal clipboard history.
     */
    fun updatePrimaryClip(item: ClipboardItem?) {
        enqueueCommand {
            this.setInternalPrimaryClip(item, syncToSystem = true)
            this.finishHistoryMutation()
        }
    }

    /**
     * Called by the system clipboard when its primary clip changes.
     */
    override fun onPrimaryClipChanged() {
        val callbackSequence = nextSystemCallbackGeneration()
        val enqueueDrain = synchronized(systemEventLock) {
            if (systemGateClosed.get() ||
                !acceptingCommands.get() ||
                callbackSequence != systemCallbackGeneration.get()
            ) {
                return
            }
            latestCallbackSequence = callbackSequence
            systemSignalDrain.offer(
                SystemClipboardSignal(
                    callbackSequence = callbackSequence,
                    localWriteEpoch = latestLocalWriteEpoch,
                ),
            )
        }
        // Register the newer generation before waking a blocked import.
        externalMediaImporter.cancelActive()
        if (enqueueDrain && commandChannel.trySend(SystemEventCommand()).isFailure) {
            close()
        }
    }

    private inner class SystemEventCommand : ClipboardCommand() {
        override suspend fun execute(state: ClipboardActorState) {
            val enqueueNextDrain = systemSignalDrain.drainBatch { signal ->
                try {
                    state.applySystemClipboardSignal(signal)
                } catch (_: Exception) {
                    state.noteSystemClipboardSignal(signal)
                }
            }
            state.finishHistoryMutation()
            if (enqueueNextDrain &&
                commandChannel.trySend(SystemEventCommand()).isFailure
            ) {
                close()
            }
        }
    }

    private fun beginSystemClipboardWrite() {
        synchronized(systemEventLock) {
            latestLocalWriteEpoch = nextSequence(latestLocalWriteEpoch)
            latestWriteCallbackBoundary = systemCallbackGeneration.get()
        }
    }

    private fun isCurrentSystemClipboardSignal(signal: SystemClipboardSignal): Boolean {
        return synchronized(systemEventLock) {
            !systemGateClosed.get() &&
                acceptingCommands.get() &&
                signal.callbackSequence == systemCallbackGeneration.get() &&
                signal.callbackSequence == latestCallbackSequence &&
                signal.callbackSequence > latestWriteCallbackBoundary &&
                signal.localWriteEpoch == latestLocalWriteEpoch
        }
    }

    private fun isSystemClipboardStable(
        lastAppliedCallbackSequence: Long,
    ): Boolean {
        val callbackGeneration = systemCallbackGeneration.get()
        return synchronized(systemEventLock) {
            !systemGateClosed.get() &&
                acceptingCommands.get() &&
                callbackGeneration == systemCallbackGeneration.get() &&
                callbackGeneration == lastAppliedCallbackSequence &&
                latestCallbackSequence == lastAppliedCallbackSequence &&
                systemSignalDrain.isIdle()
        }
    }

    private suspend fun awaitSystemClipboardCallbackFence() {
        val fence = CompletableDeferred<Unit>()
        if (!mainHandler.post { fence.complete(Unit) }) {
            throw ClipboardHistoryUnavailableException()
        }
        fence.await()
    }

    private fun nextSystemCallbackGeneration(): Long {
        while (true) {
            val current = systemCallbackGeneration.get()
            if (current == Long.MAX_VALUE) return current
            val next = current + 1L
            if (systemCallbackGeneration.compareAndSet(current, next)) return next
        }
    }

    private fun closeSystemEventGate() {
        if (systemGateClosed.compareAndSet(false, true)) {
            nextSystemCallbackGeneration()
        }
    }

    private fun nextSequence(current: Long): Long =
        if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L

    /**
     * Wraps plaintext in a clipboard item and makes it primary.
     */
    fun addNewPlaintext(newText: String) {
        val item = ClipboardItem.text(newText)
        enqueueCommand {
            this.addNewClip(item)
        }
    }

    fun clearExactHistory(items: List<ClipboardItem>) {
        val itemIds = items.mapTo(mutableSetOf(), ClipboardItem::id)
        enqueueCommand {
            this.clearExactHistory(itemIds)
        }
    }

    /**
     * Clears all unpinned items from the clipboard history.
     */
    fun clearHistory() {
        enqueueCommand {
            this.clearHistory()
        }
    }

    /**
     * Clears the full clipboard history.
     */
    fun clearFullHistory() {
        enqueueCommand {
            this.clearFullHistory()
        }
    }

    /**
     * Atomically merges or replaces selected clipboard history types.
     *
     * Once this command is accepted, cancellation cannot hide whether its Room
     * transaction committed.
     */
    internal suspend fun commitHistoryRestore(
        items: List<ClipboardItem>,
        selectedTypes: Set<ItemType>,
        replaceSelected: Boolean,
    ) = withContext(NonCancellable) {
        val itemSnapshot = items.map { item ->
            item.copy(mimeTypes = item.mimeTypes.toList())
        }
        val selectedTypeSnapshot = selectedTypes.toSet()
        awaitCommand {
            this.commitHistoryRestore(
                items = itemSnapshot,
                selectedTypes = selectedTypeSnapshot,
                replaceSelected = replaceSelected,
            )
        }
    }

    internal fun retryMediaCleanup(installs: Iterable<InstalledClipboardMedia>) {
        val snapshot = installs.toList()
        if (snapshot.isEmpty()) return
        enqueueCommand {
            retryInstallCleanup(snapshot)
        }
    }

    private sealed interface MediaPasteAccessPreparation {
        data class Ready(val access: ClipboardMediaPasteAccess) :
            MediaPasteAccessPreparation

        data object Busy : MediaPasteAccessPreparation

        data object Rejected : MediaPasteAccessPreparation
    }

    private fun acquireMediaPasteAccess(
        media: OwnedClipboardMediaUri,
    ): MediaPasteAccessPreparation {
        if (!acceptingCommands.get()) return MediaPasteAccessPreparation.Rejected
        val lease = when (val result = pasteLeaseRegistry.acquireForPaste(media)) {
            is ClipboardMediaPasteLeaseAcquisition.Acquired -> result.lease
            ClipboardMediaPasteLeaseAcquisition.Busy -> return MediaPasteAccessPreparation.Busy
            ClipboardMediaPasteLeaseAcquisition.Rejected ->
                return MediaPasteAccessPreparation.Rejected
        }
        val admission = try {
            ClipboardFileStorage.markPasteRoot(
                context = appContext,
                ownedUri = media,
                protectedRoots = pasteLeaseRegistry.activeMedia(),
            )
        } catch (_: Exception) {
            lease.close()
            return MediaPasteAccessPreparation.Rejected
        }
        if (admission.expiredRoots.isNotEmpty()) {
            val expiredRoots = admission.expiredRoots
            enqueueCommand {
                registerExpiredPasteRoots(expiredRoots)
            }
        }
        val fileInfo = admission.fileInfo ?: run {
            lease.close()
            return MediaPasteAccessPreparation.Rejected
        }
        val receipt = admission.receipt ?: run {
            lease.close()
            return MediaPasteAccessPreparation.Rejected
        }
        lateinit var access: ClipboardMediaPasteAccess
        access = ClipboardMediaPasteAccess(
            mimeTypes = fileInfo.mimeTypes.toList(),
            acceptAction = {
                lease.renew()
                ClipboardFileStorage.completePasteAdmission(receipt)
            },
            rejectAction = {
                if (!acceptingCommands.get() ||
                    commandChannel.trySend(
                        AbortPasteAdmissionCommand(receipt, lease),
                    ).isFailure
                ) {
                    rollbackPasteAdmissionDirectly(receipt, lease)
                }
            },
            resolvedAction = {
                pasteAccessRegistry.unregister(access)
            },
        )
        if (!pasteAccessRegistry.register(access)) {
            // The admission raced shutdown after its durable root was written.
            // This path is already on the IO dispatcher, so restore it before
            // the rollback scope can finish.
            rollbackPasteAdmissionNow(receipt, lease)
            return MediaPasteAccessPreparation.Rejected
        }
        return MediaPasteAccessPreparation.Ready(access)
    }

    private fun rollbackPasteAdmissionDirectly(
        receipt: ClipboardPasteAdmissionReceipt,
        lease: ClipboardMediaPasteLease,
    ) {
        pasteRollbackScope.launch {
            rollbackPasteAdmissionNow(receipt, lease)
        }
    }

    private fun rollbackPasteAdmissionNow(
        receipt: ClipboardPasteAdmissionReceipt,
        lease: ClipboardMediaPasteLease,
    ) {
        try {
            val expiredRoots = ClipboardFileStorage.abortPasteAdmission(
                appContext,
                receipt,
            )
            if (expiredRoots.isNotEmpty() && acceptingCommands.get()) {
                enqueueCommand {
                    registerExpiredPasteRoots(expiredRoots)
                }
            }
        } catch (_: Exception) {
            // Process death remains conservative: the durable retention is
            // safer than unlinking media whose delivery became uncertain.
        } finally {
            lease.close()
        }
    }

    internal suspend fun acquireBackupSnapshot(
        selectedTypes: Set<ItemType>,
    ): ClipboardBackupSnapshot {
        require(selectedTypes.isNotEmpty())
        awaitInitialization()
        val lease = withContext(NonCancellable) {
            awaitCommand {
                acquireBackupLease(selectedTypes)
            }
        }
        return ClipboardBackupSnapshot(lease.items) {
            withContext(NonCancellable) {
                awaitCommand {
                    releaseBackupLease(lease.id)
                }
            }
        }
    }

    fun deleteClip(item: ClipboardItem, onlyIfUnpinned: Boolean) {
        val itemId = item.id
        enqueueCommand {
            this.deleteClip(itemId, onlyIfUnpinned)
        }
    }

    fun pinClip(item: ClipboardItem) {
        val itemId = item.id
        enqueueCommand {
            this.setPinned(itemId, isPinned = true)
        }
    }

    fun unpinClip(item: ClipboardItem) {
        val itemId = item.id
        enqueueCommand {
            this.setPinned(itemId, isPinned = false)
        }
    }

    fun pasteItem(
        item: ClipboardItem,
        onResult: (Boolean) -> Unit = {},
    ) {
        val itemSnapshot = item.copy(mimeTypes = item.mimeTypes.toList())
        if (itemSnapshot.type == ItemType.TEXT) {
            keyboardManager.inputEventDispatcher.dispatchInputEvent {
                reportPasteResult(
                    editorInstance.commitClipboardItem(itemSnapshot),
                    onResult,
                )
            }
            return
        }
        val media = itemSnapshot.uri
            ?.let { OwnedClipboardMediaUri.parse(it, itemSnapshot.type) }
            ?: run {
                reportPasteResult(false, onResult)
                return
            }
        val resolution = AtomicReference(MediaPasteResolution.PENDING)
        val completion = AtomicReference<(() -> Unit)?>(null)
        val access = AtomicReference<ClipboardMediaPasteAccess?>(null)
        fun abandon() {
            if (resolution.compareAndSet(
                    MediaPasteResolution.PENDING,
                    MediaPasteResolution.ABANDONED,
                )
            ) {
                completion.getAndSet(null)?.invoke()
            }
        }
        fun resolveOnMain() {
            mainHandler.post {
                if (!resolution.compareAndSet(
                        MediaPasteResolution.PENDING,
                        MediaPasteResolution.COMMITTING,
                    )
                ) {
                    if (resolution.compareAndSet(
                            MediaPasteResolution.ABANDONED,
                            MediaPasteResolution.RESOLVED,
                        )
                    ) {
                        try {
                            access.getAndSet(null)?.commitRejected()
                            onResult(false)
                        } finally {
                            completion.getAndSet(null)?.invoke()
                        }
                    }
                    return@post
                }
                try {
                    val preparedAccess = access.getAndSet(null)
                    val committed = if (acceptingCommands.get() && preparedAccess != null) {
                        editorInstance.commitClipboardItem(itemSnapshot, preparedAccess)
                    } else {
                        preparedAccess?.commitRejected()
                        false
                    }
                    reportPasteResult(committed, onResult)
                } finally {
                    resolution.set(MediaPasteResolution.RESOLVED)
                    completion.getAndSet(null)?.invoke()
                }
            }
        }
        keyboardManager.inputEventDispatcher.deferInputEvents(
            onLaterInputQueued = ::abandon,
            onInvalidated = ::abandon,
            start = { onResolved ->
                completion.set(onResolved)
                ioScope.launch {
                    while (resolution.get() == MediaPasteResolution.PENDING &&
                        acceptingCommands.get()
                    ) {
                        when (val prepared = acquireMediaPasteAccess(media)) {
                            is MediaPasteAccessPreparation.Ready -> {
                                access.set(prepared.access)
                                break
                            }
                            MediaPasteAccessPreparation.Busy -> {
                                delay(PASTE_BUSY_RETRY_DELAY_MS)
                            }
                            MediaPasteAccessPreparation.Rejected -> break
                        }
                    }
                    resolveOnMain()
                }.invokeOnCompletion {
                    resolveOnMain()
                }
            },
        )
    }

    private fun reportPasteResult(
        result: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        onResult(result)
        if (!result) {
            ioScope.launch(Dispatchers.Main.immediate) { appContext.showShortToast("Failed to paste item.") }
        }
    }

    /**
     * Returns true if the editor can accept the clip item, else false.
     */
    fun canBePasted(clipItem: ClipboardItem?): Boolean {
        if (clipItem == null) return false

        return clipItem.mimeTypes.contains("text/plain") || editorInstance.activeInfo.contentMimeTypes.any { editorType ->
            clipItem.mimeTypes.any { clipType ->
                ClipDescription.compareMimeTypes(clipType, editorType)
            }
        }
    }

    /**
     * Stops accepting work. The actor drains accepted commands and closes Room
     * after its last blocking operation, so shutdown cannot race the database.
     */
    override fun close() {
        terminate(rejectQueuedImmediately = false)
    }

    /**
     * Terminates bootstrap after preferences fail to load. Unlike normal
     * shutdown, queued awaited work is rejected immediately because the actor
     * cannot initialize and therefore cannot drain it.
     */
    internal fun failInitialization() {
        terminate(rejectQueuedImmediately = true)
    }

    private fun terminate(rejectQueuedImmediately: Boolean) {
        val failure = ClipboardHistoryUnavailableException()
        closeSystemEventGate()
        externalMediaImporter.close()
        synchronized(lifecycleLock) {
            cleanUpJob.cancel()
            val wasAcceptingCommands = acceptingCommands.getAndSet(false)
            systemSignalDrain.close()
            if (wasAcceptingCommands || rejectQueuedImmediately) {
                initializationContext.completeExceptionally(failure)
                if (rejectQueuedImmediately) {
                    initializationComplete.completeExceptionally(failure)
                }
                commandChannel.close()
            }
        }
        removeSystemClipboardListener()
        pasteAccessRegistry.close()
        if (rejectQueuedImmediately) {
            rejectPendingCommands()
        }
    }

    private fun rejectPendingCommands() {
        while (true) {
            val pending = commandChannel.tryReceive().getOrNull() ?: break
            pending.reject()
        }
    }

}

private class ClipboardActorState(
    private val context: Context,
    private val database: ClipboardHistoryDatabase,
    private val dao: ClipboardHistoryDao,
    private val prefs: FlorisPreferenceModel,
    private val systemClipboardManager: AndroidClipboardManager,
    private val appOpsManager: AppOpsManager,
    private val keyguardManager: AndroidKeyguardManager,
    private val externalMediaImporter: ClipboardExternalMediaImporter,
    private val systemEventGate: SystemEventGateAccess,
    private val activePasteMedia: () -> Set<OwnedClipboardMediaUri>,
    private val reservePasteDeletion: (OwnedClipboardMediaUri) -> Boolean,
    private val releasePasteDeletion: (OwnedClipboardMediaUri) -> Unit,
    private val readPrimaryClip: () -> ClipboardItem?,
    private val writePrimaryClip: (ClipboardItem?) -> Unit,
    private val publishHistoryState: (ClipboardHistory) -> Unit,
) {
    private val pendingInstallCleanup =
        linkedMapOf<OwnedClipboardMediaUri, InstalledClipboardMedia>()
    private val pendingMediaCleanup = linkedSetOf<OwnedClipboardMediaUri>()
    private val pendingPasteAdmissionAborts =
        linkedMapOf<ClipboardPasteAdmissionReceipt, ClipboardMediaPasteLease>()
    private val backupLeaseMedia = linkedMapOf<Long, Set<OwnedClipboardMediaUri>>()
    private var nextBackupLeaseId = 0L
    private var systemPrimaryKnown = false
    private var systemPrimaryMedia = emptySet<OwnedClipboardMediaUri>()
    private var systemObservationConfirmed = false
    private var lastAppliedCallbackSequence = 0L
    private var systemObservationRequested = false
    private var systemSyncPollingRequested = false
    private var ownershipReconciliationRequested = false
    private val foreignMediaObservationCache =
        ForeignMediaObservationCache<OwnedClipboardMediaUri>()

    suspend fun initialize() {
        trimExpiredPasteRoots()
        normalizeHistoryMetadata()
        try {
            enforceHistoryLimit()
        } catch (_: Exception) {
            // The initial history is still published below.
        }
        val knownRoots = runCatching {
            dao.getAll().mapNotNullTo(mutableSetOf(), ::ownedMediaFromItem).apply {
                ownedMediaFromItem(readPrimaryClip())?.let(::add)
            }
        }.getOrDefault(emptySet())
        systemSyncPollingRequested = shouldReadSystemClipboard()
        val exactObservationNeeded = systemSyncPollingRequested ||
            pendingMediaCleanup.isNotEmpty() ||
            runCatching {
                ClipboardFileStorage.hasUnresolvedOwnership(context, knownRoots)
            }.getOrDefault(false)
        val safeReconciliationNeeded = runCatching {
            ClipboardFileStorage.hasPendingOwnership(context)
        }.getOrDefault(true)
        systemObservationRequested = exactObservationNeeded
        ownershipReconciliationRequested = exactObservationNeeded || safeReconciliationNeeded
        if (exactObservationNeeded) {
            refreshSystemClipboardOwnershipForCleanup()
        }
        try {
            enforceHistoryLimit()
        } catch (_: Exception) {
            // Polling may already have inserted the system item.
        }
        publishHistory()
        retryOwnershipReconciliation()
        retryRetiredMedia()
    }

    private fun normalizeHistoryMetadata() {
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val repairs = dao.getAll().mapNotNull { stored ->
            var normalized = stored
            if (!isValidClipboardTimestamp(normalized.creationTimestampMs, now)) {
                normalized = normalized.copy(
                    creationTimestampMs = normalized.creationTimestampMs.coerceIn(0L, now),
                )
            }
            if (normalized.type != ItemType.TEXT) {
                normalized = runCatching {
                    canonicalizeHistoryItem(normalized)
                }.getOrNull() ?: normalized
            }
            normalized.takeIf { it != stored }
        }
        if (repairs.isNotEmpty()) {
            database.runInTransaction {
                dao.update(repairs)
            }
        }
    }

    fun noteSystemClipboardSignal(signal: SystemClipboardSignal) {
        lastAppliedCallbackSequence = maxOf(
            lastAppliedCallbackSequence,
            signal.callbackSequence,
        )
    }

    fun applySystemClipboardSignal(signal: SystemClipboardSignal) {
        if (!systemEventGate.isCurrent(signal)) {
            noteSystemClipboardSignal(signal)
            return
        }
        applyCurrentSystemClipboard(signal)
        noteSystemClipboardSignal(signal)
    }

    private fun applyCurrentSystemClipboard(signal: SystemClipboardSignal) {
        // A real callback can represent new provider content behind the same
        // URI, so only stable maintenance observations may use the cache.
        foreignMediaObservationCache.invalidate()
        val shouldSync = shouldReadSystemClipboard()
        if (!shouldSync &&
            systemPrimaryMedia.isEmpty() &&
            pendingMediaCleanup.isEmpty() &&
            !systemObservationRequested
        ) {
            return
        }
        val systemPrimaryClip = when (val event = observeSystemClipboardOwnership()) {
            is SystemClipboardEvent.Observed -> event.clipData
            SystemClipboardEvent.OpaqueChange,
            SystemClipboardEvent.Unavailable,
            -> {
                systemSyncPollingRequested = shouldSync
                markSystemClipboardUnknown()
                return
            }
        }
        if (!systemEventGate.isCurrent(signal)) return
        if (!updateSystemPrimaryMedia(systemPrimaryClip, confirmsDurableRoots = true)) {
            systemSyncPollingRequested = shouldSync
            return
        }
        if (!shouldSync) {
            systemSyncPollingRequested = false
            return
        }
        systemSyncPollingRequested = true
        if (
            syncInternalClipboardFromSystem(systemPrimaryClip) {
                systemEventGate.isCurrent(signal)
            }
        ) {
            systemSyncPollingRequested = false
        } else {
            systemObservationRequested = true
        }
    }

    private fun syncInternalClipboardFromSystem(
        systemPrimaryClip: ClipData?,
        isCurrent: () -> Boolean,
    ): Boolean {
        if (!shouldReadSystemClipboard() || !isCurrent()) return false
        val syncBehavior = effectiveSyncToFloris()
        if (systemPrimaryClip == null) {
            if (syncBehavior.shouldSyncClear) {
                setInternalPrimaryClip(null, syncToSystem = false)
            }
            return isCurrent()
        }
        val dataItem = systemPrimaryClip.firstItemOrNull()
        return when {
            dataItem == null || dataItem.text == null && dataItem.uri == null -> {
                if (syncBehavior.shouldSyncClear) {
                    setInternalPrimaryClip(null, syncToSystem = false)
                }
                isCurrent()
            }
            !syncBehavior.shouldSyncSet ||
                readPrimaryClip()?.isEqualTo(context, systemPrimaryClip) == true ->
                isCurrent()
            else -> {
                val plan = try {
                    ClipboardItem.planFromClipData(context, systemPrimaryClip)
                } catch (_: Exception) {
                    return false
                }
                val foreignIdentity = when (plan) {
                    is ClipboardItemImportPlan.Ready -> null
                    is ClipboardItemImportPlan.ExternalMedia ->
                        foreignMediaObservationIdentity(systemPrimaryClip, plan)
                }
                if (plan is ClipboardItemImportPlan.ExternalMedia &&
                    foreignMediaObservationCache.shouldSkip(
                        identity = foreignIdentity,
                        currentOwner = ownedMediaFromItem(readPrimaryClip()),
                    )
                ) {
                    return isCurrent()
                }
                val item = cloneSystemClipboardItem(plan) ?: return false
                if (!isCurrent()) return false
                val persistedItem = try {
                    insertOrMoveBeginning(item)
                } catch (_: Exception) {
                    item
                }
                setInternalPrimaryClip(persistedItem, syncToSystem = false)
                isCurrent().also { current ->
                    if (current) {
                        foreignMediaObservationCache.record(
                            identity = foreignIdentity,
                            owner = ownedMediaFromItem(persistedItem),
                        )
                    }
                }
            }
        }
    }

    private fun cloneSystemClipboardItem(
        plan: ClipboardItemImportPlan,
    ): ClipboardItem? {
        return try {
            when (plan) {
                is ClipboardItemImportPlan.Ready -> plan.item
                is ClipboardItemImportPlan.ExternalMedia -> {
                    val staged = externalMediaImporter.stage(plan.source) ?: return null
                    staged.use {
                        plan.install(
                            context = context,
                            stagedFile = staged.path,
                            byteCount = staged.byteCount,
                            displayName = staged.displayName,
                        ) { installed ->
                            pendingInstallCleanup[installed.ownedUri] = installed
                        }
                    }
                }
            }
        } catch (_: Exception) {
            retryPendingInstalls()
            null
        }
    }

    private fun foreignMediaObservationIdentity(
        data: ClipData,
        plan: ClipboardItemImportPlan.ExternalMedia,
    ): ForeignMediaObservationIdentity? {
        return ForeignMediaObservationIdentity.create(
            sourceUri = plan.source.toString(),
            type = plan.type,
            mimeTypes = plan.mimeTypes,
            itemCount = data.itemCount,
            descriptionTimestamp = data.description.timestamp,
            hasText = plan.text != null,
            isSensitive = plan.isSensitive,
            isRemoteDevice = plan.isRemoteDevice,
        )
    }

    private fun observeSystemClipboardOwnership(): SystemClipboardEvent {
        return try {
            val clipData = systemClipboardManager.primaryClip
            when {
                clipData != null -> SystemClipboardEvent.Observed(clipData)
                canConfirmEmptySystemClipboard(
                    isFlorisboardSelected = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                        InputMethodUtils.isFlorisboardSelected(context),
                    isDeviceLocked = keyguardManager.isDeviceLocked,
                    isKeyguardLocked = keyguardManager.isKeyguardLocked,
                    isReadAllowed = canReadSystemClipboard(),
                    hasPrimaryClip = systemClipboardManager.hasPrimaryClip(),
                ) ->
                    SystemClipboardEvent.Observed(null)
                else -> SystemClipboardEvent.OpaqueChange
            }
        } catch (_: Exception) {
            SystemClipboardEvent.Unavailable
        }
    }

    private fun shouldReadSystemClipboard(): Boolean =
        effectiveSyncToFloris().requiresSystemClipboardRead

    private fun effectiveSyncToFloris(): ClipboardSyncBehavior =
        resolveSyncToFlorisBehavior(
            useInternalClipboard = prefs.clipboard.useInternalClipboard.get(),
            configuredBehavior = prefs.clipboard.syncToFloris.get(),
        )

    private fun canReadSystemClipboard(): Boolean {
        return readClipboardAppOpMode(
            appOpsManager = appOpsManager,
            uid = Process.myUid(),
            packageName = context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    suspend fun addNewClip(item: ClipboardItem) {
        val persistedItem = try {
            insertOrMoveBeginning(item)
        } catch (_: Exception) {
            item
        }
        setInternalPrimaryClip(persistedItem, syncToSystem = true)
        finishHistoryMutation()
    }

    suspend fun clearExactHistory(itemIds: Set<Long>) {
        val storedItems = dao.getAll().filter { it.id in itemIds }
        if (storedItems.isNotEmpty()) {
            prepareItemRetirement(storedItems)
            dao.delete(storedItems)
        }
        finishHistoryMutation()
    }

    suspend fun clearHistory() {
        val storedItems = dao.getAll().filterNot(ClipboardItem::isPinned)
        if (storedItems.isNotEmpty()) {
            prepareItemRetirement(storedItems)
            dao.deleteAllUnpinned()
        }
        finishHistoryMutation()
    }

    suspend fun clearFullHistory() {
        val storedItems = dao.getAll()
        if (storedItems.isNotEmpty()) {
            prepareItemRetirement(storedItems)
            dao.deleteAll()
        }
        finishHistoryMutation()
    }

    suspend fun deleteClip(itemId: Long, onlyIfUnpinned: Boolean) {
        val storedItem = dao.getAll().firstOrNull { it.id == itemId } ?: return
        if (onlyIfUnpinned && storedItem.isPinned) return
        prepareItemRetirement(listOf(storedItem))
        dao.delete(storedItem.id)
        finishHistoryMutation()
    }

    suspend fun setPinned(itemId: Long, isPinned: Boolean) {
        val storedItem = dao.getAll().firstOrNull { it.id == itemId } ?: return
        dao.update(storedItem.copy(isPinned = isPinned))
        finishHistoryMutation()
    }

    suspend fun commitHistoryRestore(
        items: List<ClipboardItem>,
        selectedTypes: Set<ItemType>,
        replaceSelected: Boolean,
    ) {
        require(selectedTypes.isNotEmpty()) { "Clipboard restore selection is empty." }
        require(items.all { it.type in selectedTypes }) {
            "Clipboard restore item type is not selected."
        }
        val canonicalItems = items.map { item ->
            requireNotNull(canonicalizeHistoryItem(item)) {
                "Clipboard restore media is unavailable."
            }
        }
        canonicalItems.forEach(::requireValidRestoreItem)

        val previousItems = dao.getAll()
        val retainedItems = if (replaceSelected) {
            previousItems.filterNot { it.type in selectedTypes }
        } else {
            previousItems
        }
        val identities = retainedItems
            .mapTo(mutableSetOf()) { it.copy(id = 0) }
        val pendingItems = buildList {
            for (item in canonicalItems) {
                val normalized = item.copy(id = 0)
                if (identities.add(normalized)) {
                    add(normalized.copy())
                }
            }
        }
        if (prefs.clipboard.historySizeLimitEnabled.get()) {
            require(
                (retainedItems + pendingItems).count { !it.isPinned } <=
                    prefs.clipboard.historySizeLimit.get(),
            ) {
                "Clipboard restore exceeds the configured history limit."
            }
        }
        val removedItems = if (replaceSelected) {
            previousItems.filter { it.type in selectedTypes }
        } else {
            emptyList()
        }

        prepareItemRetirement(removedItems)
        database.runInTransaction {
            if (replaceSelected) {
                selectedTypes.forEach(dao::deleteAllFromType)
            }
            pendingItems.forEach { item ->
                item.id = dao.insert(item)
            }
        }

        // The Room commit is the definitive restore boundary. Everything
        // below is best effort so callers never clean media now referenced
        // by a transaction which actually committed.
        runCatching {
            ClipboardFileStorage.markActive(
                context,
                pendingItems.mapNotNull(::ownedMediaFromItem),
            )
        }
        finishHistoryMutation()
    }

    suspend fun maintain() {
        val hasSystemOwnership = systemPrimaryMedia.isNotEmpty() ||
            runCatching {
                ClipboardFileStorage.systemRoots(context).isNotEmpty()
            }.getOrDefault(true)
        systemSyncPollingRequested = shouldReadSystemClipboard()
        if (systemSyncPollingRequested || hasSystemOwnership) {
            systemObservationRequested = true
        }
        if (runCatching {
                ClipboardFileStorage.hasPendingOwnership(context)
            }.getOrDefault(true)
        ) {
            // This also revisits bounded process-restored share installs once
            // their claim window expires.
            ownershipReconciliationRequested = true
        }
        trimExpiredPasteRoots()
        enforceExpiryDate()
        finishHistoryMutation()
    }

    suspend fun retryInstallCleanup(installs: List<InstalledClipboardMedia>) {
        installs.forEach { installed ->
            pendingInstallCleanup[installed.ownedUri] = installed
        }
        finishHistoryMutation()
    }

    fun acquireBackupLease(selectedTypes: Set<ItemType>): ClipboardBackupLeaseData {
        require(selectedTypes.isNotEmpty())
        val items = loadSortedHistory()
            .filter { it.type in selectedTypes }
            .map { item -> item.copy(mimeTypes = item.mimeTypes.toList()) }
        nextBackupLeaseId = if (nextBackupLeaseId == Long.MAX_VALUE) 1L else nextBackupLeaseId + 1L
        while (backupLeaseMedia.containsKey(nextBackupLeaseId)) {
            nextBackupLeaseId =
                if (nextBackupLeaseId == Long.MAX_VALUE) 1L else nextBackupLeaseId + 1L
        }
        backupLeaseMedia[nextBackupLeaseId] =
            items.mapNotNullTo(mutableSetOf(), ::ownedMediaFromItem)
        return ClipboardBackupLeaseData(nextBackupLeaseId, items)
    }

    suspend fun releaseBackupLease(leaseId: Long) {
        if (!backupLeaseMedia.remove(leaseId).isNullOrEmpty()) {
            systemObservationConfirmed = false
            systemObservationRequested = true
            ownershipReconciliationRequested = true
        }
        finishHistoryMutation()
    }

    suspend fun registerExpiredPasteRoots(media: Set<OwnedClipboardMediaUri>) {
        trackExpiredPasteRoots(media)
        finishHistoryMutation()
    }

    suspend fun registerPasteAdmissionAbort(
        receipt: ClipboardPasteAdmissionReceipt,
        lease: ClipboardMediaPasteLease,
    ) {
        pendingPasteAdmissionAborts[receipt] = lease
        finishHistoryMutation()
    }

    private fun trimExpiredPasteRoots() {
        val expired = runCatching {
            ClipboardFileStorage.trimPasteRoots(
                context = context,
                protectedRoots = activePasteMedia(),
            )
        }.getOrDefault(emptySet())
        trackExpiredPasteRoots(expired)
    }

    private fun trackExpiredPasteRoots(media: Set<OwnedClipboardMediaUri>) {
        if (media.isEmpty()) return
        pendingMediaCleanup += media
        systemObservationConfirmed = false
        systemObservationRequested = true
        ownershipReconciliationRequested = true
    }

    private fun enforceExpiryDate() {
        val clipHistory = ClipboardHistory(loadSortedHistory())
        val itemsToRemove = mutableSetOf<ClipboardItem>()
        if (prefs.clipboard.historyAutoCleanOldEnabled.get()) {
            val expiryTime = System.currentTimeMillis() -
                (prefs.clipboard.historyAutoCleanOldAfter.get().toLong() * MILLIS_PER_MINUTE)
            itemsToRemove += clipHistory.unpinned.filter {
                it.creationTimestampMs < expiryTime
            }
        }
        if (prefs.clipboard.historyAutoCleanSensitiveEnabled.get()) {
            val expiryTime = System.currentTimeMillis() -
                (prefs.clipboard.historyAutoCleanSensitiveAfter.get().toLong() * MILLIS_PER_SECOND)
            itemsToRemove += clipHistory.all.filter {
                it.isSensitive && it.creationTimestampMs < expiryTime
            }
        }
        if (itemsToRemove.isNotEmpty()) {
            val removedItems = itemsToRemove.toList()
            prepareItemRetirement(removedItems)
            dao.delete(removedItems)
        }
    }

    private fun insertOrMoveBeginning(newItem: ClipboardItem): ClipboardItem {
        if (!prefs.clipboard.historyEnabled.get()) return newItem

        val existingItem = findReusableClipboardHistoryItem(dao.getAll(), newItem)
        return if (existingItem != null) {
            newItem.copy(
                id = existingItem.id,
                isPinned = existingItem.isPinned,
            ).also { item -> dao.update(item) }
        } else {
            newItem.copy(id = 0).also { item ->
                item.id = dao.insert(item)
            }
        }
    }

    fun setInternalPrimaryClip(
        item: ClipboardItem?,
        syncToSystem: Boolean,
    ) {
        val previousMedia = ownedMediaFromItem(readPrimaryClip())
        val currentMedia = ownedMediaFromItem(item)
        if (previousMedia != currentMedia) {
            previousMedia?.let { prepareMediaRetirement(listOf(it)) }
        }
        currentMedia?.let { media ->
            runCatching { ClipboardFileStorage.markActive(context, listOf(media)) }
        }
        writePrimaryClip(item)
        if (syncToSystem) {
            syncSystemClipboard(item)
        }
    }

    private fun syncSystemClipboard(item: ClipboardItem?) {
        try {
            val clipData = item?.toClipData(context)
            val trustedMedia = if (clipData != null) {
                ownedMediaFromItem(item)?.let(::setOf).orEmpty()
            } else {
                emptySet()
            }
            if (prefs.clipboard.useInternalClipboard.get()) {
                val syncBehavior = prefs.clipboard.syncToSystem.get()
                when {
                    clipData != null && syncBehavior.shouldSyncSet -> {
                        writeSystemClipboard(trustedMedia) {
                            systemClipboardManager.setPrimaryClip(clipData)
                        }
                    }
                    clipData == null && syncBehavior.shouldSyncClear -> {
                        writeSystemClipboard(emptySet()) {
                            systemClipboardManager.clearPrimaryClipAnyApi()
                        }
                    }
                }
            } else {
                writeSystemClipboard(trustedMedia) {
                    systemClipboardManager.setOrClearPrimaryClip(clipData)
                }
            }
        } catch (_: Exception) {
            // Keep the previously observed system root when synchronization
            // fails; it may still be the only owner of private media.
        }
    }

    private fun writeSystemClipboard(
        currentMedia: Set<OwnedClipboardMediaUri>,
        write: () -> Unit,
    ) {
        val previousMedia = systemPrimaryMedia
        try {
            commitSystemClipboardMediaPublication(
                prepareRoot = {
                    ClipboardFileStorage.prepareSystemRoots(context, currentMedia)
                },
                markActive = {
                    ClipboardFileStorage.markActive(context, currentMedia)
                },
                verifyReadableRoot = {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 &&
                        currentMedia.any {
                            ClipboardFileStorage.currentSystemRootFileInfo(context, it) == null
                        }
                    ) {
                        throw IllegalStateException(
                            "System clipboard media root is unavailable.",
                        )
                    }
                },
                publish = {
                    systemEventGate.beginWrite()
                    write()
                },
            )
        } catch (error: Exception) {
            // Root preparation is the durable capability boundary. Keep every
            // candidate because Binder may accept a write before throwing.
            markSystemClipboardUnknown(previousMedia + currentMedia)
            throw error
        }
        systemPrimaryKnown = true
        systemPrimaryMedia = currentMedia
        systemObservationConfirmed = false
        systemObservationRequested = true
        ownershipReconciliationRequested = true
    }

    suspend fun publishOwnedClipboardShare(
        ownedUri: OwnedClipboardMediaUri,
        operationToken: ClipboardShareOperationToken,
        requestFingerprint: ClipboardShareRequestFingerprint,
    ): Boolean {
        require(ownedUri.type == ItemType.IMAGE)
        val fileInfo = ClipboardFileStorage.claimPendingShareForPublication(
            context = context,
            ownedUri = ownedUri,
            token = operationToken,
            requestFingerprint = requestFingerprint,
        ) ?: return false
        if (systemPrimaryKnown && systemPrimaryMedia == setOf(ownedUri)) {
            return true
        }
        val clipData = clipboardShareClipData(
            uri = systemClipboardMediaUri(ownedUri),
            mimeTypes = fileInfo.mimeTypes,
        )
        writeSystemClipboard(setOf(ownedUri)) {
            systemClipboardManager.setPrimaryClip(clipData)
        }
        return true
    }

    private fun updateSystemPrimaryMedia(
        clipData: ClipData?,
        confirmsDurableRoots: Boolean,
    ): Boolean {
        val previousMedia = systemPrimaryMedia
        val observedMedia = inspectOwnedSystemMedia(
            context = context,
            clipData = clipData,
        ) ?: run {
            markSystemClipboardUnknown()
            return false
        }
        val currentMedia = try {
            observedMedia.filterTo(mutableSetOf()) { media ->
                ClipboardFileStorage.fileInfo(context, media) != null
            }
        } catch (_: Exception) {
            markSystemClipboardUnknown(previousMedia + observedMedia)
            return false
        }
        try {
            ClipboardFileStorage.prepareSystemRoots(context, currentMedia)
        } catch (_: Exception) {
            markSystemClipboardUnknown(previousMedia + currentMedia)
            return false
        }
        systemPrimaryKnown = true
        systemPrimaryMedia = currentMedia
        if (confirmsDurableRoots) {
            retireObservedMedia(previousMedia - currentMedia)
        }
        systemObservationConfirmed = confirmsDurableRoots
        systemObservationRequested = !confirmsDurableRoots
        ownershipReconciliationRequested = true
        return true
    }

    private fun markSystemClipboardUnknown(
        additionalCandidates: Set<OwnedClipboardMediaUri> = emptySet(),
    ) {
        systemPrimaryKnown = false
        systemObservationConfirmed = false
        val durableRoots = runCatching {
            ClipboardFileStorage.systemRoots(context)
        }.getOrDefault(emptySet())
        systemPrimaryMedia = systemPrimaryMedia + additionalCandidates + durableRoots
        systemObservationRequested = true
        ownershipReconciliationRequested = true
    }

    private fun retireObservedMedia(media: Set<OwnedClipboardMediaUri>) {
        if (media.isEmpty()) return
        try {
            prepareMediaRetirement(media)
        } catch (_: Exception) {
            pendingMediaCleanup += media
        }
    }

    suspend fun finishHistoryMutation() {
        try {
            enforceHistoryLimit()
        } catch (_: Exception) {
            // The requested mutation has already completed.
        }
        retryPasteAdmissionAborts()
        retryPendingInstalls()
        refreshSystemClipboardOwnershipForCleanup()
        try {
            enforceHistoryLimit()
        } catch (_: Exception) {
            // Polling may already have inserted the system item.
        }
        try {
            publishHistory()
        } catch (_: Exception) {
            // The next successful command republishes the database state.
        }
        retryOwnershipReconciliation()
        retryRetiredMedia()
    }

    private suspend fun refreshSystemClipboardOwnershipForCleanup() {
        if (!systemObservationRequested && pendingMediaCleanup.isEmpty()) {
            return
        }
        val syncPollRequested =
            systemSyncPollingRequested && shouldReadSystemClipboard()
        if (!syncPollRequested) {
            systemSyncPollingRequested = false
        }
        val firstObservation = when (val event = observeSystemClipboardOwnership()) {
            is SystemClipboardEvent.Observed ->
                updateSystemPrimaryMedia(event.clipData, confirmsDurableRoots = true)
            SystemClipboardEvent.OpaqueChange,
            SystemClipboardEvent.Unavailable,
            -> {
                markSystemClipboardUnknown()
                false
            }
        }
        if (!firstObservation) return

        // Android posts clipboard listener callbacks to the main loop. Fence
        // that queue, then observe again so a notification already in flight
        // cannot be overtaken by physical media cleanup.
        systemObservationConfirmed = false
        systemObservationRequested = true
        var syncConverged = !syncPollRequested
        val ownershipConverged = try {
            convergeSystemClipboardPoll(
                isStable = {
                    systemEventGate.isStable(lastAppliedCallbackSequence)
                },
                awaitFence = systemEventGate.awaitMainFence,
                observe = ::observeSystemClipboardOwnership,
            ) { event ->
                when (event) {
                    is SystemClipboardEvent.Observed -> {
                        val ownershipUpdated = updateSystemPrimaryMedia(
                            event.clipData,
                            confirmsDurableRoots = true,
                        )
                        if (ownershipUpdated && syncPollRequested) {
                            syncConverged = syncInternalClipboardFromSystem(
                                event.clipData,
                            ) {
                                systemEventGate.isStable(lastAppliedCallbackSequence)
                            }
                        }
                        ownershipUpdated
                    }
                    SystemClipboardEvent.OpaqueChange,
                    SystemClipboardEvent.Unavailable,
                    -> false
                }
            }
        } catch (_: Exception) {
            false
        }
        if (!ownershipConverged) {
            markSystemClipboardUnknown()
        } else if (syncPollRequested) {
            systemSyncPollingRequested = !syncConverged
            if (!syncConverged) {
                systemObservationRequested = true
            }
        }
    }

    private fun retryPasteAdmissionAborts() {
        val iterator = pendingPasteAdmissionAborts.iterator()
        while (iterator.hasNext()) {
            val (receipt, lease) = iterator.next()
            val expiredRoots = try {
                ClipboardFileStorage.abortPasteAdmission(context, receipt)
            } catch (_: Exception) {
                continue
            }
            trackExpiredPasteRoots(expiredRoots)
            iterator.remove()
            lease.close()
        }
    }

    private fun retryOwnershipReconciliation() {
        if (!ownershipReconciliationRequested) return
        val observed = systemPrimaryKnown && systemObservationConfirmed
        val historyRoots = try {
            dao.getAll().mapNotNullTo(mutableSetOf(), ::ownedMediaFromItem).apply {
                ownedMediaFromItem(readPrimaryClip())?.let(::add)
                if (!observed) addAll(systemPrimaryMedia)
                addAll(activePasteMedia())
                backupLeaseMedia.values.forEach(::addAll)
            }
        } catch (_: Exception) {
            return
        }
        val observedRoots = if (observed) systemPrimaryMedia else emptySet()
        if (!systemEventGate.isStable(lastAppliedCallbackSequence)) return
        var completed = false
        runCatching {
            val failed = ClipboardFileStorage.reconcileOwnership(
                context = context,
                historyRoots = historyRoots,
                observedSystemRoots = observedRoots,
                systemClipboardObserved = observed,
                reserveDeletion = reservePasteDeletion,
                releaseDeletion = releasePasteDeletion,
                isSystemStateCurrent = {
                    systemEventGate.isStable(lastAppliedCallbackSequence)
                },
            )
            pendingMediaCleanup += failed
            if (failed.isNotEmpty()) {
                systemObservationConfirmed = false
                systemObservationRequested = true
            }
            if (systemEventGate.isStable(lastAppliedCallbackSequence)) {
                if (observed) {
                    systemPrimaryMedia = ClipboardFileStorage.systemRoots(context)
                }
                completed = true
            }
        }
        if (completed) {
            val retryUnknown = !observed &&
                (shouldReadSystemClipboard() || pendingMediaCleanup.isNotEmpty())
            systemObservationRequested = systemObservationRequested || retryUnknown
            ownershipReconciliationRequested =
                pendingMediaCleanup.isNotEmpty() || retryUnknown
        }
    }

    private fun retryPendingInstalls() {
        if (pendingInstallCleanup.isEmpty()) return
        val referencedMedia = try {
            buildSet<OwnedClipboardMediaUri> {
                dao.getAll().mapNotNullTo(this, ::ownedMediaFromItem)
                ownedMediaFromItem(readPrimaryClip())?.let(::add)
                addAll(ClipboardFileStorage.systemRoots(context))
                addAll(ClipboardFileStorage.pasteRoots(context))
                addAll(activePasteMedia())
                backupLeaseMedia.values.forEach(::addAll)
            }
        } catch (_: Exception) {
            return
        }
        val iterator = pendingInstallCleanup.iterator()
        while (iterator.hasNext()) {
            val (ownedUri, installed) = iterator.next()
            if (ownedUri in referencedMedia ||
                runCatching { installed.cleanup() }.getOrDefault(false) ||
                runCatching {
                    ClipboardFileStorage.isDeletionQuarantined(context, ownedUri)
                }.getOrDefault(false)
            ) {
                iterator.remove()
            }
        }
    }

    private fun enforceHistoryLimit() {
        if (!prefs.clipboard.historySizeLimitEnabled.get()) return

        val clipHistory = ClipboardHistory(loadSortedHistory())
        val itemsToRemove = clipHistory.unpinned.takeLast(
            (clipHistory.unpinned.size - prefs.clipboard.historySizeLimit.get())
                .coerceAtLeast(0),
        )
        if (itemsToRemove.isNotEmpty()) {
            prepareItemRetirement(itemsToRemove)
            dao.delete(itemsToRemove)
        }
    }

    private fun publishHistory() {
        publishHistoryState(ClipboardHistory(loadSortedHistory()))
    }

    private fun loadSortedHistory(): List<ClipboardItem> {
        return dao.getAll().sortedByDescending(ClipboardItem::creationTimestampMs)
    }

    private fun canonicalizeHistoryItem(item: ClipboardItem): ClipboardItem? {
        if (item.type == ItemType.TEXT) return item
        val ownedUri = item.uri
            ?.let { OwnedClipboardMediaUri.parse(it, item.type) }
            ?: return null
        val fileInfo = ClipboardFileStorage.fileInfo(context, ownedUri) ?: return null
        return item.copy(
            uri = ownedUri.uri,
            mimeTypes = fileInfo.mimeTypes.toList(),
        )
    }

    private fun prepareItemRetirement(items: Iterable<ClipboardItem>) {
        prepareMediaRetirement(items.mapNotNull(::ownedMediaFromItem))
    }

    private fun prepareMediaRetirement(media: Iterable<OwnedClipboardMediaUri>) {
        val ownedMedia = media.toSet()
        if (ownedMedia.isEmpty()) return
        ClipboardFileStorage.markRetiring(context, ownedMedia)
        pendingMediaCleanup += ownedMedia
        systemObservationConfirmed = false
        systemObservationRequested = true
        ownershipReconciliationRequested = true
    }

    private fun retryRetiredMedia() {
        if (pendingMediaCleanup.isEmpty() ||
            !systemPrimaryKnown ||
            !systemObservationConfirmed ||
            !systemEventGate.isStable(lastAppliedCallbackSequence)
        ) {
            return
        }
        val localReferencedMedia: Set<OwnedClipboardMediaUri>
        val systemReferencedMedia: Set<OwnedClipboardMediaUri>
        try {
            localReferencedMedia = buildSet {
                dao.getAll().mapNotNullTo(this) { item ->
                    ownedMediaFromItem(item)
                }
                ownedMediaFromItem(readPrimaryClip())?.let(::add)
                addAll(ClipboardFileStorage.pasteRoots(context))
                addAll(activePasteMedia())
                backupLeaseMedia.values.forEach(::addAll)
            }
            systemReferencedMedia =
                systemPrimaryMedia + ClipboardFileStorage.systemRoots(context)
        } catch (_: Exception) {
            return
        }
        val referencedMedia = localReferencedMedia + systemReferencedMedia
        if (runCatching {
            ClipboardFileStorage.markActive(
                context,
                pendingMediaCleanup.filter(referencedMedia::contains),
            )
        }.isFailure) {
            ownershipReconciliationRequested = true
            return
        }
        val iterator = pendingMediaCleanup.iterator()
        var retryNeedsFreshObservation = false
        while (iterator.hasNext()) {
            if (!systemEventGate.isStable(lastAppliedCallbackSequence)) break
            val ownedMedia = iterator.next()
            if (ownedMedia in localReferencedMedia) {
                iterator.remove()
                continue
            }
            if (ownedMedia in systemReferencedMedia) {
                retryNeedsFreshObservation = true
                continue
            }
            if (!reservePasteDeletion(ownedMedia)) {
                retryNeedsFreshObservation = true
                continue
            }
            val deletedOrAbsent = try {
                if (!systemEventGate.isStable(lastAppliedCallbackSequence)) {
                    false
                } else {
                    try {
                        ClipboardFileStorage.deleteOwned(
                            context,
                            ownedMedia,
                        ) {
                            systemEventGate.isStable(lastAppliedCallbackSequence)
                        } || ClipboardFileStorage.fileInfo(context, ownedMedia) == null
                    } catch (_: Exception) {
                        false
                    }
                }
            } finally {
                releasePasteDeletion(ownedMedia)
            }
            if (deletedOrAbsent) {
                iterator.remove()
            } else if (runCatching {
                    ClipboardFileStorage.isDeletionQuarantined(context, ownedMedia)
                }.getOrDefault(false)
            ) {
                iterator.remove()
            } else {
                retryNeedsFreshObservation = true
            }
        }
        if (retryNeedsFreshObservation) {
            systemObservationConfirmed = false
        }
    }

    private fun requireValidRestoreItem(item: ClipboardItem) {
        require(isValidClipboardTimestamp(item.creationTimestampMs)) {
            "Clipboard restore item timestamp is invalid."
        }
        require(
            item.mimeTypes.size in 1..MAX_RESTORE_MIME_TYPES &&
                item.mimeTypes.distinctBy(String::lowercase).size == item.mimeTypes.size &&
                item.mimeTypes.all { mimeType ->
                    mimeType.length <= MAX_RESTORE_MIME_TYPE_LENGTH &&
                        RESTORE_MIME_TYPE.matches(mimeType.lowercase())
                },
        ) {
            "Clipboard restore MIME metadata is invalid."
        }
        when (item.type) {
            ItemType.TEXT -> {
                require(
                    item.text != null &&
                        item.text.length <= MAX_RESTORE_TEXT_CHARS &&
                        item.uri == null &&
                        item.mimeTypes.size == 1 &&
                        item.mimeTypes.single().equals("text/plain", ignoreCase = true),
                ) {
                    "Clipboard restore text item is invalid."
                }
            }
            ItemType.IMAGE, ItemType.VIDEO -> {
                val mimePrefix = if (item.type == ItemType.IMAGE) "image/" else "video/"
                val conflictingPrefix = if (item.type == ItemType.IMAGE) "video/" else "image/"
                require(
                    item.mimeTypes.isNotEmpty() &&
                        item.mimeTypes.any { it.startsWith(mimePrefix, ignoreCase = true) } &&
                        item.mimeTypes.none {
                            it.startsWith(conflictingPrefix, ignoreCase = true)
                        },
                ) {
                    "Clipboard restore media item is invalid."
                }
                val ownedMedia = item.uri
                    ?.let { OwnedClipboardMediaUri.parse(it, item.type) }
                require(
                    ownedMedia != null &&
                        ClipboardFileStorage.ownedFile(context, ownedMedia) != null,
                ) {
                    "Clipboard restore media is unavailable."
                }
            }
        }
    }

    companion object {
        private const val MAX_RESTORE_TEXT_CHARS = 1_000_000
        private const val MAX_RESTORE_MIME_TYPES = 16
        private const val MAX_RESTORE_MIME_TYPE_LENGTH = 127
        private val RESTORE_MIME_TYPE =
            Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")
    }
}

private fun ownedMediaFromItem(item: ClipboardItem?): OwnedClipboardMediaUri? {
    return item?.uri?.let { uri ->
        OwnedClipboardMediaUri.parse(uri, item.type)
    }
}

private fun inspectOwnedSystemMedia(
    context: Context,
    clipData: ClipData?,
): Set<OwnedClipboardMediaUri>? {
    if (clipData == null) return emptySet()
    val itemCount = try {
        clipData.itemCount
    } catch (_: Exception) {
        return null
    }
    return collectOwnedSystemMedia(itemCount) { index ->
        val item = clipData.getItemAt(index)
        fun resolve(uri: android.net.Uri): OwnedClipboardMediaUri? {
            return resolveObservedSystemClipboardMedia(context, uri)
        }
        listOfNotNull(
            item.uri?.let(::resolve),
            item.intent?.data?.let(::resolve),
        )
    }
}

internal fun <T> collectOwnedSystemMedia(
    itemCount: Int,
    ownedAt: (Int) -> Iterable<T>,
): Set<T>? {
    if (itemCount !in 0..MAX_SYSTEM_CLIP_ITEMS) return null
    return try {
        buildSet {
            repeat(itemCount) { index ->
                addAll(ownedAt(index))
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun ClipData?.firstItemOrNull(): ClipData.Item? {
    return this?.takeIf { it.itemCount > 0 }?.getItemAt(0)
}
