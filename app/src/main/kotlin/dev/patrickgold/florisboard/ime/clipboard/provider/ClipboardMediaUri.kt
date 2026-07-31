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

package dev.patrickgold.florisboard.ime.clipboard.provider

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri

/**
 * An inert reference from a backup archive.
 *
 * Parsing deliberately uses only strings. Archive-controlled input must never
 * become an Android [Uri], because a [Uri] can later be mistaken for a live
 * capability and passed to a content resolver.
 */
internal class ArchiveClipboardMediaRef private constructor(
    val sourceId: Long,
    val type: ItemType,
) {
    override fun equals(other: Any?): Boolean =
        other is ArchiveClipboardMediaRef && sourceId == other.sourceId && type == other.type

    override fun hashCode(): Int = 31 * sourceId.hashCode() + type.hashCode()

    override fun toString(): String = "ArchiveClipboardMediaRef(type=$type, sourceId=<redacted>)"

    companion object {
        fun parse(
            raw: String,
            sourceAuthority: String,
            expectedType: ItemType,
        ): ArchiveClipboardMediaRef? {
            val sourceId = parseCanonicalClipboardMediaId(raw, sourceAuthority, expectedType) ?: return null
            return ArchiveClipboardMediaRef(sourceId, expectedType)
        }
    }
}

/**
 * An exact URI owned by this installed FlorisBoard application.
 *
 * Only instances of this class should reach clipboard-media resolver, grant,
 * preview, or backup operations.
 */
internal class OwnedClipboardMediaUri private constructor(
    val id: Long,
    val type: ItemType,
    val uri: Uri,
) {
    override fun equals(other: Any?): Boolean =
        other is OwnedClipboardMediaUri && id == other.id && type == other.type

    override fun hashCode(): Int = 31 * id.hashCode() + type.hashCode()

    override fun toString(): String = "OwnedClipboardMediaUri(type=$type, id=<redacted>)"

    companion object {
        fun create(id: Long, type: ItemType): OwnedClipboardMediaUri? {
            val raw = canonicalClipboardMediaUri(
                authority = ClipboardMediaProvider.AUTHORITY,
                type = type,
                id = id,
            ) ?: return null
            return OwnedClipboardMediaUri(id, type, raw.toUri())
        }

        fun parse(uri: Uri, expectedType: ItemType): OwnedClipboardMediaUri? {
            return parseProviderUri(
                uri = uri,
                authority = ClipboardMediaProvider.AUTHORITY,
                expectedType = expectedType,
            )
        }

        fun parse(uri: Uri): OwnedClipboardMediaUri? =
            parse(uri, ItemType.IMAGE) ?: parse(uri, ItemType.VIDEO)

        fun parseOreoSystemClipboard(
            uri: Uri,
            expectedType: ItemType,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): OwnedClipboardMediaUri? {
            if (sdkInt > Build.VERSION_CODES.O_MR1) return null
            return parseProviderUri(
                uri = uri,
                authority = OreoSystemClipboardMediaProvider.AUTHORITY,
                expectedType = expectedType,
            )
        }

        fun parseOreoSystemClipboard(
            uri: Uri,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): OwnedClipboardMediaUri? =
            parseOreoSystemClipboard(uri, ItemType.IMAGE, sdkInt) ?:
                parseOreoSystemClipboard(uri, ItemType.VIDEO, sdkInt)

        internal fun parseProviderUri(
            uri: Uri,
            authority: String,
        ): OwnedClipboardMediaUri? =
            parseProviderUri(uri, authority, ItemType.IMAGE) ?:
                parseProviderUri(uri, authority, ItemType.VIDEO)

        internal fun parseProviderUri(
            uri: Uri,
            authority: String,
            expectedType: ItemType,
        ): OwnedClipboardMediaUri? {
            if (authority != ClipboardMediaProvider.AUTHORITY &&
                authority != OreoSystemClipboardMediaProvider.AUTHORITY
            ) {
                return null
            }
            val id = parseCanonicalClipboardMediaId(
                raw = uri.toString(),
                authority = authority,
                expectedType = expectedType,
            ) ?: return null
            return create(id, expectedType)
        }
    }
}

/**
 * Resolves an untrusted system-clipboard URI without letting a replayed Oreo
 * bearer URI recreate a capability which has already been retired.
 */
internal fun resolveObservedSystemClipboardMedia(
    context: Context,
    uri: Uri,
    expectedType: ItemType,
): OwnedClipboardMediaUri? {
    OwnedClipboardMediaUri.parse(uri, expectedType)?.let { return it }
    val proxy = OwnedClipboardMediaUri.parseOreoSystemClipboard(uri, expectedType)
        ?: return null
    return proxy.takeIf {
        ClipboardFileStorage.currentSystemRootFileInfo(context, it) != null
    }
}

internal fun resolveObservedSystemClipboardMedia(
    context: Context,
    uri: Uri,
): OwnedClipboardMediaUri? =
    resolveObservedSystemClipboardMedia(context, uri, ItemType.IMAGE) ?:
        resolveObservedSystemClipboardMedia(context, uri, ItemType.VIDEO)

internal fun systemClipboardMediaUri(
    ownedUri: OwnedClipboardMediaUri,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Uri {
    val authority = if (sdkInt <= Build.VERSION_CODES.O_MR1) {
        OreoSystemClipboardMediaProvider.AUTHORITY
    } else {
        ClipboardMediaProvider.AUTHORITY
    }
    val raw = checkNotNull(
        canonicalClipboardMediaUri(
            authority = authority,
            type = ownedUri.type,
            id = ownedUri.id,
        ),
    )
    return raw.toUri()
}

internal fun clipboardMediaAuthority(packageName: String): String =
    "$packageName.provider.clipboard"

private fun parseCanonicalClipboardMediaId(
    raw: String,
    authority: String,
    expectedType: ItemType,
): Long? {
    val pathSegment = expectedType.clipboardMediaPathSegment() ?: return null
    val prefix = "content://$authority/clips/$pathSegment/"
    if (!raw.startsWith(prefix)) return null
    if (raw.length !in (prefix.length + 1)..(prefix.length + MAX_CLIPBOARD_MEDIA_ID_DIGITS)) {
        return null
    }
    val idText = raw.substring(prefix.length)
    if (idText.any { it !in '0'..'9' }) return null
    val id = idText.toLongOrNull()?.takeIf { it > 0L } ?: return null
    return id.takeIf { raw == canonicalClipboardMediaUri(authority, expectedType, it) }
}

private fun canonicalClipboardMediaUri(
    authority: String,
    type: ItemType,
    id: Long,
): String? {
    if (authority.isEmpty() || id <= 0L) return null
    val pathSegment = type.clipboardMediaPathSegment() ?: return null
    return "content://$authority/clips/$pathSegment/$id"
}

private fun ItemType.clipboardMediaPathSegment(): String? = when (this) {
    ItemType.TEXT -> null
    ItemType.IMAGE -> "images"
    ItemType.VIDEO -> "videos"
}

private const val MAX_CLIPBOARD_MEDIA_ID_DIGITS = 19
