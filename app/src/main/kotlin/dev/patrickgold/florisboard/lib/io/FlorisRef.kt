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

package dev.patrickgold.florisboard.lib.io

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File

/**
 * A universal resource reference, capable to point to destinations within
 * FlorisBoard's app user interface screens, APK assets, cache and internal
 * storage, external resources provided to FlorisBoard via content URIs, as
 * well as hyperlinks.
 *
 * [android.net.Uri] is used as the underlying implementation for storing the
 * reference and also handles parsing of raw string URIs.
 *
 * The reference is immutable. If a change is required, consider constructing
 * a new reference with the provided builder methods.
 *
 * @property uri The underlying URI, which can be used for external references
 *  to pass along to the system.
 */
@Serializable(with = FlorisRef.Serializer::class)
@JvmInline
value class FlorisRef private constructor(val uri: Uri) {
    companion object {
        private const val SCHEME_FLORIS = "florisboard"

        private const val AUTHORITY_APP_UI = "app-ui"

        private const val AUTHORITY_ASSETS = "assets"

        private const val AUTHORITY_CACHE = "cache"

        private const val AUTHORITY_INTERNAL = "internal"

        private const val URL_HTTP_PREFIX = "http://"
        private const val URL_HTTPS_PREFIX = "https://"
        private const val URL_MAILTO_PREFIX = "mailto:"

        /** Points to an app screen. [path] may include encoded route arguments. */
        fun app(path: String) = local(AUTHORITY_APP_UI, path)

        /** Points to a resource in the APK assets directory. */
        fun assets(path: String) = local(AUTHORITY_ASSETS, path)

        /** Points to a resource in the app cache. */
        fun cache(path: String) = local(AUTHORITY_CACHE, path)

        /** Points to a resource in the app's internal storage. */
        fun internal(path: String) = local(AUTHORITY_INTERNAL, path)

        private fun local(authority: String, path: String) = Uri.Builder().run {
            scheme(SCHEME_FLORIS)
            authority(authority)
            encodedPath(path)
            FlorisRef(build())
        }

        /**
         * Constructs a new reference from given [uri], this can point to any
         * destination, regardless of within FlorisBoard or not.
         *
         * @param uri The destination, denoted by a system URI format.
         *
         * @return The newly constructed reference.
         */
        fun from(uri: Uri) = FlorisRef(uri)

        /**
         * Constructs a new reference from given [str], this can point to any
         * destination, regardless of within FlorisBoard or not.
         *
         * @param str An RFC 2396-compliant, encoded URI string.
         *
         * @return The newly constructed reference.
         */
        fun from(str: String): FlorisRef {
            // First two entries only kept due to backwards-compatibility reasons.
            return when {
                str.startsWith("assets:") -> assets(str.substring(7))
                str.startsWith("internal:") -> internal(str.substring(9))
                else -> FlorisRef(str.toUri())
            }
        }

        /**
         * Constructs a new reference from given [url], which is a URL.
         *
         * @param url An URL pointing to a web page. If the scheme is missing, `https` is assumed.
         *
         * @return The newly constructed reference.
         */
        fun fromUrl(url: String): FlorisRef {
            return FlorisRef(when {
                url.startsWith(URL_HTTP_PREFIX) ||
                    url.startsWith(URL_HTTPS_PREFIX) ||
                    url.startsWith(URL_MAILTO_PREFIX) -> url.toUri()
                else -> "$URL_HTTPS_PREFIX$url".toUri().normalizeScheme()
            })
        }
    }

    /**
     * True if the scheme and authority indicates a reference to an app user interface
     * component (screen), false otherwise.
     */
    val isAppUi: Boolean
        get() = uri.scheme == SCHEME_FLORIS && uri.authority == AUTHORITY_APP_UI

    /**
     * True if the scheme and authority indicates a reference to a FlorisBoard APK asset
     * resource, false otherwise.
     */
    val isAssets: Boolean
        get() = uri.scheme == SCHEME_FLORIS && uri.authority == AUTHORITY_ASSETS

    /**
     * True if the scheme indicates a reference to a FlorisBoard cache
     * resource, false otherwise.
     */
    val isCache: Boolean
        get() = uri.scheme == SCHEME_FLORIS && uri.authority == AUTHORITY_CACHE

    /**
     * True if the scheme indicates a reference to a FlorisBoard internal
     * storage resource, false otherwise.
     */
    val isInternal: Boolean
        get() = uri.scheme == SCHEME_FLORIS && uri.authority == AUTHORITY_INTERNAL

    /**
     * Returns the scheme of this URI, or an empty string if no scheme is
     * specified.
     */
    val scheme: String
        get() = uri.scheme ?: ""

    /**
     * Returns the authority of this URI, or an empty string if no authority
     * is specified.
     */
    val authority: String
        get() = uri.authority ?: ""

    /**
     * Returns the relative path of this URI, without a leading forward slash.
     * Works only for assets, cache or internal references.
     */
    val relativePath: String
        get() = (uri.path ?: "").removePrefix("/")

    /**
     * Returns the absolute path on the device file storage for this reference,
     * depending on the [context] and the [scheme].
     *
     * @param context The context used to get the absolute path for various directories.
     *
     * @return The absolute path of this reference.
     */
    fun absolutePath(context: Context): String {
        return when {
            isAppUi || isAssets -> relativePath
            isCache -> "${context.cacheDir.absolutePath}/$relativePath"
            isInternal -> "${context.filesDir.absolutePath}/$relativePath"
            else -> uri.path ?: ""
        }
    }

    /**
     * Returns the absolute file on the device file storage for this reference,
     * depending on the [context] and the [scheme].
     *
     * @param context The context used to get the absolute file for various directories.
     *
     * @return The absolute file of this reference.
     */
    fun absoluteFile(context: Context): File {
        return File(absolutePath(context))
    }

    /**
     * Returns a new reference pointing to a sub directory(file with given [name].
     *
     * @param name The name of the sub file/directory.
     *
     * @return The newly constructed reference.
     */
    fun subRef(name: String) = from(uri.buildUpon().run {
        appendEncodedPath(name)
        build()
    })

    /**
     * Returns the encoded string representation of this URI.
     */
    override fun toString(): String {
        return uri.toString()
    }

    object Serializer : PreferenceSerializer<FlorisRef>, KSerializer<FlorisRef> {
        override val descriptor = PrimitiveSerialDescriptor("FlorisRef", PrimitiveKind.STRING)

        override fun serialize(value: FlorisRef): String {
            return value.toString()
        }

        override fun serialize(encoder: Encoder, value: FlorisRef) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(value: String): FlorisRef {
            return from(value)
        }

        override fun deserialize(decoder: Decoder): FlorisRef {
            return from(decoder.decodeString())
        }
    }
}
