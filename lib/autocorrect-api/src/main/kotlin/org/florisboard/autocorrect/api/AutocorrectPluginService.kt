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

package org.florisboard.autocorrect.api

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Base service for an external autocorrect provider.
 *
 * Suggestion work is cancelled when a newer request arrives or the host unbinds. Implementations
 * should cooperate with coroutine cancellation and must not start a background service, acquire a
 * wake lock, or schedule recurring work for an active typing session.
 */
abstract class AutocorrectPluginService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    private var suggestionJob: Job? = null
    private var activeSessionId: Long? = null
    private val messenger = Messenger(IncomingHandler())

    final override fun onBind(intent: Intent?): IBinder? {
        return messenger.binder.takeIf {
            intent?.action == AutocorrectPluginContract.ACTION_BIND_PROVIDER
        }
    }

    final override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    protected open suspend fun onStartSession(session: AutocorrectSession) = Unit

    protected abstract suspend fun onSuggest(request: AutocorrectRequest): List<AutocorrectCandidate>

    protected open suspend fun onSuggestionAccepted(sessionId: Long, candidateId: String) = Unit

    protected open suspend fun onSuggestionReverted(sessionId: Long, candidateId: String) = Unit

    protected open suspend fun onRemoveSuggestion(sessionId: Long, candidateId: String): Boolean = false

    protected open suspend fun onFinishSession(sessionId: Long) = Unit

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                AutocorrectPluginContract.MSG_START_SESSION -> {
                    val session = AutocorrectSession.fromBundle(message.data)
                    suggestionJob?.cancel()
                    sessionJob?.cancel()
                    activeSessionId = session.sessionId
                    sessionJob = serviceScope.launch {
                        onStartSession(session)
                    }
                }
                AutocorrectPluginContract.MSG_SUGGEST -> {
                    val request = AutocorrectRequest.fromBundle(message.data)
                    if (request.sessionId != activeSessionId) return
                    val replyTo = message.replyTo
                    suggestionJob?.cancel()
                    suggestionJob = serviceScope.launch {
                        sessionJob?.join()
                        val candidates = onSuggest(request)
                        replyTo.sendSafely(
                            AutocorrectPluginContract.MSG_SUGGESTIONS,
                            candidatesToBundle(request.requestId, candidates),
                        )
                    }
                }
                AutocorrectPluginContract.MSG_ACCEPTED -> {
                    val sessionId = message.data.getLong(Keys.SESSION_ID)
                    if (sessionId != activeSessionId) return
                    val candidateId = message.data.getString(Keys.ID).orEmpty()
                        .take(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS)
                    serviceScope.launch {
                        onSuggestionAccepted(sessionId, candidateId)
                    }
                }
                AutocorrectPluginContract.MSG_REVERTED -> {
                    val sessionId = message.data.getLong(Keys.SESSION_ID)
                    if (sessionId != activeSessionId) return
                    val candidateId = message.data.getString(Keys.ID).orEmpty()
                        .take(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS)
                    serviceScope.launch {
                        onSuggestionReverted(sessionId, candidateId)
                    }
                }
                AutocorrectPluginContract.MSG_REMOVE -> {
                    val sessionId = message.data.getLong(Keys.SESSION_ID)
                    if (sessionId != activeSessionId) return
                    val requestId = message.data.getLong(Keys.REQUEST_ID)
                    val candidateId = message.data.getString(Keys.ID).orEmpty()
                        .take(AutocorrectPluginContract.MAX_CANDIDATE_ID_CHARS)
                    val replyTo = message.replyTo
                    serviceScope.launch {
                        val removed = onRemoveSuggestion(sessionId, candidateId)
                        replyTo.sendSafely(
                            AutocorrectPluginContract.MSG_REMOVE_RESULT,
                            android.os.Bundle().apply {
                                putLong(Keys.REQUEST_ID, requestId)
                                putBoolean(Keys.REMOVED, removed)
                            },
                        )
                    }
                }
                AutocorrectPluginContract.MSG_FINISH_SESSION -> {
                    val sessionId = message.data.getLong(Keys.SESSION_ID)
                    if (sessionId != activeSessionId) return
                    suggestionJob?.cancel()
                    sessionJob?.cancel()
                    activeSessionId = null
                    serviceScope.launch {
                        onFinishSession(sessionId)
                    }
                }
                AutocorrectPluginContract.MSG_CANCEL -> {
                    suggestionJob?.cancel()
                }
                else -> super.handleMessage(message)
            }
        }
    }
}

private fun Messenger?.sendSafely(what: Int, data: android.os.Bundle) {
    if (this == null) return
    try {
        send(Message.obtain(null, what).apply { this.data = data })
    } catch (_: RemoteException) {
        // The host disappeared; Android will shortly unbind and destroy this service.
    }
}
