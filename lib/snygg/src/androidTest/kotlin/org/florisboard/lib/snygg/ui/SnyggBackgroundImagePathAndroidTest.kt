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

package org.florisboard.lib.snygg.ui

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.florisboard.lib.snygg.value.SnyggAssetResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class SnyggBackgroundImagePathAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun switchingResolverOrUriDropsTheOldPathAndCancelsObsoleteWork() {
        val old = TestResolver("/old")
        val held = TestResolver("/held", CountDownLatch(1))
        val current = TestResolver("/current")
        val uri = mutableStateOf<String?>("flex:/image")
        val resolver = mutableStateOf<SnyggAssetResolver>(old)

        compose.setContent {
            Text(rememberBackgroundImagePath(uri.value, resolver.value) ?: "no image")
        }
        try {
            compose.waitForText("/old")

            // The URI is unchanged, but a new theme supplies a different resolver.
            compose.runOnUiThread { resolver.value = held }
            compose.waitUntil(5_000) { held.started.count == 0L }
            compose.onNodeWithText("no image").assertExists()
            compose.onNodeWithText("/old").assertDoesNotExist()

            compose.runOnUiThread {
                uri.value = "flex:/next"
                resolver.value = current
            }
            compose.waitForText("/current")
            assertTrue("obsolete resolution was not interrupted", held.finished.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("/held").assertDoesNotExist()

            compose.runOnUiThread { uri.value = null }
            compose.onNodeWithText("no image").assertExists()
            assertFalse("path resolution ran on Main", old.ranOnMain.get())
            assertFalse("path resolution ran on Main", held.ranOnMain.get())
            assertFalse("path resolution ran on Main", current.ranOnMain.get())
        } finally {
            held.release()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForText(text: String) {
        waitUntil(5_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private class TestResolver(
        private val path: String,
        private val gate: CountDownLatch? = null,
    ) : SnyggAssetResolver {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val ranOnMain = AtomicBoolean(false)

        override fun resolveAbsolutePath(uri: String): Result<String> {
            ranOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            started.countDown()
            try {
                gate?.await()
            } catch (_: InterruptedException) {
                // Return anyway: the obsolete coroutine must not publish this result.
            } finally {
                finished.countDown()
            }
            return Result.success(path)
        }

        fun release() {
            gate?.countDown()
        }
    }
}
