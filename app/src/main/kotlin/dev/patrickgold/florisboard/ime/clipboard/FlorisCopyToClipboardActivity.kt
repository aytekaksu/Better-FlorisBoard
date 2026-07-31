/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState as collectFlowAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dev.patrickgold.florisboard.ApplicationBootstrapState
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardShareOperationToken
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.collectIn
import org.florisboard.lib.kotlin.mimeTypeFilterOf

internal fun Context.isExternalClipboardShareUri(
    uri: Uri,
    intentFlags: Int,
): Boolean {
    return try {
        val sourceAuthority = uri.authority
            ?.let(Uri::decode)
            ?.substringAfterLast('@')
        if ((intentFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 ||
            uri.scheme != ContentResolver.SCHEME_CONTENT ||
            sourceAuthority == null
        ) {
            false
        } else {
            @Suppress("DEPRECATION")
            val sourceUid =
                packageManager.resolveContentProvider(sourceAuthority, 0)?.applicationInfo?.uid
            sourceUid != null && sourceUid != applicationInfo.uid
        }
    } catch (_: RuntimeException) {
        false
    }
}

internal data class ClipboardShareIntentHeader(
    val action: String?,
    val type: String?,
)

internal data class ClipboardShareIntentStream(
    val uri: Uri?,
    val flags: Int,
) {
    override fun toString(): String =
        "ClipboardShareIntentStream(hasUri=${uri != null}, " +
            "hasReadGrant=${(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0})"
}

internal fun Intent.clipboardShareHeaderOrNull(): ClipboardShareIntentHeader? = try {
    ClipboardShareIntentHeader(action = action, type = type)
} catch (_: RuntimeException) {
    null
}

internal fun Intent.clipboardShareStreamOrNull(): ClipboardShareIntentStream? = try {
    val uri: Uri? =
        if (AndroidVersion.ATLEAST_API33_T) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
    ClipboardShareIntentStream(uri = uri, flags = flags)
} catch (_: RuntimeException) {
    null
}

internal fun clipboardShareClipData(
    uri: Uri,
    mimeTypes: List<String>,
): ClipData = ClipData(
    "image",
    mimeTypes.toTypedArray(),
    ClipData.Item(uri),
)

internal sealed interface ClipboardShareCopyState {
    data object Loading : ClipboardShareCopyState

    data class Failed(
        val error: FlorisCopyToClipboardActivity.CopyToClipboardError,
    ) : ClipboardShareCopyState

    data class Ready(val preview: ClipboardSharePreview) : ClipboardShareCopyState
}

internal data class ClipboardShareOperationResolution(
    val operation: ClipboardShareOperation,
    val requiresPublication: Boolean,
)

internal fun resolveClipboardShareOperation(
    sourceUri: String,
    declaredMimeType: String,
    restoredState: ClipboardShareSavedState?,
    restoredStateInvalid: Boolean,
): ClipboardShareOperationResolution? {
    if (restoredStateInvalid) return null
    val operation = ClipboardShareOperation.resolve(
        sourceUri = sourceUri,
        declaredMimeType = declaredMimeType,
        restoredToken = restoredState?.token,
        restoredRequestFingerprint = restoredState?.requestFingerprint,
    ) ?: return null
    return ClipboardShareOperationResolution(
        operation = operation,
        requiresPublication = restoredState?.completed != true,
    )
}

internal fun clipboardShareCompletionWasObserved(
    previouslyCompleted: Boolean,
    restoredState: ClipboardShareSavedState?,
    copyState: ClipboardShareCopyState,
): Boolean =
    previouslyCompleted ||
        restoredState?.completed == true ||
        copyState is ClipboardShareCopyState.Ready

internal sealed interface ClipboardShareRestoration {
    data object NotStarted : ClipboardShareRestoration

    data object Terminal : ClipboardShareRestoration

    data class Operation(val state: ClipboardShareSavedState) : ClipboardShareRestoration
}

internal class ClipboardShareViewModel : ViewModel() {
    var copyState by mutableStateOf<ClipboardShareCopyState>(ClipboardShareCopyState.Loading)
        private set

    private var started = false
    private var terminalFailure = false
    private var loadJob: Job? = null

    val hasBegun: Boolean
        get() = started

    fun begin(): Boolean {
        if (started) return false
        started = true
        return true
    }

    fun fail(error: FlorisCopyToClipboardActivity.CopyToClipboardError) {
        copyState = ClipboardShareCopyState.Failed(error)
    }

    fun failTerminal(error: FlorisCopyToClipboardActivity.CopyToClipboardError) {
        if (terminalFailure) return
        terminalFailure = true
        loadJob?.cancel()
        (copyState as? ClipboardShareCopyState.Ready)?.preview?.bitmap?.recycle()
        fail(error)
    }

    fun completeRestored() {
        copyState = ClipboardShareCopyState.Ready(
            ClipboardSharePreview(bitmap = null, mimeType = null),
        )
    }

    fun load(
        context: Context,
        uri: Uri,
        declaredMimeType: String,
        operation: ClipboardShareOperation,
        clipboardManager: ClipboardManager,
    ) {
        loadJob = viewModelScope.launch {
            val preview = try {
                ClipboardSharePreviewLoader.load(
                    context = context,
                    uri = uri,
                    declaredMimeType = declaredMimeType,
                    operation = operation,
                    publishOwnedMedia = clipboardManager::publishOwnedClipboardShare,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
            copyState = if (preview == null) {
                ClipboardShareCopyState.Failed(
                    FlorisCopyToClipboardActivity.CopyToClipboardError.UNKNOWN_ERROR,
                )
            } else {
                ClipboardShareCopyState.Ready(preview)
            }
        }
    }

    override fun onCleared() {
        (copyState as? ClipboardShareCopyState.Ready)?.preview?.bitmap?.recycle()
        super.onCleared()
    }
}

class FlorisCopyToClipboardActivity : ComponentActivity() {
    private val shareViewModel by viewModels<ClipboardShareViewModel>()
    private val florisApplication by appContext()
    private val florisClipboardManager by clipboardManager()
    private val filter = mimeTypeFilterOf("image/*")
    private val mainHandler = Handler(Looper.getMainLooper())
    private var restoredOperationState: ClipboardShareSavedState? = null
    private var restoredOperationStateInvalid = false
    private var shareOperation: ClipboardShareOperation? = null
    private var shareCompleted = false
    private val finishWhenStopped = Runnable {
        if (!isChangingConfigurations && !isFinishing && !isDestroyed) {
            closeShare()
        }
    }

    internal enum class CopyToClipboardError {
        UNKNOWN_ERROR,
        TYPE_NOT_SUPPORTED_ERROR;

        @Composable
        fun showError(): String {
            val textId = when (this) {
                UNKNOWN_ERROR -> R.string.send_to_clipboard__unknown_error
                TYPE_NOT_SUPPORTED_ERROR -> R.string.send_to_clipboard__type_not_supported_error
            }
            return stringRes(id = textId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            savedStateRegistry
                .consumeRestoredStateForKey(SHARE_OPERATION_REGISTRY_KEY)
                ?.let { savedState ->
                    when (val restoration = savedState.clipboardShareRestorationOrNull()) {
                        ClipboardShareRestoration.NotStarted -> Unit
                        ClipboardShareRestoration.Terminal, null -> {
                            restoredOperationStateInvalid = true
                        }
                        is ClipboardShareRestoration.Operation -> {
                            restoredOperationState = restoration.state
                        }
                    }
                }
        } catch (_: RuntimeException) {
            restoredOperationStateInvalid = true
        }
        savedStateRegistry.registerSavedStateProvider(SHARE_OPERATION_REGISTRY_KEY) {
            Bundle().apply {
                shareCompleted = clipboardShareCompletionWasObserved(
                    previouslyCompleted = shareCompleted,
                    restoredState = restoredOperationState,
                    copyState = shareViewModel.copyState,
                )
                val state = shareOperation?.let {
                    ClipboardShareSavedState(
                        token = it.token.value,
                        requestFingerprint = it.requestFingerprint.value,
                        completed = shareCompleted,
                    )
                } ?: restoredOperationState?.copy(completed = shareCompleted)
                when {
                    state != null -> {
                        putString(SHARE_OPERATION_PHASE_KEY, SHARE_OPERATION_PHASE_OPERATION)
                        putBundle(SHARE_OPERATION_NESTED_KEY, state.toBundle())
                    }
                    shareViewModel.hasBegun -> {
                        putString(SHARE_OPERATION_PHASE_KEY, SHARE_OPERATION_PHASE_TERMINAL)
                    }
                    else -> {
                        putString(SHARE_OPERATION_PHASE_KEY, SHARE_OPERATION_PHASE_NOT_STARTED)
                    }
                }
            }
        }

        setContent {
            Content()
        }
        handleBootstrapState(florisApplication.applicationBootstrapState.value)
        florisApplication.applicationBootstrapState.collectIn(lifecycleScope) {
            handleBootstrapState(it)
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(finishWhenStopped)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations &&
            florisApplication.applicationBootstrapState.value.isTerminalFailure
        ) {
            // Bootstrap is process-scoped. Back, task dismissal, and framework
            // finishes must leave a fresh process for the next share attempt.
            closeShare()
        } else {
            mainHandler.post(finishWhenStopped)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(finishWhenStopped)
        super.onDestroy()
    }

    private fun handleBootstrapState(state: ApplicationBootstrapState) {
        when {
            state.isTerminalFailure -> {
                shareViewModel.failTerminal(CopyToClipboardError.UNKNOWN_ERROR)
            }
            state.canRenderPreferenceBackedUi -> handleIntent(intent)
        }
    }

    private fun closeShare() {
        finish()
        if (florisApplication.applicationBootstrapState.value.isTerminalFailure) {
            // The retry suggested by the error starts with a fresh bootstrap.
            Process.killProcess(Process.myPid())
        }
    }

    private fun handleIntent(intent: Intent) {
        if (!shareViewModel.begin()) return
        if (restoredOperationStateInvalid) {
            shareViewModel.fail(CopyToClipboardError.UNKNOWN_ERROR)
            return
        }
        val header = intent.clipboardShareHeaderOrNull()
        val type = header?.type

        if (Intent.ACTION_SEND != header?.action || type == null) {
            shareViewModel.fail(CopyToClipboardError.UNKNOWN_ERROR)
            return
        }
        val typeMatches = try {
            filter.matches(type)
        } catch (_: RuntimeException) {
            false
        }
        if (type.length > MAX_SHARE_MIME_TYPE_LENGTH || !typeMatches) {
            shareViewModel.fail(CopyToClipboardError.TYPE_NOT_SUPPORTED_ERROR)
            return
        }

        val stream = intent.clipboardShareStreamOrNull()
        val uri = stream?.uri
        if (uri == null || !isExternalClipboardShareUri(uri, stream.flags)) {
            shareViewModel.fail(CopyToClipboardError.TYPE_NOT_SUPPORTED_ERROR)
            return
        }
        val restoredState = restoredOperationState
        val resolution = resolveClipboardShareOperation(
            sourceUri = uri.toString(),
            declaredMimeType = type,
            restoredState = restoredState,
            restoredStateInvalid = restoredOperationStateInvalid,
        )
        if (resolution == null) {
            shareViewModel.fail(CopyToClipboardError.TYPE_NOT_SUPPORTED_ERROR)
            return
        }
        shareOperation = resolution.operation
        restoredOperationState = null
        if (!resolution.requiresPublication) {
            shareViewModel.completeRestored()
            return
        }

        // Resolve the app manager on Main; the awaited publication itself runs
        // through its isolated clipboard actor.
        shareViewModel.load(
            context = applicationContext,
            uri = uri,
            declaredMimeType = type,
            operation = resolution.operation,
            clipboardManager = florisClipboardManager,
        )
    }

    @Composable
    private fun Content() {
        val bootstrapState by
            florisApplication.applicationBootstrapState.collectFlowAsState()
        ProvideLocalizedResources(
            resourcesContext = this,
            appName = R.string.app_name,
        ) {
            if (bootstrapState.canRenderPreferenceBackedUi) {
                val prefs by FlorisPreferenceStore
                val theme by prefs.other.settingsTheme.collectAsState()
                FlorisAppTheme(theme) {
                    CopyStateContent(shareViewModel.copyState)
                }
            } else {
                MaterialTheme {
                    val copyState =
                        if (bootstrapState.isTerminalFailure) {
                            ClipboardShareCopyState.Failed(CopyToClipboardError.UNKNOWN_ERROR)
                        } else {
                            ClipboardShareCopyState.Loading
                        }
                    CopyStateContent(copyState)
                }
            }
        }
    }

    @Composable
    private fun CopyStateContent(state: ClipboardShareCopyState) {
        BottomSheet {
            when (state) {
                ClipboardShareCopyState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is ClipboardShareCopyState.Failed -> {
                    Row {
                        Text(
                            text = state.error.showError(),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is ClipboardShareCopyState.Ready -> {
                    Row {
                        Text(
                            text = stringRes(
                                id = R.string.send_to_clipboard__description__copied_image_to_clipboard,
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    state.preview.bitmap?.let { bitmap ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                modifier = Modifier
                                    .padding(
                                        start = 64.dp,
                                        end = 64.dp,
                                        top = 32.dp,
                                        bottom = 8.dp,
                                    ),
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BottomSheet(
        content: @Composable ColumnScope.() -> Unit,
    ) {
        ModalBottomSheet(
            modifier = Modifier.navigationBarsPadding(),
            onDismissRequest = ::closeShare,
        ) {
            Column {
                content()
                Button(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(16.dp),
                    onClick = ::closeShare,
                    colors = ButtonDefaults.textButtonColors(),
                ) {
                    Text(text = stringRes(id = R.string.action__ok))
                }
            }
        }
    }
}

internal data class ClipboardShareSavedState(
    val token: String,
    val requestFingerprint: String,
    val completed: Boolean = false,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(SHARE_OPERATION_TOKEN_KEY, token)
        putString(SHARE_OPERATION_FINGERPRINT_KEY, requestFingerprint)
        putBoolean(SHARE_OPERATION_COMPLETED_KEY, completed)
    }
}

internal fun Bundle.clipboardShareSavedStateOrNull(): ClipboardShareSavedState? {
    val nested = safelyRead(SHARE_OPERATION_NESTED_KEY) as? Bundle ?: return null
    val token = nested.safelyRead(SHARE_OPERATION_TOKEN_KEY) as? String ?: return null
    val fingerprint =
        nested.safelyRead(SHARE_OPERATION_FINGERPRINT_KEY) as? String ?: return null
    val completed = when (val value = nested.safelyRead(SHARE_OPERATION_COMPLETED_KEY)) {
        null -> false
        is Boolean -> value
        else -> return null
    }
    if (ClipboardShareOperationToken.parse(token) == null ||
        ClipboardShareRequestFingerprint.parse(fingerprint) == null
    ) {
        return null
    }
    return ClipboardShareSavedState(token, fingerprint, completed)
}

internal fun Bundle.clipboardShareRestorationOrNull(): ClipboardShareRestoration? {
    val phase = safelyRead(SHARE_OPERATION_PHASE_KEY) as? String ?: return null
    val expectedKeys = when (phase) {
        SHARE_OPERATION_PHASE_NOT_STARTED,
        SHARE_OPERATION_PHASE_TERMINAL,
        -> setOf(SHARE_OPERATION_PHASE_KEY)
        SHARE_OPERATION_PHASE_OPERATION ->
            setOf(SHARE_OPERATION_PHASE_KEY, SHARE_OPERATION_NESTED_KEY)
        else -> return null
    }
    val keys = try {
        keySet()
    } catch (_: RuntimeException) {
        return null
    }
    if (keys != expectedKeys) return null
    return when (phase) {
        SHARE_OPERATION_PHASE_NOT_STARTED -> ClipboardShareRestoration.NotStarted
        SHARE_OPERATION_PHASE_TERMINAL -> ClipboardShareRestoration.Terminal
        SHARE_OPERATION_PHASE_OPERATION -> clipboardShareSavedStateOrNull()
            ?.let { ClipboardShareRestoration.Operation(it) }
        else -> null
    }
}

private fun Bundle.safelyRead(key: String): Any? = try {
    @Suppress("DEPRECATION")
    get(key)
} catch (_: RuntimeException) {
    null
}

internal const val SHARE_OPERATION_REGISTRY_KEY =
    "dev.patrickgold.florisboard.clipboard.share.state"
internal const val SHARE_OPERATION_NESTED_KEY = "operation"
internal const val SHARE_OPERATION_TOKEN_KEY = "token"
internal const val SHARE_OPERATION_FINGERPRINT_KEY = "request_fingerprint"
internal const val SHARE_OPERATION_COMPLETED_KEY = "completed"
internal const val SHARE_OPERATION_PHASE_KEY = "phase"
internal const val SHARE_OPERATION_PHASE_NOT_STARTED = "not_started"
internal const val SHARE_OPERATION_PHASE_OPERATION = "operation"
internal const val SHARE_OPERATION_PHASE_TERMINAL = "terminal"
