/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import java.nio.charset.StandardCharsets
import java.nio.file.Files

internal fun ArchiveSnapshot.patchCentral(
    entryName: String,
    patch: (bytes: ByteArray, offset: Int) -> Unit,
): ArchiveSnapshot {
    val bytes = Files.readAllBytes(path)
    patch(bytes, bytes.findCentralEntry(entryName))
    Files.write(path, bytes)
    return ArchiveSnapshot(path, Files.size(path))
}

internal fun ByteArray.findCentralEntry(entryName: String): Int {
    var offset = findSignature(CENTRAL_SIGNATURE)
    while (offset >= 0 && u32(offset) == CENTRAL_SIGNATURE) {
        val nameBytes = u16(offset + CENTRAL_NAME_LENGTH_OFFSET)
        val extraBytes = u16(offset + CENTRAL_EXTRA_LENGTH_OFFSET)
        val commentBytes = u16(offset + CENTRAL_COMMENT_LENGTH_OFFSET)
        val actualName = String(this, offset + CENTRAL_HEADER_BYTES, nameBytes, StandardCharsets.UTF_8)
        if (actualName == entryName) return offset
        offset += CENTRAL_HEADER_BYTES + nameBytes + extraBytes + commentBytes
    }
    error("Missing fixture entry.")
}

private fun ByteArray.findSignature(signature: Long): Int {
    for (index in 0..size - Int.SIZE_BYTES) {
        if (u32(index) == signature) return index
    }
    return -1
}

internal fun ByteArray.u16(offset: Int): Int = (this[offset].toInt() and 0xff) or
    ((this[offset + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

internal fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

internal fun ByteArray.putU32(offset: Int, value: Long) {
    putU16(offset, value.toInt())
    putU16(offset + 2, (value ushr 16).toInt())
}

private const val CENTRAL_SIGNATURE = 0x02014b50L
private const val CENTRAL_HEADER_BYTES = 46
private const val CENTRAL_NAME_LENGTH_OFFSET = 28
private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30
private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32
