/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.about

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.R
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.florisboard.lib.android.stringRes
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectLicenseScreenAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun assetReadStaysOffMainAndDoesNotRepeatOnRecomposition() {
        val reads = AtomicInteger()
        val readThread = AtomicReference<Thread>()
        var reader by mutableStateOf<(Context) -> Result<String>>({
            reads.incrementAndGet()
            readThread.set(Thread.currentThread())
            Result.success("License text")
        })
        composeRule.setContent { ProjectLicenseText(LocalContext.current, reader) }

        waitForText("License text")
        assertNotSame(Looper.getMainLooper().thread, readThread.get())
        composeRule.runOnIdle {
            reader = {
                reads.incrementAndGet()
                Result.success("Unexpected second read")
            }
        }
        composeRule.waitForIdle()

        assertEquals(1, reads.get())
        composeRule.onNodeWithText("License text").assertIsDisplayed()
    }

    @Test
    fun disposedReadCannotReplaceContentAfterReentry() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val reads = AtomicInteger()
        var show by mutableStateOf(true)
        try {
            composeRule.setContent {
                if (show) {
                    ProjectLicenseText(LocalContext.current) {
                        if (reads.incrementAndGet() == 1) {
                            started.countDown()
                            while (true) {
                                try {
                                    check(release.await(10, TimeUnit.SECONDS)) {
                                        "Timed out waiting to release the read"
                                    }
                                    break
                                } catch (_: InterruptedException) {
                                    // Model a read which finishes despite cancellation.
                                }
                            }
                            Result.success("Stale license").also { finished.countDown() }
                        } else {
                            Result.success("Current license")
                        }
                    }
                }
            }

            assertTrue(started.await(10, TimeUnit.SECONDS))
            composeRule.runOnIdle { show = false }
            composeRule.waitForIdle()
            composeRule.runOnIdle { show = true }

            waitForText("Current license")
            assertEquals(2, reads.get())
            assertEquals(1L, finished.count)
            release.countDown()
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Current license").assertIsDisplayed()
            composeRule.onNodeWithText("Stale license").assertDoesNotExist()
        } finally {
            release.countDown()
        }
    }

    @Test
    fun packagedLicenseIsDisplayed() {
        val packagedLicense = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("license/project_license.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        composeRule.setContent { ProjectLicenseText(LocalContext.current) }
        waitForText(packagedLicense)
    }

    @Test
    fun localizedReadFailureIsDisplayed() {
        lateinit var context: Context
        composeRule.setContent {
            context = LocalContext.current
            ProvideLocalizedResources(context, R.string.app_name) {
                ProjectLicenseText(context) { Result.failure(IOException("Synthetic failure")) }
            }
        }
        val expectedError = context.stringRes(
            R.string.about__project_license__error_license_text_failed,
            "error_message" to "Synthetic failure",
        )
        waitForText(expectedError)
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
