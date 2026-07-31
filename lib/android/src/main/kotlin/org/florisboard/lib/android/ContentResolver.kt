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
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Shorthand function for querying a Uri without any other arguments.
 *
 * @see android.content.ContentResolver.query
 */
inline fun ContentResolver.query(uri: Uri) = this.query(uri, null, null, null, null)

/**
 * Shorthand function for querying a Uri and projection without any other arguments.
 *
 * @see android.content.ContentResolver.query
 */
inline fun ContentResolver.query(uri: Uri, projection: Array<String>) = this.query(uri, projection, null, null, null)

inline fun ContentResolver.read(uri: Uri, maxSize: Long = Long.MAX_VALUE, block: (InputStream) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    require(maxSize > 0) { "Argument `maxSize` must be greater than 0" }
    val inputStream = this.openInputStream(uri)
        ?: throw ContentReadException()
    inputStream.use {
        block(SizeLimitedInputStream(it, maxSize))
    }
}

fun ContentResolver.readToFile(
    uri: Uri,
    file: FsFile,
    maxSize: Long = Long.MAX_VALUE,
): Long {
    var copiedSize = 0L
    val deleteOnFailure = !file.exists()
    try {
        this.read(uri, maxSize) { inStream ->
            file.outputStream().use { outStream ->
                copiedSize = inStream.copyTo(outStream)
            }
        }
        return copiedSize
    } catch (error: Throwable) {
        if (deleteOnFailure) file.delete()
        throw error
    }
}

inline fun ContentResolver.readText(uri: Uri, block: (BufferedReader) -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    this.read(uri) { inStream ->
        inStream.bufferedReader().use(block)
    }
}

inline fun ContentResolver.readAllText(uri: Uri): String {
    val text: String
    this.read(uri) { inStream ->
        text = inStream.bufferedReader().use { it.readText() }
    }
    return text
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

inline fun ContentResolver.writeAllText(uri: Uri, text: String) {
    this.write(uri) { outStream ->
        outStream.bufferedWriter().use { it.write(text) }
    }
}

class ContentReadException : IOException("Unable to read selected content.")

class ContentSizeLimitExceededException :
    IOException("Selected content exceeds the allowed size.")

@PublishedApi
internal class SizeLimitedInputStream(
    inputStream: InputStream,
    private val maxSize: Long,
) : FilterInputStream(inputStream) {
    private var readSize = 0L

    override fun read(): Int {
        return super.read().also { value ->
            if (value >= 0) recordRead(1)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = maxSize - readSize
        val allowedRead = if (remaining >= length) length else (remaining + 1).toInt()
        return super.read(buffer, offset, allowedRead).also { count ->
            if (count > 0) recordRead(count)
        }
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0) return 0
        var remaining = byteCount
        var skipped = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            remaining -= count
            skipped += count
        }
        return skipped
    }

    override fun markSupported() = false

    override fun mark(readLimit: Int) = Unit

    override fun reset(): Nothing = throw IOException("Stream reset is not supported.")

    private fun recordRead(count: Int) {
        if (readSize > maxSize - count) {
            throw ContentSizeLimitExceededException()
        }
        readSize += count
    }
}
