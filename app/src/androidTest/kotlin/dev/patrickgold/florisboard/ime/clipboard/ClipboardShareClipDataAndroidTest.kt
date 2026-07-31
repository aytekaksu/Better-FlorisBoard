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

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardShareClipDataAndroidTest {
    @Test
    fun preservesOwnedUriAndExplicitMimeTypes() {
        val owned = Uri.parse(
            "content://dev.patrickgold.florisboard.provider.clipboard/clips/images/42",
        )

        val clip = clipboardShareClipData(
            uri = owned,
            mimeTypes = listOf("image/png", "image/jpeg"),
        )

        assertEquals(owned, clip.getItemAt(0).uri)
        assertEquals(2, clip.description.mimeTypeCount)
        assertEquals("image/png", clip.description.getMimeType(0))
        assertEquals("image/jpeg", clip.description.getMimeType(1))
    }
}
