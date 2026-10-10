/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardItemAndroidTest {
    @Test
    fun sensitiveMetadataRoundTripsAndParticipatesInEquality() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isRemote = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val item = ClipboardItem(
            type = ItemType.TEXT,
            text = "private marker",
            uri = null,
            creationTimestampMs = System.currentTimeMillis(),
            isPinned = false,
            mimeTypes = listOf("text/plain"),
            isSensitive = true,
            isRemoteDevice = isRemote,
        )

        val clipData = requireNotNull(item.toClipData(context))
        assertTrue(
            clipData.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
        )
        if (isRemote) {
            assertTrue(
                clipData.description.extras?.getBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE) ==
                    true,
            )
        }
        val restored = (
            ClipboardItem.planFromClipData(context, clipData) as ClipboardItemImportPlan.Ready
            ).item
        assertTrue(restored.isSensitive)
        assertTrue(restored.isRemoteDevice == isRemote)
        assertTrue(item.isEqualTo(context, clipData))

        val unmarked = ClipData.newPlainText("test", "private marker")
        assertFalse(item.isEqualTo(context, unmarked))
        assertFalse(item.toString().contains("private marker"))
    }
}
