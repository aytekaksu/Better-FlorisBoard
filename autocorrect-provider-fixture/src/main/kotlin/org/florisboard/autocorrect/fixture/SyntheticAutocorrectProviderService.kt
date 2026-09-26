/*
 * Copyright (C) 2026 The FlorisBoard Contributors
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

package org.florisboard.autocorrect.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import org.florisboard.autocorrect.api.AutocorrectPluginContract
import org.florisboard.autocorrect.api.AutocorrectPluginDocument
import org.florisboard.autocorrect.api.AutocorrectPluginService
import org.florisboard.autocorrect.api.AutocorrectPluginUi
import org.florisboard.autocorrect.api.AutocorrectPluginUiItem
import org.florisboard.autocorrect.api.AutocorrectPluginUiItemKind
import org.florisboard.autocorrect.api.AutocorrectPluginUiPage
import org.florisboard.autocorrect.api.AutocorrectPluginUiSurface
import org.florisboard.autocorrect.api.AutocorrectRequest
import org.florisboard.autocorrect.api.AutocorrectSession
import org.florisboard.autocorrect.api.AutocorrectSuggestionResult

/** Both test providers run in the fixture app's separate UID and process. */
abstract class RecordingAutocorrectProviderService(
    private val eventPrefix: String,
) : AutocorrectPluginService() {
    private fun record(event: String) = SyntheticAutocorrectFixture.record(this, eventPrefix + event)

    protected open fun beforeSuggest(request: AutocorrectRequest) = Unit
    protected open suspend fun afterSuggest() = Unit
    protected open fun fixtureUi(): AutocorrectPluginUi? = null

    override suspend fun onStartSession(session: AutocorrectSession) {
        record("START")
    }

    override suspend fun onSuggestResult(request: AutocorrectRequest): AutocorrectSuggestionResult {
        beforeSuggest(request)
        record("SUGGEST")
        afterSuggest()
        return AutocorrectSuggestionResult.Empty
    }

    override suspend fun onFinishSession(sessionId: Long, finalRequest: AutocorrectRequest?) {
        record("FINISH")
        record(if (finalRequest?.text.isNullOrEmpty()) "FINISH_CONTENT_EMPTY" else "FINISH_CONTENT_PRESENT")
        SyntheticAutocorrectFixture.awaitFinishRelease()
    }

    override suspend fun onGetPluginUi(languageTags: List<String>): AutocorrectPluginUi? {
        record("UI_REQUEST")
        return fixtureUi()
    }

    override suspend fun onSetPluginUiValue(itemId: String, value: String): Boolean {
        record("UI_VALUE")
        return true
    }

    override suspend fun onInvokePluginUiAction(itemId: String): Boolean {
        record("UI_ACTION")
        return true
    }

    override suspend fun onPluginUiDocument(document: AutocorrectPluginDocument): Boolean {
        record("UI_DOCUMENT")
        return true
    }

    override suspend fun onPluginUiClosed() {
        record("UI_CLOSED")
    }

    override suspend fun onHostUnboundCleanup() {
        record("UNBOUND")
        super.onHostUnboundCleanup()
    }
}

/** Provider A keeps the original fixture behavior and bare event names. */
class SyntheticAutocorrectProviderService : RecordingAutocorrectProviderService("")

/** Provider B exposes a distinct UI and can hold its suggestion reply. */
class SyntheticAutocorrectProviderServiceB : RecordingAutocorrectProviderService("B_") {
    override fun beforeSuggest(request: AutocorrectRequest) {
        SyntheticAutocorrectFixture.noteBRequestId(request.requestId)
    }

    override suspend fun afterSuggest() {
        SyntheticAutocorrectFixture.awaitSuggestBRelease()
    }

    override fun fixtureUi() = AutocorrectPluginUi(
        appRootPageId = "fixture-b",
        keyboardRootPageId = "fixture-b",
        pages = listOf(
            AutocorrectPluginUiPage(
                id = "fixture-b",
                title = "Fixture B",
                surface = AutocorrectPluginUiSurface.BOTH,
                items = listOf(
                    AutocorrectPluginUiItem(
                        id = "fixture-b-action",
                        kind = AutocorrectPluginUiItemKind.ACTION,
                        title = "Action",
                    ),
                    AutocorrectPluginUiItem(
                        id = "fixture-b-document",
                        kind = AutocorrectPluginUiItemKind.DOCUMENT_IMPORT,
                        title = "Document",
                        documentMimeTypes = listOf("application/octet-stream"),
                    ),
                ),
            ),
        ),
    )
}

/** Cross-process control channel; it exposes no editor or protocol payloads. */
class SyntheticAutocorrectControlProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        return when (method) {
            "reset" -> {
                SyntheticAutocorrectFixture.reset(context)
                Bundle.EMPTY
            }
            "hold_finish" -> {
                SyntheticAutocorrectFixture.holdFinish()
                Bundle.EMPTY
            }
            "release_finish" -> {
                SyntheticAutocorrectFixture.releaseFinish()
                Bundle.EMPTY
            }
            "hold_suggest_b" -> {
                SyntheticAutocorrectFixture.holdSuggestB()
                Bundle.EMPTY
            }
            "release_suggest_b" -> {
                SyntheticAutocorrectFixture.releaseSuggestB()
                Bundle.EMPTY
            }
            "send_stale_suggestions" -> {
                @Suppress("DEPRECATION")
                val replyTo = requireNotNull(extras?.getParcelable<Messenger>("reply_to"))
                val requestId = requireNotNull(extras).getLong("request_id")
                require(requestId > 0L)
                val sent = runCatching {
                    replyTo.send(Message.obtain(null, AutocorrectPluginContract.MSG_SUGGESTIONS).apply {
                        data = Bundle().apply {
                            putLong("requestId", requestId)
                            putBoolean("handled", false)
                        }
                    })
                }.isSuccess
                Bundle().apply { putBoolean("sent", sent) }
            }
            "kill_provider" -> Bundle().apply {
                val pid = Process.myPid()
                putInt("pid", pid)
                Handler(Looper.getMainLooper()).postDelayed(
                    { Process.killProcess(pid) },
                    500L,
                )
            }
            "snapshot" -> Bundle().apply {
                putStringArrayList(
                    "events",
                    ArrayList(SyntheticAutocorrectFixture.events(context)),
                )
                putInt("pid", Process.myPid())
                putInt("uid", Process.myUid())
                putLong("latest_b_request_id", SyntheticAutocorrectFixture.latestBRequestId())
            }
            else -> throw IllegalArgumentException("Unknown fixture control method")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

private object SyntheticAutocorrectFixture {
    private const val PREFS = "synthetic-autocorrect-fixture"
    private const val EVENTS = "events"
    private var finishRelease: CompletableDeferred<Unit>? = null
    private var suggestBRelease: CompletableDeferred<Unit>? = null
    private var lastBRequestId = 0L

    @Synchronized
    fun reset(context: Context) {
        releaseFinish()
        releaseSuggestB()
        lastBRequestId = 0L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(EVENTS, "").commit()
    }

    @Synchronized
    fun record(context: Context, event: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(EVENTS, "").orEmpty()
        val next = (previous.split(',').filter(String::isNotEmpty) + event)
            .takeLast(64)
            .joinToString(",")
        check(prefs.edit().putString(EVENTS, next).commit())
    }

    @Synchronized
    fun events(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(EVENTS, "").orEmpty().split(',').filter(String::isNotEmpty)

    @Synchronized
    fun holdFinish() {
        finishRelease = CompletableDeferred()
    }

    @Synchronized
    fun releaseFinish() {
        finishRelease?.complete(Unit)
        finishRelease = null
    }

    suspend fun awaitFinishRelease() {
        val current = synchronized(this) { finishRelease }
        current?.await()
    }

    @Synchronized
    fun holdSuggestB() {
        suggestBRelease = CompletableDeferred()
    }

    @Synchronized
    fun releaseSuggestB() {
        suggestBRelease?.complete(Unit)
        suggestBRelease = null
    }

    @Synchronized
    fun noteBRequestId(requestId: Long) {
        lastBRequestId = requestId
    }

    @Synchronized
    fun latestBRequestId() = lastBRequestId

    suspend fun awaitSuggestBRelease() {
        val current = synchronized(this) { suggestBRelease }
        current?.await()
    }
}
