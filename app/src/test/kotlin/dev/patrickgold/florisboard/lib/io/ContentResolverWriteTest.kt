/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.io

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.florisboard.lib.android.write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ContentResolverWriteTest {
    @Test
    fun nullOutputStreamHasContentFreeFailure() {
        val authority = "synthetic.writer"
        val marker = "synthetic-destination-marker"
        val uri = Uri.parse("content://$authority/$marker")
        val controller = Robolectric.buildContentProvider(NullOutputProvider::class.java).create(authority)
        try {
            val provider = controller.get()
            var writerCalls = 0
            val failure = assertThrows(IllegalStateException::class.java) {
                RuntimeEnvironment.getApplication().contentResolver.write(uri) { writerCalls++ }
            }
            assertEquals("fixture must reach the provider", 1, provider.outputRequests)
            assertEquals("wt", provider.lastMode)
            assertEquals("null output must not invoke the writer", 0, writerCalls)
            val message = failure.message.orEmpty()
            assertFalse(
                "null-stream failure exposed destination identity",
                message.contains(authority) || message.contains(marker),
            )
        } finally {
            controller.shutdown()
        }
    }

    class NullOutputProvider : ContentProvider() {
        var outputRequests = 0
            private set
        var lastMode: String? = null
            private set

        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            outputRequests++
            lastMode = mode
            return null
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = error("Unexpected provider query")

        override fun getType(uri: Uri): String? = error("Unexpected provider type lookup")
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected provider insert")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Unexpected provider delete")
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = error("Unexpected provider update")
    }
}
