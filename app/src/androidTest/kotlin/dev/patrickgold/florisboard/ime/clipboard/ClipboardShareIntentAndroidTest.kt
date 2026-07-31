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

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardShareIntentAndroidTest {
    @Test
    fun readsHeaderStreamAndFlagsFromAValidShare() {
        val source = Uri.parse("content://external.example/image/1")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, source)
        }

        assertEquals(
            ClipboardShareIntentHeader(Intent.ACTION_SEND, "image/png"),
            intent.clipboardShareHeaderOrNull(),
        )
        assertEquals(
            ClipboardShareIntentStream(source, Intent.FLAG_GRANT_READ_URI_PERMISSION),
            intent.clipboardShareStreamOrNull(),
        )
    }

    @Test
    fun rejectsWrongExtraTypeWithoutThrowing() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, "not a URI")
        }

        assertNull(intent.clipboardShareStreamOrNull()?.uri)
    }

    @Test
    fun rejectsRuntimeFailuresFromIntentAccessors() {
        assertNull(ThrowingActionIntent().clipboardShareHeaderOrNull())
        assertNull(ThrowingTypeIntent().clipboardShareHeaderOrNull())
        assertNull(ThrowingExtraIntent().clipboardShareStreamOrNull())
        assertNull(ThrowingFlagsIntent().clipboardShareStreamOrNull())
    }

    @Test
    fun streamSummaryDoesNotRevealTheForeignUri() {
        val marker = "private-provider-marker"
        val stream = ClipboardShareIntentStream(
            uri = Uri.parse("content://external.example/$marker"),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )

        assertFalse(stream.toString().contains(marker))
        assertTrue(stream.toString().contains("hasUri=true"))
        assertTrue(stream.toString().contains("hasReadGrant=true"))
    }

    private class ThrowingActionIntent : Intent() {
        override fun getAction(): String? = throw IllegalStateException("malformed action")
    }

    private class ThrowingTypeIntent : Intent() {
        override fun getType(): String? = throw IllegalStateException("malformed type")
    }

    private class ThrowingExtraIntent : Intent() {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun <T : Parcelable?> getParcelableExtra(name: String?): T? {
            throw IllegalStateException("malformed extra")
        }

        override fun <T : Any> getParcelableExtra(name: String?, clazz: Class<T>): T? {
            throw IllegalStateException("malformed extra")
        }
    }

    private class ThrowingFlagsIntent : Intent() {
        override fun getFlags(): Int = throw IllegalStateException("malformed flags")
    }
}
