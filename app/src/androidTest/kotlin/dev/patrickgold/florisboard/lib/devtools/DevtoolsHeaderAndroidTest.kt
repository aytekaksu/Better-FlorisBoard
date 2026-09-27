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

package dev.patrickgold.florisboard.lib.devtools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.PreferenceStoreInitializationState
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevtoolsHeaderAndroidTest {
    @Before
    fun awaitPreferenceStore(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<FlorisApplication>()
        assertEquals(
            PreferenceStoreInitializationState.READY,
            withTimeout(20_000L) {
                app.preferenceStoreInitializationState.first {
                    it != PreferenceStoreInitializationState.LOADING
                }
            },
        )
    }

    @Test
    fun reportsKeepTheirReadableSections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs by FlorisPreferenceStore
        val sections = listOf("SYSTEM INFO", "APP INFO", "EXTENSION CONFIG", "ACTIVE SUBTYPE CONFIG")
        assertSections(Devtools.generateDebugLog(context), sections)
        assertSections(
            Devtools.generateDebugLog(context, prefs),
            sections.take(2) + "FEATURE CONFIG" + sections.drop(2),
        )
    }

    private fun assertSections(report: String, expected: List<String>) {
        val headings = Regex("^======= (.+) =======$", RegexOption.MULTILINE)
            .findAll(report).map { it.groupValues[1] }.toList()
        assertEquals(expected, headings)
        assertEquals(expected.size - 1, Regex("\n\n======= [A-Z ]+ =======\n").findAll(report).count())
        assertFalse(report.contains("\n\n\n======="))
        assertTrue(report.endsWith("\n"))
    }
}
