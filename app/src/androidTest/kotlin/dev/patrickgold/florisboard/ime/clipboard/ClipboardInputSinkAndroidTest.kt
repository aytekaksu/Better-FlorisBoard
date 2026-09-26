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

import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardFileStorage
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.InstalledClipboardMedia
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import dev.patrickgold.florisboard.ime.clipboard.provider.OwnedClipboardMediaUri
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.ObservableKeyboardState
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardInputSinkAndroidTest {
    @Test
    fun editorMimeIdentityUsesOrderedValuesAndSnapshotsTheArray() {
        fun info(vararg types: String) = FlorisEditorInfo.wrap(EditorInfo().apply {
            contentMimeTypes = types
        })
        val source = EditorInfo().apply {
            contentMimeTypes = arrayOf("image/*", "video/*")
        }
        val first = FlorisEditorInfo.wrap(source)
        val equal = info("image/*", "video/*")

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, info("video/*", "image/*"))
        assertNotEquals(first, info("image/*"))
        assertNotEquals(FlorisEditorInfo.Unspecified, first)

        source.contentMimeTypes!![0] = "audio/*"
        assertEquals(listOf("image/*", "video/*"), first.contentMimeTypes)
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
    }

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
        val editor = EditorInstance(
            instrumentation.targetContext,
            lazy { ObservableKeyboardState.new() },
            lazy { error("MIME eligibility must not need the composing policy") },
        ) { }
        fun info(vararg types: String) = FlorisEditorInfo.wrap(EditorInfo().apply {
            packageName = "test.editor"
            contentMimeTypes = types
        })
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

            editor.handleStartInput(info("image/*"))
            assertTrue(editor.canPaste(image))
            assertFalse(editor.canPaste(video))

            editor.handleStartInput(info("video/*"))
            assertFalse(editor.canPaste(image))
            assertTrue(editor.canPaste(video))
        }
    }

    @Test
    fun mimeOnlyEditorSwitchRefreshesComputedClipboardKeys() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clipboard by context.clipboardManager()
        val editor by context.editorInstance()
        val keyboard by context.keyboardManager()
        val prefs by FlorisPreferenceStore
        runBlocking { withTimeout(10_000L) { clipboard.awaitInitialization() } }
        val originalClip = clipboard.primaryClip
        val originalInfo = editor.activeInfo
        val originalMode = keyboard.activeState.keyboardMode
        val originalInternalClipboard = prefs.clipboard.useInternalClipboard.get()
        val originalSyncToSystem = prefs.clipboard.syncToSystem.get()
        val source = Files.createTempFile(context.cacheDir.toPath(), "editor-mime-", ".bin")
        var installed: InstalledClipboardMedia? = null
        fun info(type: String) = FlorisEditorInfo.wrap(EditorInfo().apply {
            packageName = "test.editor"
            contentMimeTypes = arrayOf(type)
        })
        fun waitUntil(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10_000L
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(20L)
            }
            throw AssertionError(message)
        }

        val testResult = runCatching {
            Files.write(source, byteArrayOf(1))
            runBlocking {
                prefs.clipboard.useInternalClipboard.set(true).getOrThrow()
                prefs.clipboard.syncToSystem.set(ClipboardSyncBehavior.NO_EVENTS).getOrThrow()
            }
            installed = ClipboardFileStorage.installFromBackup(
                context = context,
                source = source,
                expectedBytes = 1L,
                type = ItemType.IMAGE,
                mimeTypes = listOf("image/png"),
            )
            val image = ClipboardItem(
                type = ItemType.IMAGE,
                text = null,
                uri = installed.ownedUri.uri,
                creationTimestampMs = System.currentTimeMillis(),
                isPinned = false,
                mimeTypes = listOf("image/png"),
            )
            instrumentation.runOnMainSync {
                keyboard.activeState.keyboardMode = KeyboardMode.CHARACTERS
            }
            clipboard.updatePrimaryClip(image)
            waitUntil("image clip did not become primary") {
                clipboard.primaryClip?.uri == image.uri
            }
            instrumentation.runOnMainSync { editor.handleStartInput(info("image/png")) }
            waitUntil("image editor did not reach the keyboard evaluator") {
                val evaluator = keyboard.activeEvaluator.value
                val smartbar = keyboard.activeSmartbarEvaluator.value
                evaluator.keyboard is TextKeyboard &&
                    evaluator.editorInfo.contentMimeTypes == listOf("image/png") &&
                    smartbar.version == evaluator.version
            }
            val paste = TextKey(TextKeyData.CLIPBOARD_PASTE)
            val clear = TextKey(TextKeyData.CLIPBOARD_CLEAR_PRIMARY_CLIP)
            val imageEvaluator = keyboard.activeEvaluator.value
            val imageSmartbar = keyboard.activeSmartbarEvaluator.value
            paste.compute(imageEvaluator)
            clear.compute(imageEvaluator)
            assertTrue(paste.isEnabled)
            assertTrue(clear.isEnabled)
            assertTrue(imageSmartbar.evaluateEnabled(TextKeyData.CLIPBOARD_PASTE))
            assertTrue(imageSmartbar.evaluateEnabled(TextKeyData.CLIPBOARD_CLEAR_PRIMARY_CLIP))

            instrumentation.runOnMainSync { editor.handleStartInput(info("video/mp4")) }
            waitUntil("MIME-only editor switch did not refresh the keyboard evaluator") {
                val evaluator = keyboard.activeEvaluator.value
                val smartbar = keyboard.activeSmartbarEvaluator.value
                evaluator.version > imageEvaluator.version &&
                    evaluator.editorInfo.contentMimeTypes == listOf("video/mp4") &&
                    smartbar.version == evaluator.version
            }
            val videoEvaluator = keyboard.activeEvaluator.value
            val videoSmartbar = keyboard.activeSmartbarEvaluator.value
            paste.compute(videoEvaluator)
            clear.compute(videoEvaluator)
            assertFalse(paste.isEnabled)
            assertFalse(clear.isEnabled)
            assertFalse(videoSmartbar.evaluateEnabled(TextKeyData.CLIPBOARD_PASTE))
            assertFalse(videoSmartbar.evaluateEnabled(TextKeyData.CLIPBOARD_CLEAR_PRIMARY_CLIP))
        }
        val editorRestore = runCatching {
            instrumentation.runOnMainSync {
                editor.handleStartInput(originalInfo)
                keyboard.activeState.keyboardMode = originalMode
            }
        }
        val clipRestore = runCatching {
            clipboard.updatePrimaryClip(originalClip)
            waitUntil("original primary clip was not restored") {
                clipboard.primaryClip == originalClip
            }
        }
        val prefsRestore = runCatching {
            runBlocking {
                try {
                    prefs.clipboard.syncToSystem.set(originalSyncToSystem).getOrThrow()
                } finally {
                    prefs.clipboard.useInternalClipboard.set(originalInternalClipboard).getOrThrow()
                }
            }
        }
        val mediaCleanup = runCatching {
            if (!clipRestore.isSuccess) return@runCatching
            installed?.ownedUri?.let { owned ->
                assertTrue(
                    ClipboardFileStorage.deleteOwned(context, owned) ||
                        ClipboardFileStorage.fileInfo(context, owned) == null,
                )
            }
        }
        runCatching { installed?.cleanup() }
        val sourceCleanup = runCatching { Files.deleteIfExists(source) }
        testResult.getOrThrow()
        editorRestore.getOrThrow()
        clipRestore.getOrThrow()
        prefsRestore.getOrThrow()
        mediaCleanup.getOrThrow()
        sourceCleanup.getOrThrow()
    }
}
