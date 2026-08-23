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

package org.florisboard.lib.snygg.value

import androidx.annotation.CallSuper
import org.florisboard.lib.kotlin.curlyFormat

private val ANGLED_GROUP_NAME_REGEX = """<[a-zA-Z0-9]+>""".toRegex()
private val WHITESPACE_REGEX = """\s*""".toRegex()
private val INTEGER_NUMBER_REGEX = """0|[+-]?[1-9][0-9]*""".toRegex()
private val FLOAT_NUMBER_REGEX = """(?:0|[1-9][0-9]*)(?:[.][0-9]*)?""".toRegex()

interface SnyggValueSpec {
    val id: String?

    val parsePattern: Regex

    val packTemplate: String

    @CallSuper
    fun parse(str: String, dstMap: SnyggIdToValueMap) {
        val match = parsePattern.matchEntire(str)
        checkNotNull(match) { "$str does not match pattern $parsePattern" }
        val groupNames = ANGLED_GROUP_NAME_REGEX.findAll(parsePattern.toString()).map { angleGroup ->
            angleGroup.value.substring(1, angleGroup.value.length - 1)
        }
        for (groupName in groupNames) {
            match.groups[groupName]?.let { group ->
                dstMap.add(groupName to group.value)
            }
        }
    }

    @CallSuper
    fun pack(srcMap: SnyggIdToValueMap): String {
        val raw = packTemplate.curlyFormat { arg ->
            srcMap[arg] ?: error("$arg not provided in mapping")
        }
        check(parsePattern.matchEntire(raw) != null) { "cannot serialize" }
        return raw
    }
}

fun SnyggValueSpec(block: SnyggValueSpecBuilder.() -> SnyggValueSpec): SnyggValueSpec =
    block(SnyggValueSpecBuilder.Instance)

private inline fun buildPattern(builderAction: StringBuilder.() -> Unit): Regex = buildString {
    append(WHITESPACE_REGEX)
    builderAction()
    append(WHITESPACE_REGEX)
}.replace("$WHITESPACE_REGEX$WHITESPACE_REGEX", WHITESPACE_REGEX.toString()).toRegex()

private fun numberParsePattern(id: String, prefix: String?, suffix: String?, unit: String?, numberPattern: Regex) =
    buildPattern {
        prefix?.let(::append)
        append("(?<$id>$numberPattern)")
        suffix?.let(::append)
        unit?.let(::append)
    }

private fun numberPackTemplate(id: String, prefix: String?, suffix: String?, unit: String?) = buildString {
    prefix?.let(::append)
    append("{$id}")
    suffix?.let(::append)
    unit?.let(::append)
}

data class SnyggIntValueSpec(
    override val id: String,
    val prefix: String?,
    val suffix: String?,
    val unit: String?,
    val numberPattern: Regex?,
) : SnyggValueSpec {
    init {
        require(id.isNotBlank()) { "id cannot be blank" }
    }

    override val parsePattern = numberParsePattern(id, prefix, suffix, unit, numberPattern ?: INTEGER_NUMBER_REGEX)

    override val packTemplate = numberPackTemplate(id, prefix, suffix, unit)
}

data class SnyggFloatValueSpec(
    override val id: String,
    val prefix: String?,
    val suffix: String?,
    val unit: String?,
    val numberPattern: Regex?,
) : SnyggValueSpec {
    init {
        require(id.isNotBlank()) { "id cannot be blank" }
    }

    override val parsePattern = numberParsePattern(id, prefix, suffix, unit, numberPattern ?: FLOAT_NUMBER_REGEX)

    override val packTemplate = numberPackTemplate(id, prefix, suffix, unit)
}

data class SnyggKeywordValueSpec(override val id: String, val keywords: List<String>) : SnyggValueSpec {
    init {
        require(id.isNotBlank()) { "id cannot be blank" }
    }

    override val parsePattern = buildPattern {
        append("(?<$id>")
        append(keywords.joinToString("|"))
        append(")")
    }

    override val packTemplate = buildString {
        append("{$id}")
    }
}

data class SnyggStringValueSpec(override val id: String, private val pattern: Regex) : SnyggValueSpec {
    init {
        require(id.isNotBlank()) { "id cannot be blank" }
    }

    override val parsePattern = buildPattern {
        append("(?<$id>")
        append(pattern)
        append(")")
    }

    override val packTemplate = buildString {
        append("{$id}")
    }
}

data class SnyggFunctionValueSpec(val name: String, val innerSpec: SnyggValueSpec) : SnyggValueSpec {
    override val id = null

    override val parsePattern = buildPattern {
        append(name)
        append("[(]")
        append(innerSpec.parsePattern)
        append("[)]")
    }

    override val packTemplate = buildString {
        append(name)
        append("(")
        append(innerSpec.packTemplate)
        append(")")
    }
}

data class SnyggListValueSpec(val separator: String, val valueSpecs: List<SnyggValueSpec>) : SnyggValueSpec {
    override val id = null

    override val parsePattern = buildPattern {
        for ((index, valueSpec) in valueSpecs.withIndex()) {
            if (index != 0) append(separator)
            append(valueSpec.parsePattern)
        }
    }

    override val packTemplate = buildString {
        for ((index, valueSpec) in valueSpecs.withIndex()) {
            if (index != 0) append(separator)
            append(valueSpec.packTemplate)
        }
    }
}

object SnyggNothingValueSpec : SnyggValueSpec {
    override val id = null

    override val parsePattern = buildPattern {}

    override val packTemplate = buildString {}
}

class SnyggValueFormatListBuilder {
    private val valueFormats = mutableListOf<SnyggValueSpec>()

    operator fun SnyggValueSpec.unaryPlus() {
        valueFormats.add(this)
    }

    fun build() = valueFormats.toList()
}

class SnyggValueSpecBuilder {
    companion object {
        val Instance = SnyggValueSpecBuilder()
    }

    inline fun spacedList(valueFormatsBlock: SnyggValueFormatListBuilder.() -> Unit) = SnyggListValueSpec(
        separator = " ",
        valueSpecs = SnyggValueFormatListBuilder().let {
            valueFormatsBlock(it)
            it.build()
        },
    )

    inline fun commaList(valueFormatsBlock: SnyggValueFormatListBuilder.() -> Unit) = SnyggListValueSpec(
        separator = ",",
        valueSpecs = SnyggValueFormatListBuilder().let {
            valueFormatsBlock(it)
            it.build()
        },
    )

    inline fun function(name: String, innerSpecBuilder: SnyggValueSpecBuilder.() -> SnyggValueSpec) =
        SnyggFunctionValueSpec(name, innerSpecBuilder(Instance))

    fun keywords(id: String, keywords: List<String>) = SnyggKeywordValueSpec(id, keywords)

    fun string(id: String, regex: Regex) = SnyggStringValueSpec(id, regex)

    fun int(
        id: String,
        prefix: String? = null,
        suffix: String? = null,
        unit: String? = null,
        numberPattern: Regex? = null,
    ) = SnyggIntValueSpec(id, prefix, suffix, unit, numberPattern)

    fun float(
        id: String,
        prefix: String? = null,
        suffix: String? = null,
        unit: String? = null,
        numberPattern: Regex? = null,
    ) = SnyggFloatValueSpec(id, prefix, suffix, unit, numberPattern)

    fun percentageInt(id: String) = int(id, unit = "%", numberPattern = """100|[1-9]?[0-9]""".toRegex())

    fun percentageFloat(id: String) =
        float(id, unit = "%", numberPattern = """100(?:[.]0*)?|[1-9]?[0-9](?:[.][0-9]*)?""".toRegex())

    fun nothing() = SnyggNothingValueSpec
}
