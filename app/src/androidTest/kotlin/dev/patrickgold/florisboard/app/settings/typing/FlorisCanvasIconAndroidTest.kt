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

package dev.patrickgold.florisboard.app.settings.typing

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.R
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.florisboard.lib.compose.FlorisCanvasIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlorisCanvasIconAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun drawableRasterizationIsRememberedUntilDrawableOrConfigurationChanges() {
        val first = CountingDrawable()
        val second = CountingDrawable()
        var drawable by mutableStateOf<Drawable>(first)
        var description by mutableIntStateOf(0)
        var configuration by mutableStateOf(Configuration())
        var show by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                if (show) FlorisCanvasIcon(drawable, contentDescription = description.toString())
            }
        }

        composeRule.runOnIdle { assertEquals(1, first.draws.get()) }
        composeRule.runOnIdle { description++ }
        composeRule.runOnIdle { assertEquals(1, first.draws.get()) }
        composeRule.runOnIdle { drawable = second }
        composeRule.runOnIdle { assertEquals(1, second.draws.get()) }
        composeRule.runOnIdle {
            configuration = Configuration(configuration).apply { densityDpi++ }
        }
        composeRule.runOnIdle { assertEquals(2, second.draws.get()) }
        composeRule.runOnIdle { show = false }
        composeRule.runOnIdle { show = true }
        composeRule.runOnIdle { assertEquals(3, second.draws.get()) }
    }

    @Test
    fun adaptiveResourceIconStillRenders() {
        composeRule.setContent { FlorisCanvasIcon(R.mipmap.floris_app_icon) }
        composeRule.waitForIdle()
    }

    @Test
    fun rasterSizeIsBoundedAndInvalidIntrinsicSizeUsesFallback() {
        val oversized = CountingDrawable(4000, 1000).apply { setBounds(4, 5, 10, 11) }
        var drawable by mutableStateOf<Drawable>(oversized)
        composeRule.setContent { FlorisCanvasIcon(drawable) }

        composeRule.runOnIdle {
            assertEquals(512 to 128, oversized.lastCanvasSize.get())
            assertEquals(Rect(4, 5, 10, 11), oversized.bounds)
        }
        val invalid = CountingDrawable(-1, -1)
        composeRule.runOnIdle { drawable = invalid }
        composeRule.runOnIdle {
            val (width, height) = invalid.lastCanvasSize.get()
            assertTrue(width in 1..512)
            assertEquals(width, height)
        }
    }

    private class CountingDrawable(
        private val intrinsicWidth: Int = 8,
        private val intrinsicHeight: Int = 8,
    ) : Drawable() {
        val draws = AtomicInteger()
        val lastCanvasSize = AtomicReference<Pair<Int, Int>>()

        override fun draw(canvas: Canvas) {
            draws.incrementAndGet()
            lastCanvasSize.set(canvas.width to canvas.height)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = intrinsicWidth
        override fun getIntrinsicHeight() = intrinsicHeight
    }
}
