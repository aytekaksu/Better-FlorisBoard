/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.android

typealias AndroidClipboardManager = android.content.ClipboardManager

fun AndroidClipboardManager.clearPrimaryClipAnyApi() {
    if (AndroidVersion.ATLEAST_API28_P) {
        this.clearPrimaryClip()
    } else {
        this.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
    }
}

fun AndroidClipboardManager.setOrClearPrimaryClip(clip: android.content.ClipData?) {
    if (clip != null) {
        this.setPrimaryClip(clip)
    } else {
        this.clearPrimaryClipAnyApi()
    }
}
