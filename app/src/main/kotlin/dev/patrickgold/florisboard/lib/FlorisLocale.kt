/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib

import android.content.Context
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.lib.FlorisLocale.Companion.default
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Wraps [Locale] to keep tags and display names consistent across the app.
 * Kept as a regular class for Room compatibility.
 * Create one with `from(...)` or [fromTag].
 */
@Serializable(with = FlorisLocale.Serializer::class)
class FlorisLocale private constructor(val base: Locale) {
    companion object {
        private const val DELIMITER_LANGUAGE_TAG = '-'
        private const val DELIMITER_LOCALE_TAG = '_'
        private val DELIMITER_SPLITTER = """[${DELIMITER_LANGUAGE_TAG}${DELIMITER_LOCALE_TAG}]""".toRegex()

        val ROOT = from("", "", "")
        val ENGLISH = from("en", "", "")

        fun from(javaLocale: Locale) = FlorisLocale(javaLocale)

        fun from(language: String) = from(Locale(language))

        fun from(language: String, country: String) = from(Locale(language, country))

        fun from(language: String, country: String, variant: String) = from(Locale(language, country, variant))

        /**
         * Parses a tag with `-` or `_` separators. Uses the language, optional
         * country and optional variant; any later parts are ignored.
         */
        fun fromTag(str: String) = when {
            str.contains(DELIMITER_SPLITTER) -> {
                val lc = str.split(DELIMITER_SPLITTER)
                if (lc.size >= 3) {
                    from(lc[0], lc[1], lc[2])
                } else {
                    from(lc[0], lc[1])
                }
            }
            else -> from(str)
        }

        /** Wraps the JVM's current default locale. */
        fun default() = FlorisLocale(Locale.getDefault())

        /** Wraps the locales installed on the system. */
        fun installedSystemLocales(): List<FlorisLocale> = Locale.getAvailableLocales().map { from(it) }

        /** Includes locales supplied by installed language packs. */
        fun extendedAvailableLocales(context: Context): List<FlorisLocale> {
            val extensionManager by context.extensionManager()
            val packLocales = extensionManager.languagePacks.value.flatMap { pack ->
                pack.items.map { it.locale }
            }
            return mergeAvailableLocales(installedSystemLocales(), packLocales)
        }
    }

    private fun buildLocaleString(delimiter: Char) = buildString {
        val language = base.language
        val country = base.country
        val variant = base.variant
        append(language)
        if (language.isNotBlank() && country.isNotBlank()) {
            append(delimiter)
        }
        append(country)
        if (country.isNotBlank() && variant.isNotBlank()) {
            append(delimiter)
        }
        append(variant)
    }

    val language: String get() = base.language

    val country: String get() = base.country

    val variant: String get() = base.variant

    /** Hard-coded capitalization policy; this language list is not exhaustive. */
    val supportsCapitalization: Boolean
        get() = when (language) {
            "zh", "ja", "ko", "th", "bn", "hi" -> false
            else -> true
        }

    /** Whether to add a space after suggestions; this language list is not exhaustive. */
    val supportsAutoSpace: Boolean
        get() = when (language) {
            "zh", "ja", "ko", "th" -> false
            else -> true
        }

    /** A hyphenated tag such as `en-US`; empty when all parts are empty. */
    fun languageTag(): String = buildLocaleString(DELIMITER_LANGUAGE_TAG)

    /** An underscored tag such as `en_US`; empty when all parts are empty. */
    fun localeTag(): String = buildLocaleString(DELIMITER_LOCALE_TAG)

    /** Language name in [locale], with its first letter title-cased. */
    fun displayLanguage(locale: FlorisLocale = default()): String {
        return base.getDisplayLanguage(locale.base).titlecase(locale)
    }

    /** Country name in [locale]. */
    fun displayCountry(locale: FlorisLocale = default()): String = base.getDisplayCountry(locale.base)

    /** Variant name in [locale]. */
    fun displayVariant(locale: FlorisLocale = default()): String = base.getDisplayVariant(locale.base)

    /**
     * Displays `Language (Country) [VARIANT]` in [locale], omitting absent parts.
     * Uses the raw code when a localized name is missing and uppercases the
     * variant in [locale]. Returns empty when all parts are empty.
     */
    fun displayName(locale: FlorisLocale = default()) = buildString {
        val languageName = displayLanguage(locale).ifBlank { base.language }
        val countryName = displayCountry(locale).ifBlank { base.country }
        val variantName = displayVariant(locale).ifBlank { base.variant }
        append(languageName)
        if (countryName.isNotBlank()) {
            if (languageName.isNotBlank()) {
                append(' ')
            }
            append('(')
            append(countryName)
            append(')')
        }
        if (variantName.isNotBlank()) {
            if (languageName.isNotBlank() || countryName.isNotBlank()) {
                append(' ')
            }
            append('[')
            append(variantName.uppercase(locale))
            append(']')
        }
    }

    /** Debug text; use [localeTag] for a tag. */
    override fun toString() = "FlorisLocale { l=${base.language} c=${base.country} v=${base.variant} }"

    override fun equals(other: Any?): Boolean = other is FlorisLocale && base == other.base

    override fun hashCode(): Int = base.hashCode()

    /** Serializes a [languageTag] string and parses it with [fromTag]. */
    class Serializer : KSerializer<FlorisLocale> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("FlorisLocale", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: FlorisLocale) {
            encoder.encodeString(value.languageTag())
        }

        override fun deserialize(decoder: Decoder): FlorisLocale {
            return fromTag(decoder.decodeString())
        }
    }
}

internal fun mergeAvailableLocales(
    systemLocales: List<FlorisLocale>,
    packLocales: List<FlorisLocale>,
): List<FlorisLocale> {
    val seenTags = systemLocales.mapTo(mutableSetOf()) { it.localeTag() }
    return systemLocales + packLocales.filter { seenTags.add(it.localeTag()) }
}

@Suppress("NOTHING_TO_INLINE")
inline fun String.lowercase(locale: FlorisLocale): String = this.lowercase(locale.base)

@Suppress("NOTHING_TO_INLINE")
inline fun String.uppercase(locale: FlorisLocale): String = this.uppercase(locale.base)

@Suppress("NOTHING_TO_INLINE")
inline fun String.titlecase(locale: FlorisLocale = FlorisLocale.ROOT): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale.base) else it.toString() }
}
