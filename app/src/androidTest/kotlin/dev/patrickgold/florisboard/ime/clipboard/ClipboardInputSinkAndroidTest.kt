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

package dev.patrickgold.florisboard.ime.clipboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardInputSinkAndroidTest {
    @Test
    fun constructionDefersSinkAndBothPasteTypesUseTheInjectedSink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var sinkResolutions = 0
        var textDispatches = 0
        var mediaDeferrals = 0
        val fakeSink = object : ClipboardInputSink {
            override fun dispatchPaste(action: () -> Unit) {
                textDispatches++
            }

            override fun deferMediaPaste(
                onLaterInputQueued: () -> Unit,
                onInvalidated: () -> Unit,
                start: ((() -> Unit) -> Unit),
            ) {
                mediaDeferrals++
            }
        }
        lateinit var manager: ClipboardManager
        instrumentation.runOnMainSync {
            manager = ClipboardManager(context, lazy {
                sinkResolutions++
                fakeSink
            })
        }
        try {
            assertEquals(0, sinkResolutions)
            val owned = requireNotNull(OwnedClipboardMediaUri.create(1L, ItemType.IMAGE))
            val image = ClipboardItem(
                type = ItemType.IMAGE,
                text = null,
                uri = owned.uri,
                creationTimestampMs = 1L,
                isPinned = false,
                mimeTypes = listOf("image/png"),
            )
            instrumentation.runOnMainSync {
                manager.pasteItem(ClipboardItem.text("synthetic paste"))
                manager.pasteItem(image)
            }

            assertEquals(1, sinkResolutions)
            assertEquals(1, textDispatches)
            assertEquals(1, mediaDeferrals)
        } finally {
            manager.close()
        }
    }
}
