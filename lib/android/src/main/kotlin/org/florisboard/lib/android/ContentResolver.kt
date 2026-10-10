/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

@file:Suppress("NOTHING_TO_INLINE")

package org.florisboard.lib.android

import android.content.ContentResolver
import android.net.Uri
import org.florisboard.lib.kotlin.io.FsFile
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

inline fun ContentResolver.readText(uri: Uri, block: (BufferedReader) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val inputStream = this.openInputStream(uri) ?: throw ContentReadException()
    inputStream.bufferedReader().use(block)
}

inline fun ContentResolver.write(uri: Uri, block: (OutputStream) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val outputStream = this.openOutputStream(uri, "wt") ?: error("Unable to write selected content.")
    outputStream.use(block)
}

inline fun ContentResolver.writeFromFile(uri: Uri, file: FsFile) {
    this.write(uri) { outStream ->
        file.inputStream().use { inStream ->
            inStream.copyTo(outStream)
        }
    }
}

inline fun ContentResolver.writeText(uri: Uri, block: (BufferedWriter) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    this.write(uri) { outStream ->
        outStream.bufferedWriter().use(block)
    }
}

class ContentReadException : IOException("Unable to read selected content.")
