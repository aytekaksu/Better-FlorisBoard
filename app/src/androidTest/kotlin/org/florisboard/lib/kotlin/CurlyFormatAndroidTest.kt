/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.kotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurlyFormatAndroidTest {
    @Test
    fun formatsWithAndroidRegexEngine() {
        assertEquals("Hello Android", "Hello {name}".curlyFormat("name" to "Android"))
        assertEquals("{self}", "{self}".curlyFormat("self" to "{self}"))
    }
}
