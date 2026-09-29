/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.media.emoji

import android.content.Context
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Loads the default emoji font once. The palette uses the system font until metadata is ready,
 * or throughout the session if no provider is available or loading fails.
 */
object FlorisEmojiCompat {
    private val mutableInstanceFlow = MutableStateFlow<EmojiCompat?>(null)
    val instanceFlow: StateFlow<EmojiCompat?> = mutableInstanceFlow

    fun init(context: Context) {
        val config = DefaultEmojiCompatConfig.create(context)?.apply {
            setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
        } ?: return
        val instance = EmojiCompat.init(config)
        instance.registerInitCallback(object : EmojiCompat.InitCallback() {
            override fun onInitialized() {
                flogInfo { "EmojiCompat successfully loaded" }
                mutableInstanceFlow.value = instance
            }

            override fun onFailed(throwable: Throwable?) {
                val errorType = throwable?.javaClass?.simpleName ?: "unknown"
                flogError { "EmojiCompat failed to load: error=$errorType" }
            }
        })
        // The default configuration queues font and metadata work on its background executor.
        instance.load()
    }
}
