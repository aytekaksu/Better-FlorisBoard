/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package org.florisboard.lib.snygg

/**
 * Base interface for all Snygg stylesheet rules. A rule in a stylesheet is a core component, acting as the key for a
 * property set map. There are two main rule categories, annotation and element rules.
 *
 * - **Annotation rules**: Represent meta-rules, describing globally valid properties. This includes global style
 *   variables, global font faces, etc.
 * - **Element rules**: These rules target a specific element with a specific attribute/selector set. Used to describe
 *   the style of a specific element.
 *
 * @since 0.5.0-alpha01
 * @see [SnyggAnnotationRule.Defines]
 * @see [SnyggAnnotationRule.Font]
 * @see [SnyggElementRule]
 */
sealed interface SnyggRule : Comparable<SnyggRule> {
    /**
     * Returns the associated declaration of this rule.
     *
     * @since 0.5.0-alpha01
     */
    fun decl(): SnyggSpecDecl.RuleDecl

    /**
     * Compares this Snygg rule with [other]. The ordering is defined as follows:
     * - @defines
     * - @font (multiple fonts => sort by fontName)
     * - elements (first by element name, then by attributes/selectors)
     *
     * @since 0.5.0-alpha01
     */
    override fun compareTo(other: SnyggRule): Int

    /**
     * Serializes the Snygg rule to a string. This method never fails.
     *
     * @return The serialized representation of the Snygg rule instance.
     * @since 0.5.0-alpha01
     */
    override fun toString(): String

    companion object {
        /**
         * Attempts to parse the given string into a `SnyggRule` instance or `null` if no match is found.
         *
         * @param str The string to parse into a `SnyggRule`.
         * @return A `SnyggRule` instance if the string matches any supported rule type, or `null` if no match is found.
         * @since 0.5.0-alpha01
         */
        fun fromOrNull(str: String): SnyggRule? = SnyggAnnotationRule.Defines.fromOrNull(str)
            ?: SnyggAnnotationRule.Font.fromOrNull(str)
            ?: SnyggElementRule.fromOrNull(str)
    }
}

/**
 * Annotation rule base interface. See the specific implementations for details.
 *
 * @since 0.5.0-alpha01
 *
 * @see [SnyggAnnotationRule.Defines]
 * @see [SnyggAnnotationRule.Font]
 */
sealed interface SnyggAnnotationRule : SnyggRule {
    data object Defines : SnyggAnnotationRule, SnyggSpecDecl.RuleDecl {
        override val name: String = "defines"

        override val pattern = """^@$name$""".toRegex()

        override fun decl() = this

        override fun compareTo(other: SnyggRule): Int = when (other) {
            is Defines -> 0
            is SnyggAnnotationRule -> decl().name.compareTo(other.decl().name)
            is SnyggElementRule -> -1 // annotations always come first
        }

        override fun toString(): String = "@defines"

        /**
         * Attempts to parse the given string into a `defines` annotation rule instance, or `null` if the given string
         * does not represent a `defines` annotation rule.
         *
         * @param str The string to parse into a `defines` annotation rule instance.
         * @return A `defines` annotation rule instance or `null`.
         *
         * @since 0.5.0-alpha01
         */
        fun fromOrNull(str: String): Defines? {
            pattern.matchEntire(str) ?: return null
            return Defines
        }
    }

    data class Font(val fontName: String) : SnyggAnnotationRule {
        override fun decl() = Companion

        override fun compareTo(other: SnyggRule): Int = when (other) {
            is Font -> fontName.compareTo(other.fontName)
            is SnyggAnnotationRule -> decl().name.compareTo(other.decl().name)
            is SnyggElementRule -> -1 // annotations always come first
        }

        override fun toString(): String = "@font `$fontName`"

        companion object : SnyggSpecDecl.RuleDecl {
            override val name = "font"
            override val pattern = """^@$name `(?<fontName>[a-zA-Z0-9\s-]+)`$""".toRegex()

            /**
             * Attempts to parse the given string into a `font` annotation rule instance, or `null` if the given string
             * does not represent a `font` annotation rule.
             *
             * @param str The string to parse into a `font` annotation rule instance.
             * @return A `font` annotation rule instance or `null`.
             *
             * @since 0.5.0-alpha01
             */
            fun fromOrNull(str: String): Font? {
                val match = pattern.matchEntire(str) ?: return null
                return Font(match.groups["fontName"]!!.value)
            }
        }
    }
}

/**
 * A core element in the Snygg styling system, this rule allows targeting specific elements with specific attributes
 * and selectors.
 *
 * @property elementName The element name this rule targets, it can be seen similarly to a CSS class.
 * @property attributes The attributes this rule targets.
 * @property selector The selector this rule targets, or [SnyggSelector.NONE] for not specified.
 *
 * @since 0.5.0-alpha01
 */
data class SnyggElementRule(
    val elementName: String,
    val attributes: SnyggAttributes = SnyggAttributes.EMPTY,
    val selector: SnyggSelector = SnyggSelector.NONE,
) : SnyggRule {
    init {
        requireNotNull(ELEMENT_NAME_REGEX.matchEntire(elementName)) { "element name is invalid" }
    }

    override fun decl() = Companion

    override fun compareTo(other: SnyggRule): Int {
        if (other !is SnyggElementRule) {
            return 1 // annotations always come first
        }
        val elemDiff = elementName.compareTo(other.elementName)
        if (elemDiff != 0) return elemDiff
        val selectorDiff = when {
            selector == other.selector -> 0
            selector == SnyggSelector.NONE -> -1
            other.selector == SnyggSelector.NONE -> 1
            else -> selector.compareTo(other.selector)
        }
        return selectorDiff.takeIf { it != 0 } ?: attributes.compareTo(other.attributes)
    }

    override fun toString() = buildString {
        append(elementName)
        append(attributes)
        append(selector)
    }

    companion object : SnyggSpecDecl.RuleDecl {
        override val name = "element"
        private val ELEMENT_NAME_REGEX = """(?<elementName>[a-zA-Z0-9-]+)""".toRegex()

        private val ATTRIBUTES_REGEX = """(?<attributesRaw>(?:${SnyggAttributes.ATTRIBUTE_REGEX})+)?""".toRegex()
        private val SELECTOR_REGEX = """(?<selectorRaw>:pressed|:focus|:hover|:disabled)?""".toRegex()
        override val pattern = """^$ELEMENT_NAME_REGEX$ATTRIBUTES_REGEX$SELECTOR_REGEX$""".toRegex()

        /**
         * Attempts to parse the given string into an element rule instance, or `null` if the given string
         * does not represent an element rule instance.
         *
         * @param str the string to parse into an element rule instance
         * @return an element rule instance or `null`
         *
         * @since 0.5.0-alpha01
         */
        fun fromOrNull(str: String): SnyggElementRule? {
            val result = pattern.matchEntire(str) ?: return null
            val elementName = result.groups["elementName"]!!.value // cannot be null logically
            val attributesRaw = result.groups["attributesRaw"]?.value
            val selectorRaw = result.groups["selectorRaw"]?.value

            return SnyggElementRule(
                elementName = elementName,
                attributes = SnyggAttributes.fromOrNull(attributesRaw ?: "") ?: return null,
                selector = SnyggSelector.from(selectorRaw ?: ""),
            )
        }
    }
}

data class SnyggAttributes private constructor(private val attributes: Map<String, List<String>>) :
    Map<String, List<String>> by attributes,
    Comparable<SnyggAttributes> {
    override fun compareTo(other: SnyggAttributes): Int {
        attributes.size.compareTo(other.attributes.size).takeIf { it != 0 }?.let { return it }
        val left = attributes.entries.iterator()
        val right = other.attributes.entries.iterator()
        while (left.hasNext()) {
            val leftEntry = left.next()
            val rightEntry = right.next()
            leftEntry.key.compareTo(rightEntry.key).takeIf { it != 0 }?.let { return it }
            compareValues(leftEntry.value, rightEntry.value).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }

    override fun toString() = buildString {
        for ((key, values) in attributes) {
            append("[$key=")
            append(serializeValues(values))
            append(']')
        }
    }

    /** Returns a canonical copy containing [pairs]. */
    fun including(vararg pairs: Pair<String, Any>): SnyggAttributes {
        if (pairs.isEmpty()) return this
        return create(flattenedEntries() + pairs.asSequence().map { (key, value) -> normalizeEntry(key, value) })
    }

    /** Returns a canonical copy without [pairs]. Missing pairs are ignored. */
    fun excluding(vararg pairs: Pair<String, Any>): SnyggAttributes {
        if (pairs.isEmpty()) return this
        val excluded = pairs.mapTo(mutableSetOf()) { (key, value) -> normalizeEntry(key, value) }
        return create(flattenedEntries().filterNot(excluded::contains))
    }

    internal fun matches(query: SnyggQueryAttributes): Boolean {
        for ((key, values) in attributes) {
            val queryValue = query[key]?.let(::normalizeValue) ?: return false
            if (queryValue !in values) return false
        }
        return true
    }

    private fun flattenedEntries() = attributes.asSequence().flatMap { (key, values) ->
        values.asSequence().map { value -> key to value }
    }

    @Suppress("RegExpUnnecessaryNonCapturingGroup")
    companion object {
        private const val MAX_VALUES_PER_RULE = 4_096
        private const val ATTRIBUTE_KEY_PATTERN = "[a-zA-Z0-9-]+"

        private val ATTRIBUTE_KEY_REGEX = ATTRIBUTE_KEY_PATTERN.toRegex()
        private val INT_PATTERN = """(?:0|-?[1-9][0-9]*)""".toRegex()
        private val INT_RANGE_PATTERN = """$INT_PATTERN[.]{2}$INT_PATTERN""".toRegex()
        private val STRING_PATTERN = """`[^`]+`""".toRegex()
        private val ATTR_VALUE_PATTERN = """(?:$STRING_PATTERN|$INT_RANGE_PATTERN|$INT_PATTERN)""".toRegex()
        private val VALUE_COMPARATOR = compareBy<String>({ it.toIntOrNull() == null }, { it })

        internal val EMPTY = SnyggAttributes(emptyMap())
        internal val ATTRIBUTE_REGEX =
            """\[(?<attrKey>$ATTRIBUTE_KEY_PATTERN)=(?<attrRawValues>$ATTR_VALUE_PATTERN(?:,$ATTR_VALUE_PATTERN)*)]"""
                .toRegex()

        private fun normalizeValue(value: Any): String? {
            val rawValue = value.toString()
            return rawValue.toIntOrNull()?.toString()
                ?: rawValue.takeIf { it.isNotEmpty() && '`' !in it }
        }

        private fun normalizeEntry(key: String, value: Any): Pair<String, String> {
            require(ATTRIBUTE_KEY_REGEX.matches(key)) { "attribute key is invalid" }
            return key to requireNotNull(normalizeValue(value)) { "attribute value is invalid" }
        }

        private fun create(entries: Sequence<Pair<String, String>>): SnyggAttributes {
            val grouped = sortedMapOf<String, MutableSet<String>>()
            var valueCount = 0
            for ((key, value) in entries) {
                val values = grouped.getOrPut(key) { sortedSetOf(VALUE_COMPARATOR) }
                if (values.add(value)) {
                    valueCount++
                    require(valueCount <= MAX_VALUES_PER_RULE) { "rule has too many attribute values" }
                }
            }
            if (grouped.isEmpty()) return EMPTY
            return SnyggAttributes(
                buildMap {
                    grouped.forEach { (key, values) -> put(key, buildList { addAll(values) }) }
                },
            )
        }

        private fun compareValues(left: List<String>, right: List<String>): Int {
            for (index in 0..<minOf(left.size, right.size)) {
                VALUE_COMPARATOR.compare(left[index], right[index]).takeIf { it != 0 }?.let { return it }
            }
            return left.size.compareTo(right.size)
        }

        private fun serializeValues(values: List<String>): String = buildList {
            val ranges = mutableListOf<IntRange>()
            for (value in values.mapNotNull(String::toIntOrNull)) {
                val last = ranges.lastOrNull()
                if (last != null && last.last != Int.MAX_VALUE && last.last + 1 == value) {
                    ranges[ranges.lastIndex] = last.first..value
                } else {
                    ranges.add(value..value)
                }
            }
            for (range in ranges) {
                when {
                    range.first == range.last -> add(range.first.toString())

                    range.first.toLong() + 1 == range.last.toLong() -> {
                        add(range.first.toString())
                        add(range.last.toString())
                    }

                    else -> add(range.toString())
                }
            }
            values.filter { it.toIntOrNull() == null }.mapTo(this) { "`$it`" }
        }.joinToString(",")

        private fun parseRawValue(rawValue: String): List<String>? = when {
            STRING_PATTERN.matches(rawValue) -> listOf(rawValue.substring(1, rawValue.lastIndex))
            INT_RANGE_PATTERN.matches(rawValue) -> parseRange(rawValue)
            else -> rawValue.toIntOrNull()?.let { listOf(it.toString()) }
        }

        private fun parseRange(rawValue: String): List<String>? {
            val separator = rawValue.indexOf("..")
            val start = rawValue.substring(0, separator).toIntOrNull() ?: return null
            val end = rawValue.substring(separator + 2).toIntOrNull() ?: return null
            val size = end.toLong() - start.toLong() + 1
            if (size !in 1L..MAX_VALUES_PER_RULE.toLong()) return null
            return (start..end).map { it.toString() }
        }

        private fun parseEntries(str: String): List<Pair<String, String>>? = buildList {
            var parsedValueCount = 0
            for (attributeMatch in ATTRIBUTE_REGEX.findAll(str)) {
                val key = attributeMatch.groups["attrKey"]!!.value
                val rawValues = attributeMatch.groups["attrRawValues"]!!.value
                for (valueMatch in ATTR_VALUE_PATTERN.findAll(rawValues)) {
                    val values = parseRawValue(valueMatch.value) ?: return null
                    if (values.size > MAX_VALUES_PER_RULE - parsedValueCount) return null
                    parsedValueCount += values.size
                    values.mapTo(this) { value -> normalizeEntry(key, value) }
                }
            }
        }

        internal fun fromOrNull(str: String): SnyggAttributes? {
            val entries = parseEntries(str) ?: return null
            return runCatching { create(entries.asSequence()) }.getOrNull()
        }

        internal fun of(vararg pairs: Pair<String, List<Any>>): SnyggAttributes {
            val entries = pairs.asSequence().flatMap { (key, values) ->
                require(values.isNotEmpty()) { "attribute values cannot be empty" }
                values.asSequence().map { value -> normalizeEntry(key, value) }
            }
            return create(entries)
        }
    }
}

/**
 * A Snygg selector describes the interaction state of a component. Within stylesheets, this can be used in element
 * rules to target specific interaction states of elements for styling. Within the UI implementation this is used to
 * pass the current interaction state to Snygg to allow for correct style resolving.
 *
 * @property id The id of the selector.
 *
 * @since 0.5.0-alpha01
 */
enum class SnyggSelector(val id: String) {
    /**
     * No interaction is active. Only used within UI implementation, is not serialized.
     *
     * @since 0.5.0-alpha01
     */
    NONE("none"),

    /**
     * Pressed interaction.
     *
     * @since 0.5.0-alpha01
     */
    PRESSED("pressed"),

    /**
     * Focus interaction.
     *
     * @since 0.5.0-alpha01
     */
    FOCUS("focus"),

    /**
     * Hover interaction.
     *
     * @since 0.5.0-alpha01
     */
    HOVER("hover"),

    /**
     * Disabled state. Used for inputs and buttons.
     *
     * @since 0.5.0-alpha01
     */
    DISABLED("disabled"),
    ;

    /**
     * Serializes the selector to a string. If [NONE], an empty string is returned.
     *
     * @return The serialized representation of this selector.
     *
     * @since 0.5.0-alpha01
     */
    override fun toString(): String {
        if (this == NONE) {
            return ""
        }
        return buildString {
            append(SELECTOR_COLON)
            append(id)
        }
    }

    companion object {
        private const val SELECTOR_COLON = ":"

        internal fun from(str: String): SnyggSelector {
            if (str.isNotEmpty()) {
                val selector = str.substring(1)
                return entries.first { it.id == selector }
            }
            return NONE
        }
    }
}
