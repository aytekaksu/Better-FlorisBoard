/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
