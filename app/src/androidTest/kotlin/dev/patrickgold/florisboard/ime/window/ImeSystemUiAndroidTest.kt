/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.window

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.test.EditorHarnessActivity
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeSystemUiAndroidTest {
    @Test
    fun nestedContextWrappersResolveTheActivityWindow() {
        ActivityScenario.launch(EditorHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertSame(activity.window, activity.findWindow())
                assertSame(activity.window, ContextWrapper(ContextWrapper(activity)).findWindow())
            }
        }
    }

    @Test
    fun contextWithoutAWindowReturnsNull() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertNull(ContextWrapper(context).findWindow())
    }
}
