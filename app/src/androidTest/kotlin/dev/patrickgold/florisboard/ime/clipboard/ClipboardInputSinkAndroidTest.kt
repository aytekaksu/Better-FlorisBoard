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

import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        var editorCommits = 0
        var textResult: Boolean? = null
        val fakeSink = object : ClipboardInputSink {
            override fun dispatchPaste(action: () -> Unit) {
                textDispatches++
                action()
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
            }) { item, access ->
                assertEquals(ItemType.TEXT, item.type)
                assertEquals(null, access)
                editorCommits++
                true
            }
        }
        try {
            assertEquals(0, sinkResolutions)
            assertEquals(0, editorCommits)
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
                manager.pasteItem(ClipboardItem.text("synthetic paste")) { textResult = it }
                manager.pasteItem(image)
            }

            assertEquals(1, sinkResolutions)
            assertEquals(1, textDispatches)
            assertEquals(1, mediaDeferrals)
            assertEquals(1, editorCommits)
            assertEquals(true, textResult)
        } finally {
            manager.close()
        }
    }

    @Test
    fun mimeEligibilityFollowsTheCurrentEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val editor = EditorInstance(instrumentation.targetContext)
        val text = ClipboardItem.text("synthetic paste")
        val image = ClipboardItem(
            type = ItemType.IMAGE,
            text = null,
            uri = requireNotNull(OwnedClipboardMediaUri.create(1L, ItemType.IMAGE)).uri,
            creationTimestampMs = 1L,
            isPinned = false,
            mimeTypes = listOf("image/png"),
        )
        val video = image.copy(type = ItemType.VIDEO, mimeTypes = listOf("video/mp4"))

        instrumentation.runOnMainSync {
            assertFalse(editor.canPaste(null))
            assertTrue(editor.canPaste(text))
            assertFalse(editor.canPaste(image))

            editor.handleStartInput(FlorisEditorInfo.wrap(EditorInfo().apply {
                packageName = "test.image.editor"
                contentMimeTypes = arrayOf("image/*")
            }))
            assertTrue(editor.canPaste(image))
            assertFalse(editor.canPaste(video))

            editor.handleStartInput(FlorisEditorInfo.wrap(EditorInfo().apply {
                packageName = "test.video.editor"
                contentMimeTypes = arrayOf("video/*")
            }))
            assertFalse(editor.canPaste(image))
            assertTrue(editor.canPaste(video))
        }
    }
}
