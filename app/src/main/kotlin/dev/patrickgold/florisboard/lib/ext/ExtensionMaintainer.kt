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

package dev.patrickgold.florisboard.lib.ext

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ExtensionMaintainerSerializer::class)
data class ExtensionMaintainer(val name: String, val email: String? = null, val url: String? = null) {
    companion object {
        @Suppress("ReturnCount") // Guard clauses keep this linear parser easy to audit.
        fun from(str: String): ExtensionMaintainer? {
            var index = str.skipMaintainerWhitespace(0)
            if (index == str.length) {
                return null
            }

            val nameStart = index
            var codePoint = Character.codePointAt(str, index)
            if (!codePoint.isMaintainerNameSymbol()) {
                return null
            }
            index += Character.charCount(codePoint)
            while (index < str.length) {
                codePoint = Character.codePointAt(str, index)
                if (!codePoint.isMaintainerNamePart()) {
                    break
                }
                index += Character.charCount(codePoint)
            }
            val nameEnd = index

            var email: String? = null
            if (str.getOrNull(index) == '<') {
                val emailStart = index + 1
                val emailEnd = str.endOfMaintainerField(index, '<', '>') ?: return null
                email = str.substring(emailStart, emailEnd).trim().takeIf { it.isNotBlank() }
                index = emailEnd + 1
            }
            index = str.skipMaintainerWhitespace(index)

            var url: String? = null
            if (str.getOrNull(index) == '(') {
                val urlStart = index + 1
                val urlEnd = str.endOfMaintainerField(index, '(', ')') ?: return null
                url = str.substring(urlStart, urlEnd).trim().takeIf { it.isNotBlank() }
                index = urlEnd + 1
            }
            if (str.skipMaintainerWhitespace(index) != str.length) {
                return null
            }
            return ExtensionMaintainer(str.substring(nameStart, nameEnd).trim(), email, url)
        }

        fun fromOrTakeRaw(str: String): ExtensionMaintainer = from(str) ?: ExtensionMaintainer(str)
    }

    override fun toString() = buildString {
        append(name)
        if (!email.isNullOrBlank()) {
            append(" <$email>")
        }
        if (!url.isNullOrBlank()) {
            append(" ($url)")
        }
    }
}

private fun String.skipMaintainerWhitespace(startIndex: Int): Int {
    var index = startIndex
    while (index < length && this[index].isMaintainerWhitespace()) {
        index++
    }
    return index
}

private fun String.endOfMaintainerField(startIndex: Int, opening: Char, closing: Char): Int? {
    var index = startIndex + 1
    while (index < length) {
        when (this[index]) {
            opening -> return null
            closing -> return index.takeIf { it > startIndex + 1 }
            else -> index++
        }
    }
    return null
}

private fun Int.isMaintainerNameSymbol(): Boolean = Character.isLetter(this) ||
    this in '0'.code..'9'.code ||
    this == '.'.code ||
    this == '_'.code ||
    this == '-'.code

private fun Int.isMaintainerNamePart(): Boolean = isMaintainerNameSymbol() ||
    this == ' '.code ||
    this in '\t'.code..'\r'.code

private fun Char.isMaintainerWhitespace(): Boolean = this == ' ' || this in '\t'..'\r'

object ExtensionMaintainerSerializer : KSerializer<ExtensionMaintainer> {
    override val descriptor = PrimitiveSerialDescriptor("ExtensionMaintainer", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExtensionMaintainer) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): ExtensionMaintainer =
        ExtensionMaintainer.fromOrTakeRaw(decoder.decodeString())
}
