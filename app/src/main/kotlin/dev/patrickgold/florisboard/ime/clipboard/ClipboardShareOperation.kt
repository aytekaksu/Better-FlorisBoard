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

import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardShareOperationToken
import java.security.MessageDigest

/**
 * Binds one opaque durable token to the share request which created it.
 *
 * Only the digest enters saved state. Provider URIs can contain private
 * identifiers, so neither the request nor its digest is exposed by toString.
 */
internal class ClipboardShareOperation private constructor(
    val token: ClipboardShareOperationToken,
    val requestFingerprint: ClipboardShareRequestFingerprint,
    val isRestored: Boolean,
) {
    fun matches(sourceUri: String, declaredMimeType: String?): Boolean =
        requestFingerprint == clipboardShareRequestFingerprint(sourceUri, declaredMimeType)

    override fun toString(): String =
        "ClipboardShareOperation(token=<redacted>, request=<redacted>)"

    companion object {
        fun resolve(
            sourceUri: String,
            declaredMimeType: String?,
            restoredToken: String? = null,
            restoredRequestFingerprint: String? = null,
        ): ClipboardShareOperation? {
            val fingerprint =
                clipboardShareRequestFingerprint(sourceUri, declaredMimeType) ?: return null
            val hasRestoredIdentity =
                restoredToken != null || restoredRequestFingerprint != null
            if (!hasRestoredIdentity) {
                return ClipboardShareOperation(
                    token = ClipboardShareOperationToken.create(),
                    requestFingerprint = fingerprint,
                    isRestored = false,
                )
            }
            val token = ClipboardShareOperationToken.parse(restoredToken) ?: return null
            if (ClipboardShareRequestFingerprint.parse(restoredRequestFingerprint) != fingerprint) {
                return null
            }
            return ClipboardShareOperation(
                token = token,
                requestFingerprint = fingerprint,
                isRestored = true,
            )
        }
    }
}

@JvmInline
internal value class ClipboardShareRequestFingerprint private constructor(
    val value: String,
) {
    override fun toString(): String = "ClipboardShareRequestFingerprint(<redacted>)"

    companion object {
        private val FORMAT = Regex("""[0-9a-f]{64}""")

        fun parse(value: String?): ClipboardShareRequestFingerprint? =
            value?.takeIf(FORMAT::matches)?.let(::ClipboardShareRequestFingerprint)

        internal fun create(value: String): ClipboardShareRequestFingerprint =
            requireNotNull(parse(value))
    }
}

internal fun clipboardShareRequestFingerprint(
    sourceUri: String,
    declaredMimeType: String?,
): ClipboardShareRequestFingerprint? {
    if (sourceUri.length !in 1..MAX_SHARE_SOURCE_URI_LENGTH ||
        declaredMimeType != null &&
        declaredMimeType.length > MAX_SHARE_MIME_TYPE_LENGTH
    ) {
        return null
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(declaredMimeType.orEmpty().toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(sourceUri.toByteArray(Charsets.UTF_8))
    return ClipboardShareRequestFingerprint.create(buildString(SHA_256_HEX_LENGTH) {
        digest.digest().forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    })
}

private const val MAX_SHARE_SOURCE_URI_LENGTH = 32 * 1024
private const val SHA_256_HEX_LENGTH = 64
private const val HEX_DIGITS = "0123456789abcdef"
