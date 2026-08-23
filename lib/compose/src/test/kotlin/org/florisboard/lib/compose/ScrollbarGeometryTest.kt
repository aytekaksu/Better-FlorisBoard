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

package org.florisboard.lib.compose

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.LayoutDirection
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrollbarGeometryTest {
    @Test
    fun `thumb follows the scroll range from start through end`() {
        assertGeometry(0f, 30f, scrollOffset = 0)
        assertGeometry(45f, 30f, scrollOffset = 150)
        assertGeometry(90f, 30f, scrollOffset = 300)
    }

    @Test
    fun `invalid unknown and non-scrollable measurements have no thumb`() {
        assertNull(calculateScrollbarGeometry(0f, 400, 100, 0))
        assertNull(calculateScrollbarGeometry(-1f, 400, 100, 0))
        assertNull(calculateScrollbarGeometry(Float.NaN, 400, 100, 0))
        assertNull(calculateScrollbarGeometry(Float.POSITIVE_INFINITY, 400, 100, 0))
        assertNull(calculateScrollbarGeometry(120f, 0, 100, 0))
        assertNull(calculateScrollbarGeometry(120f, -1, 100, 0))
        assertNull(calculateScrollbarGeometry(120f, 400, 0, 0))
        assertNull(calculateScrollbarGeometry(120f, 400, -1, 0))
        assertNull(calculateScrollbarGeometry(120f, 100, 100, 0))
        assertNull(calculateScrollbarGeometry(120f, 99, 100, 0))
        assertNull(calculateScrollbarGeometry(120f, Int.MAX_VALUE, 100, 0))
        assertNull(calculateScrollbarGeometry(120f, 400, Int.MAX_VALUE, 0))
        assertNull(calculateScrollbarGeometry(120f, 400, 100, Int.MAX_VALUE))
    }

    @Test
    fun `out of range offsets are clamped`() {
        assertGeometry(0f, 30f, scrollOffset = -1)
        assertGeometry(90f, 30f, scrollOffset = 301)
    }

    @Test
    fun `large and changing estimates stay finite`() {
        val large = assertNotNull(
            calculateScrollbarGeometry(
                trackLength = 1_000_000f,
                contentSize = Int.MAX_VALUE - 1,
                viewportSize = 1_000_000,
                scrollOffset = 1_000_000_000,
            ),
        )
        assertTrue(large.offset.isFinite())
        assertTrue(large.length.isFinite())

        val first = assertNotNull(calculateScrollbarGeometry(100f, 1_000, 100, 450))
        val updated = assertNotNull(calculateScrollbarGeometry(100f, 500, 100, 200))
        assertEquals(10f, first.length, TOLERANCE)
        assertEquals(20f, updated.length, TOLERANCE)
        assertEquals(45f, first.offset, TOLERANCE)
        assertEquals(40f, updated.offset, TOLERANCE)
    }

    @Test
    fun `horizontal rtl maps visual start to the right edge`() {
        val start = assertNotNull(calculateScrollbarGeometry(120f, 400, 100, 0))
        val end = assertNotNull(calculateScrollbarGeometry(120f, 400, 100, 300))

        assertEquals(0f, start.physicalOffset(120f, Orientation.Horizontal, LayoutDirection.Ltr), TOLERANCE)
        assertEquals(90f, start.physicalOffset(120f, Orientation.Horizontal, LayoutDirection.Rtl), TOLERANCE)
        assertEquals(0f, start.physicalOffset(120f, Orientation.Vertical, LayoutDirection.Rtl), TOLERANCE)
        assertEquals(0f, end.physicalOffset(120f, Orientation.Horizontal, LayoutDirection.Rtl), TOLERANCE)
    }

    private fun assertGeometry(expectedOffset: Float, expectedLength: Float, scrollOffset: Int) {
        val geometry = assertNotNull(calculateScrollbarGeometry(120f, 400, 100, scrollOffset))
        assertEquals(expectedOffset, geometry.offset, TOLERANCE)
        assertEquals(expectedLength, geometry.length, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
