/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.autocorrect.api

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutocorrectPluginUiTest {
    @Test
    fun externalLinkTargetAtLimitSurvivesRoundTrip() {
        val target = "https://example.com/" + "a".repeat(236)
        assertEquals(256, target.length)
        assertEquals(target, roundTrip(AutocorrectPluginUiItemKind.EXTERNAL_LINK, target))
    }

    @Test
    fun overlongExternalLinkTargetIsDiscarded() {
        val target = "https://example.com/" + "a".repeat(237)
        assertNull(roundTrip(AutocorrectPluginUiItemKind.EXTERNAL_LINK, target))
    }

    @Test
    fun overlongNavigationTargetRemainsBackwardCompatible() {
        val target = "a".repeat(257)
        assertEquals(target.take(256), roundTrip(AutocorrectPluginUiItemKind.NAVIGATION, target))
    }

    @Test
    @Suppress("DEPRECATION")
    fun documentMimeTypesKeepValidV5OrderAndBoundRawInput() {
        val types = (0 until 8).map { "application/x-$it" }
        val encoded = singleItemReply(
            AutocorrectPluginUiItem(
                id = "document",
                kind = AutocorrectPluginUiItemKind.DOCUMENT_IMPORT,
                title = "Document",
                documentMimeTypes = types,
            ),
        )
        assertEquals(types, decodedDocumentMimeTypes(encoded))

        val item = encoded.getBundle("ui")!!
            .getParcelableArrayList<Bundle>("pages")!![0]
            .getParcelableArrayList<Bundle>("items")!![0]
        item.putStringArrayList(
            "documentMimeTypes",
            ArrayList(types + List(1_000) { "application/ignored-$it" }),
        )
        assertEquals(types, decodedDocumentMimeTypes(encoded))
    }

    @Test
    fun malformedNestedPageListFailsBeforeItCanBecomeAnEmptyUi() {
        val reply = Bundle().apply {
            putLong("requestId", 1L)
            putBoolean("successful", true)
            putBundle(
                "ui",
                Bundle().apply {
                    putStringArrayList("pages", arrayListOf("not-a-page"))
                },
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            pluginUiResultFromBundle(reply)
        }
    }

    @Test
    fun wrongTypedUiAndMissingPagesCannotReplaceTheCurrentUi() {
        val reply = Bundle().apply {
            putLong("requestId", 1L)
            putBoolean("successful", true)
            putString("ui", "not-a-ui")
        }
        assertThrows(IllegalArgumentException::class.java) {
            pluginUiResultFromBundle(reply)
        }
        reply.putBundle("ui", Bundle().apply { putString("appRoot", "root") })
        assertThrows(IllegalArgumentException::class.java) {
            pluginUiResultFromBundle(reply)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun wrongTypedDocumentMimeTypesCannotWidenThePicker() {
        val reply = singleItemReply(
            AutocorrectPluginUiItem(
                id = "document",
                kind = AutocorrectPluginUiItemKind.DOCUMENT_IMPORT,
                title = "Document",
                documentMimeTypes = listOf("application/json"),
            ),
        )
        val item = reply.getBundle("ui")!!
            .getParcelableArrayList<Bundle>("pages")!![0]
            .getParcelableArrayList<Bundle>("items")!![0]
        item.putString("documentMimeTypes", "not-a-list")
        assertThrows(IllegalArgumentException::class.java) {
            pluginUiResultFromBundle(reply)
        }
    }

    private fun decodedDocumentMimeTypes(bundle: Bundle) = pluginUiResultFromBundle(bundle)
        .ui!!.pages.single().items.single().documentMimeTypes

    private fun roundTrip(kind: AutocorrectPluginUiItemKind, target: String): String? {
        val reply = singleItemReply(
            AutocorrectPluginUiItem(
                id = "target",
                kind = kind,
                title = "Target",
                target = target,
            ),
        )
        return pluginUiResultFromBundle(reply).ui?.pages?.single()?.items?.single()?.target
    }

    private fun singleItemReply(item: AutocorrectPluginUiItem) = pluginUiResultBundle(
        1L,
        true,
        AutocorrectPluginUi(
            appRootPageId = "root",
            keyboardRootPageId = null,
            pages = listOf(AutocorrectPluginUiPage(id = "root", title = "Root", items = listOf(item))),
        ),
    )
}
