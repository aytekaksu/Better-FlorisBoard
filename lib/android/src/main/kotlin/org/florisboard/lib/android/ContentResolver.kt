/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
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
    val outputStream = this.openOutputStream(uri, "wt")
        ?: error("Cannot open input stream for given uri '$uri'")
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
