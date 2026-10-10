/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.io

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/**
 * Immutable [Uri] reference for app resources, content URIs, and links.
 * Local paths and sub-reference names are already URI-encoded.
 */
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

        /** Points to a resource in the APK assets directory. */
        fun assets(path: String) = local(AUTHORITY_ASSETS, path)

        /** Points to a resource in the app's internal storage. */
        fun internal(path: String) = local(AUTHORITY_INTERNAL, path)

        private fun local(authority: String, path: String) = Uri.Builder().run {
            scheme(SCHEME_FLORIS)
            authority(authority)
            encodedPath(path)
            FlorisRef(build())
        }

        /** Wraps any URI unchanged, including app/cache resources and content URIs. */
        fun from(uri: Uri) = FlorisRef(uri)

        /** Uses `https://` unless [url] starts with `http://`, `https://`, or `mailto:`. */
        fun fromUrl(url: String): FlorisRef = FlorisRef(
            when {
                url.startsWith(URL_HTTP_PREFIX) ||
                    url.startsWith(URL_HTTPS_PREFIX) ||
                    url.startsWith(URL_MAILTO_PREFIX) -> url.toUri()

                else -> "$URL_HTTPS_PREFIX$url".toUri().normalizeScheme()
            },
        )
    }

    /** Whether this is a `florisboard://app-ui` reference. */
    val isAppUi: Boolean
        get() = uri.scheme == SCHEME_FLORIS && uri.authority == AUTHORITY_APP_UI

    /** Whether this is a `florisboard://assets` reference. */
    val isAssets: Boolean
        get() = uri.scheme == SCHEME_FLORIS && uri.authority == AUTHORITY_ASSETS

    /** Whether this is a `florisboard://cache` reference. */
    val isCache: Boolean
        get() = uri.scheme == SCHEME_FLORIS && uri.authority == AUTHORITY_CACHE

    /** Whether this is a `florisboard://internal` reference. */
    val isInternal: Boolean
        get() = uri.scheme == SCHEME_FLORIS && uri.authority == AUTHORITY_INTERNAL

    /** URI scheme, or an empty string when absent. */
    val scheme: String
        get() = uri.scheme ?: ""

    /** URI authority, or an empty string when absent. */
    val authority: String
        get() = uri.authority ?: ""

    /** Decoded URI path with one leading slash removed, or an empty string when absent. */
    val relativePath: String
        get() = (uri.path ?: "").removePrefix("/")

    /**
     * Resolves cache/internal refs under the app's directories. App/asset refs keep their relative path;
     * other refs use their URI path, or an empty string when absent.
     */
    fun absolutePath(context: Context): String = when {
        isAppUi || isAssets -> relativePath
        isCache -> "${context.cacheDir.absolutePath}/$relativePath"
        isInternal -> "${context.filesDir.absolutePath}/$relativePath"
        else -> uri.path ?: ""
    }

    /** Wraps [absolutePath] in a [File]; app/asset paths remain relative. */
    fun absoluteFile(context: Context): File = File(absolutePath(context))

    /** Appends the URI-encoded [name] and returns a new reference. */
    fun subRef(name: String) = from(
        uri.buildUpon().run {
            appendEncodedPath(name)
            build()
        },
    )

    /** Encoded URI string. */
    override fun toString(): String = uri.toString()
}
