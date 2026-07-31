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

package dev.patrickgold.florisboard.lib.ext

import dev.patrickgold.florisboard.ime.core.SubtypePreset
import dev.patrickgold.florisboard.ime.keyboard.KeyData
import dev.patrickgold.florisboard.ime.keyboard.KeyboardExtension
import dev.patrickgold.florisboard.ime.keyboard.LayoutType
import dev.patrickgold.florisboard.ime.nlp.LanguagePackExtension
import dev.patrickgold.florisboard.ime.text.composing.WithRules
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import java.net.URI
import java.net.URISyntaxException

/**
 * Content-free structural failures for an extension manifest.
 *
 * These values are safe for diagnostics because they never carry descriptor text, paths, or other
 * untrusted input.
 */
enum class ExtensionImportValidationError {
    META_ID,
    META_VERSION,
    META_TITLE,
    META_DESCRIPTION,
    META_KEYWORDS,
    META_HOMEPAGE,
    META_ISSUE_TRACKER,
    META_MAINTAINERS,
    META_LICENSE,
    DEPENDENCIES,
    COMPONENT_LIMIT,
    COMPONENT_ID,
    COMPONENT_LABEL,
    COMPONENT_AUTHORS,
    DUPLICATE_COMPONENT_ID,
    KEYBOARD_RESOURCE_PATH,
    KEYBOARD_COMPOSER_RULES,
    KEYBOARD_CURRENCY_SLOTS,
    KEYBOARD_PUNCTUATION,
    KEYBOARD_POPUP_MAPPING,
    KEYBOARD_LAYOUT,
    KEYBOARD_REFERENCE,
    KEYBOARD_SUBTYPE_PRESET,
    THEME_STYLESHEET_PATH,
    LANGUAGE_SQLITE_PATH,
    LANGUAGE_SQL_TABLE,
    LANGUAGE_LOCALE,
    LANGUAGE_KEY_CODE,
}

class ExtensionImportValidationResult internal constructor(val errors: Set<ExtensionImportValidationError>) {
    val isValid: Boolean
        get() = errors.isEmpty()
}

private object ImportLimits {
    const val META_ID = 160
    const val VERSION = 64
    const val TITLE = 160
    const val DESCRIPTION = 4_096
    const val KEYWORDS = 32
    const val KEYWORD = 64
    const val WEB_LINK = 2_048
    const val MAINTAINERS = 32
    const val MAINTAINER_NAME = 160
    const val MAINTAINER_EMAIL = 254
    const val LICENSE = 128
    const val DEPENDENCIES = 64
    const val COMPONENT_GROUPS = 32
    const val COMPONENTS = 512
    const val COMPONENT_ID = 80
    const val COMPONENT_LABEL = 160
    const val AUTHORS = 32
    const val AUTHOR = 160
    const val SUBTYPE_PRESETS = 256
    const val SQL_IDENTIFIER = 63
    const val KEYBOARD_RUNTIME_ITEMS = 2_048
    const val COMPOSER_RULES = 4_096
    const val COMPOSER_RULES_TOTAL = 8_192
    const val COMPOSER_RULE_KEY = 32
    const val COMPOSER_RULE_VALUE = 64
    const val COMPOSER_RULE_TEXT_TOTAL = 262_144
    const val CURRENCY_SLOTS = 6
    const val CURRENCY_LABEL = 16
    const val PUNCTUATION_TEXT = 128
    const val PUNCTUATION_TEXT_TOTAL = 32_768
    const val LAYOUT_COMPONENTS_TOTAL = 1_024
    const val PROVIDER_ID = 160
    const val LOCALE_TAG = 64
    const val LANGUAGE_KEY_CODES = 128
    const val MANIFEST_JSON_CHARS = 1_048_576
    const val MANIFEST_JSON_DEPTH = 32
    const val MANIFEST_JSON_CONTAINERS = 8_192
    const val MANIFEST_JSON_ARRAY_ITEMS = 2_048
    const val MANIFEST_JSON_OBJECT_ENTRIES = 4_096
    const val MANIFEST_JSON_TOTAL_ITEMS = 65_536
    const val MANIFEST_JSON_STRING_TOKEN = 32_768
    const val MANIFEST_JSON_PRIMITIVE_TOKEN = 64
    const val MAX_URI_PORT = 65_535
}

private val MetaIdRegex = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z0-9][a-z0-9_]*)*$")
private val ThemeComponentIdRegex = Regex("^[a-z][a-z0-9_]*$")
private val KeyboardComponentIdRegex = Regex("^[a-z0-9][A-Za-z0-9_-]*$")
private val LanguageComponentIdRegex = Regex("^[a-z]{2,3}(?:[_-][A-Za-z0-9]+)*$")
private val KeyboardComponentGroupRegex = Regex("^[a-z][A-Za-z0-9]*$")
private val SqlIdentifierRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val ProviderIdRegex = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")
private val LocaleTagRegex = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,16}){0,3}$")
private val EmailRegex = Regex(
    "^[A-Za-z0-9.!$'*+/=_~-]+@" +
        "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
        "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$",
)

/**
 * Checks raw manifest structure before polymorphic decoding constructs runtime-bearing objects.
 *
 * This is intentionally a resource preflight, not a second JSON parser. Syntax and field types
 * remain the serializer's responsibility after this bounded scan succeeds.
 */
internal fun String.hasBoundedExtensionManifestJsonShape(): Boolean =
    length in 1..ImportLimits.MANIFEST_JSON_CHARS && ManifestJsonShapeScanner(this).scan()

private class ManifestJsonShapeScanner(private val manifest: String) {
    private val openers = CharArray(ImportLimits.MANIFEST_JSON_DEPTH)
    private val separators = IntArray(ImportLimits.MANIFEST_JSON_DEPTH)
    private val hasContent = BooleanArray(ImportLimits.MANIFEST_JSON_DEPTH)
    private var depth = 0
    private var containerCount = 0
    private var totalItems = 0
    private var stringTokenLength = 0
    private var primitiveTokenLength = 0
    private var inString = false
    private var escaped = false
    private var sawRoot = false
    private var closedRoot = false

    fun scan(): Boolean {
        var index = 0
        var valid = true
        while (index < manifest.length && valid) {
            valid = consume(manifest[index])
            index++
        }
        return valid && isComplete()
    }

    private fun consume(char: Char): Boolean =
        if (inString) consumeStringCharacter(char) else consumeStructuralCharacter(char)

    private fun consumeStringCharacter(char: Char): Boolean = when {
        char == '"' && !escaped -> {
            inString = false
            stringTokenLength = 0
            true
        }

        char.isISOControl() -> false

        else -> {
            stringTokenLength++
            val withinLimit = stringTokenLength <= ImportLimits.MANIFEST_JSON_STRING_TOKEN
            if (withinLimit) {
                escaped = if (escaped) false else char == '\\'
            }
            withinLimit
        }
    }

    private fun consumeStructuralCharacter(char: Char): Boolean = when (char) {
        '"' -> startString()
        '{', '[' -> openContainer(char)
        '}', ']' -> closeContainer(char)
        ',' -> consumeSeparator()
        ':' -> consumeColon()
        ' ', '\t', '\r', '\n' -> consumeWhitespace()
        else -> consumePrimitive(char)
    }

    private fun startString(): Boolean {
        val canStart = depth > 0 && !closedRoot
        if (canStart) {
            markCurrentContainerHasContent()
            inString = true
            escaped = false
            primitiveTokenLength = 0
        }
        return canStart
    }

    private fun openContainer(opener: Char): Boolean {
        if (closedRoot || !acceptRoot(opener)) {
            return false
        }
        markCurrentContainerHasContent()
        if (!reserveContainer()) {
            return false
        }
        openers[depth] = opener
        separators[depth] = 0
        hasContent[depth] = false
        depth++
        primitiveTokenLength = 0
        return true
    }

    private fun acceptRoot(opener: Char): Boolean {
        if (depth > 0) {
            return true
        }
        val validRoot = !sawRoot && opener == '{'
        if (validRoot) {
            sawRoot = true
        }
        return validRoot
    }

    private fun reserveContainer(): Boolean {
        if (depth >= ImportLimits.MANIFEST_JSON_DEPTH) {
            return false
        }
        containerCount++
        return containerCount <= ImportLimits.MANIFEST_JSON_CONTAINERS
    }

    private fun closeContainer(closer: Char): Boolean {
        val index = depth - 1
        val expectedOpener = if (closer == '}') '{' else '['
        if (index < 0 || openers[index] != expectedOpener) {
            return false
        }
        val itemCount = if (hasContent[index]) separators[index] + 1 else 0
        totalItems += itemCount
        if (
            itemCount > itemLimit(index) ||
            totalItems > ImportLimits.MANIFEST_JSON_TOTAL_ITEMS
        ) {
            return false
        }
        depth--
        if (depth == 0) {
            closedRoot = true
        }
        primitiveTokenLength = 0
        return true
    }

    private fun consumeSeparator(): Boolean {
        if (depth == 0) {
            return false
        }
        val index = depth - 1
        separators[index]++
        val withinLimit = separators[index] < itemLimit(index)
        if (withinLimit) {
            primitiveTokenLength = 0
        }
        return withinLimit
    }

    private fun consumeColon(): Boolean {
        val insideContainer = depth > 0
        if (insideContainer) {
            primitiveTokenLength = 0
        }
        return insideContainer
    }

    private fun consumeWhitespace(): Boolean {
        primitiveTokenLength = 0
        return true
    }

    private fun consumePrimitive(char: Char): Boolean {
        val canConsume = depth > 0 && !closedRoot && !char.isISOControl()
        if (canConsume) {
            markCurrentContainerHasContent()
            primitiveTokenLength++
        }
        return canConsume && primitiveTokenLength <= ImportLimits.MANIFEST_JSON_PRIMITIVE_TOKEN
    }

    private fun itemLimit(index: Int): Int = if (openers[index] == '{') {
        ImportLimits.MANIFEST_JSON_OBJECT_ENTRIES
    } else {
        ImportLimits.MANIFEST_JSON_ARRAY_ITEMS
    }

    private fun markCurrentContainerHasContent() {
        if (depth > 0) {
            hasContent[depth - 1] = true
        }
    }

    private fun isComplete(): Boolean = sawRoot && closedRoot && depth == 0 && !inString
}

/**
 * Performs bounded structural validation without reading extension files or mutating app state.
 */
fun Extension.validateForImport(): ExtensionImportValidationResult {
    val errors = linkedSetOf<ExtensionImportValidationError>()
    validateMeta(meta, errors)
    validateDependencies(meta.id, dependencies, errors)
    when (this) {
        is KeyboardExtension -> validateKeyboardExtension(this, errors)

        is ThemeExtension -> validateThemeExtension(this, errors)

        is LanguagePackExtension -> validateLanguagePackExtension(this, errors)

        else -> validateComponentGroup(
            components = components(),
            idRegex = KeyboardComponentIdRegex,
            errors = errors,
        )
    }
    return ExtensionImportValidationResult(errors.toSet())
}

private fun validateMeta(meta: ExtensionMeta, errors: MutableSet<ExtensionImportValidationError>) {
    if (!isMetaId(meta.id)) {
        errors += ExtensionImportValidationError.META_ID
    }
    if (!meta.version.isBoundedSingleLine(ImportLimits.VERSION)) {
        errors += ExtensionImportValidationError.META_VERSION
    }
    if (!meta.title.isBoundedSingleLine(ImportLimits.TITLE)) {
        errors += ExtensionImportValidationError.META_TITLE
    }
    if (meta.description != null && !meta.description.isBoundedMultiline(ImportLimits.DESCRIPTION)) {
        errors += ExtensionImportValidationError.META_DESCRIPTION
    }
    if (!meta.keywords.areValidKeywords()) {
        errors += ExtensionImportValidationError.META_KEYWORDS
    }
    if (meta.homepage != null && !meta.homepage.isBoundedWebLink()) {
        errors += ExtensionImportValidationError.META_HOMEPAGE
    }
    if (meta.issueTracker != null && !meta.issueTracker.isBoundedWebLink()) {
        errors += ExtensionImportValidationError.META_ISSUE_TRACKER
    }
    if (!meta.maintainers.areValidMaintainers()) {
        errors += ExtensionImportValidationError.META_MAINTAINERS
    }
    if (!meta.license.isBoundedSingleLine(ImportLimits.LICENSE)) {
        errors += ExtensionImportValidationError.META_LICENSE
    }
}

private fun validateDependencies(
    extensionId: String,
    dependencies: List<String>?,
    errors: MutableSet<ExtensionImportValidationError>,
) {
    if (dependencies == null) {
        return
    }
    val withinLimit = dependencies.size <= ImportLimits.DEPENDENCIES
    val hasValidIds = withinLimit && dependencies.all { isMetaId(it) && it != extensionId }
    val hasUniqueIds = hasValidIds && dependencies.distinct().size == dependencies.size
    if (!hasUniqueIds) {
        errors += ExtensionImportValidationError.DEPENDENCIES
    }
}

private fun validateKeyboardExtension(
    extension: KeyboardExtension,
    errors: MutableSet<ExtensionImportValidationError>,
) {
    validateKeyboardComponentGroups(extension, errors)
    val layoutCount = extension.layouts.values.sumOf { it.size }
    val runtimeItemCount = extension.composers.size +
        extension.currencySets.size +
        extension.punctuationRules.size +
        extension.popupMappings.size +
        layoutCount +
        extension.subtypePresets.size
    if (
        extension.layouts.size > ImportLimits.COMPONENT_GROUPS ||
        layoutCount > ImportLimits.LAYOUT_COMPONENTS_TOTAL ||
        runtimeItemCount > ImportLimits.KEYBOARD_RUNTIME_ITEMS ||
        extension.subtypePresets.size > ImportLimits.SUBTYPE_PRESETS
    ) {
        errors += ExtensionImportValidationError.COMPONENT_LIMIT
    }
    validateKeyboardLayouts(extension, errors)
    for (preset in extension.subtypePresets) {
        validateSubtypePreset(preset, errors)
    }
}

private fun validateKeyboardComponentGroups(
    extension: KeyboardExtension,
    errors: MutableSet<ExtensionImportValidationError>,
) {
    validateSimpleComponentGroup(
        extension.composers.map { it.id to it.label },
        KeyboardComponentIdRegex,
        errors,
    )
    validateComposerRules(extension, errors)
    validateSimpleComponentGroup(
        extension.currencySets.map { it.id to it.label },
        KeyboardComponentIdRegex,
        errors,
    )
    validateCurrencySets(extension, errors)
    validateComponentGroup(
        extension.punctuationRules,
        KeyboardComponentIdRegex,
        errors,
    )
    validatePunctuationRules(extension, errors)
    validateComponentGroup(
        extension.popupMappings,
        KeyboardComponentIdRegex,
        errors,
    )
    validatePopupMappings(extension, errors)
}

private fun validateKeyboardLayouts(extension: KeyboardExtension, errors: MutableSet<ExtensionImportValidationError>) {
    for ((typeId, components) in extension.layouts) {
        val layoutType = LayoutType.fromId(typeId)
        if (
            typeId.length > ImportLimits.COMPONENT_ID ||
            !KeyboardComponentGroupRegex.matches(typeId) ||
            layoutType == null
        ) {
            errors += ExtensionImportValidationError.KEYBOARD_LAYOUT
        }
        validateComponentGroup(components, KeyboardComponentIdRegex, errors)
        for (component in components) {
            if (component.direction !in setOf("ltr", "rtl")) {
                errors += ExtensionImportValidationError.KEYBOARD_LAYOUT
            }
            if (component.modifier?.isValidKeyboardReference() == false) {
                errors += ExtensionImportValidationError.KEYBOARD_REFERENCE
            }
            val resourcePath = layoutType?.let(component::arrangementFile)
                ?: component.arrangementFile
            if (resourcePath == null || SafeRelativePath.parse(resourcePath).isFailure) {
                errors += ExtensionImportValidationError.KEYBOARD_RESOURCE_PATH
            }
        }
    }
}

private fun validateComposerRules(extension: KeyboardExtension, errors: MutableSet<ExtensionImportValidationError>) {
    var totalRules = 0
    var totalTextLength = 0L
    var isValid = true
    for (composer in extension.composers.asSequence().filterIsInstance<WithRules>()) {
        totalRules += composer.rules.size
        if (
            composer.rules.size > ImportLimits.COMPOSER_RULES ||
            totalRules > ImportLimits.COMPOSER_RULES_TOTAL
        ) {
            isValid = false
        } else {
            val caseInsensitiveTriggers = HashSet<String>(composer.rules.size)
            for ((trigger, replacement) in composer.rules) {
                totalTextLength += trigger.length.toLong() + replacement.length
                if (
                    !trigger.isBoundedRuntimeText(ImportLimits.COMPOSER_RULE_KEY, allowEmpty = false) ||
                    !replacement.isBoundedRuntimeText(ImportLimits.COMPOSER_RULE_VALUE) ||
                    !caseInsensitiveTriggers.add(trigger.lowercase())
                ) {
                    isValid = false
                }
            }
        }
    }
    if (
        !isValid ||
        totalRules > ImportLimits.COMPOSER_RULES_TOTAL ||
        totalTextLength > ImportLimits.COMPOSER_RULE_TEXT_TOTAL
    ) {
        errors += ExtensionImportValidationError.KEYBOARD_COMPOSER_RULES
    }
}

private fun validateCurrencySets(extension: KeyboardExtension, errors: MutableSet<ExtensionImportValidationError>) {
    val containsInvalidSet = extension.currencySets.any { currencySet ->
        val invalidSlotCount = currencySet.slotsForValidation.size !in 1..ImportLimits.CURRENCY_SLOTS
        if (invalidSlotCount) {
            true
        } else {
            currencySet.slotsForValidation.any { slot ->
                val validCharacter = slot.type == KeyType.CHARACTER &&
                    slot.code in KeyCode.Spec.CHARACTERS &&
                    slot.code !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
                val validLabel = validCharacter && slot.label.isBoundedRuntimeText(
                    ImportLimits.CURRENCY_LABEL,
                    allowEmpty = false,
                )
                val validMetadata = validLabel &&
                    slot.groupId == KeyData.GROUP_DEFAULT &&
                    slot.popup == null
                !validMetadata
            }
        }
    }
    if (containsInvalidSet) {
        errors += ExtensionImportValidationError.KEYBOARD_CURRENCY_SLOTS
    }
}

private fun validatePunctuationRules(
    extension: KeyboardExtension,
    errors: MutableSet<ExtensionImportValidationError>,
) {
    var totalTextLength = 0L
    val isValid = extension.punctuationRules.all { rule ->
        val fields = listOf(
            rule.symbolsPrecedingAutoSpace,
            rule.symbolsFollowingAutoSpace,
            rule.symbolsPrecedingPhantomSpace,
            rule.symbolsFollowingPhantomSpace,
            rule.symbolsTerminatingSentence,
        )
        totalTextLength += fields.sumOf { it.length }
        fields.all {
            it.isBoundedRuntimeText(ImportLimits.PUNCTUATION_TEXT) &&
                it.none(Character::isSurrogate) &&
                it.toSet().size == it.length
        }
    }
    if (!isValid || totalTextLength > ImportLimits.PUNCTUATION_TEXT_TOTAL) {
        errors += ExtensionImportValidationError.KEYBOARD_PUNCTUATION
    }
}

private fun validatePopupMappings(extension: KeyboardExtension, errors: MutableSet<ExtensionImportValidationError>) {
    if (
        extension.popupMappings.any { component ->
            SafeRelativePath.parse(component.mappingFile()).isFailure ||
                component.mappingFile?.let { SafeRelativePath.parse(it).isFailure } == true
        }
    ) {
        errors += ExtensionImportValidationError.KEYBOARD_POPUP_MAPPING
    }
}

private fun validateSubtypePreset(preset: SubtypePreset, errors: MutableSet<ExtensionImportValidationError>) {
    val localeTag = preset.locale.languageTag()
    val providerIds = listOf(
        preset.nlpProviders.spelling,
        preset.nlpProviders.suggestion,
    )
    if (
        localeTag.length !in 1..ImportLimits.LOCALE_TAG ||
        !LocaleTagRegex.matches(localeTag) ||
        providerIds.any {
            it.length !in 1..ImportLimits.PROVIDER_ID || !ProviderIdRegex.matches(it)
        }
    ) {
        errors += ExtensionImportValidationError.KEYBOARD_SUBTYPE_PRESET
    }
    val references = listOf(
        preset.composer,
        preset.currencySet,
        preset.punctuationRule,
        preset.popupMapping,
        preset.preferred.characters,
        preset.preferred.symbols,
        preset.preferred.symbols2,
        preset.preferred.numeric,
        preset.preferred.numericAdvanced,
        preset.preferred.numericRow,
        preset.preferred.phone,
        preset.preferred.phone2,
    )
    if (references.any { !it.isValidKeyboardReference() }) {
        errors += ExtensionImportValidationError.KEYBOARD_REFERENCE
    }
}

private fun ExtensionComponentName.isValidKeyboardReference(): Boolean = isMetaId(extensionId) &&
    componentId.length in 1..ImportLimits.COMPONENT_ID &&
    KeyboardComponentIdRegex.matches(componentId)

private fun validateThemeExtension(extension: ThemeExtension, errors: MutableSet<ExtensionImportValidationError>) {
    validateComponentGroup(extension.themes, ThemeComponentIdRegex, errors)
    for (component in extension.themes) {
        val configuredPath = component.stylesheetPath
        if (
            configuredPath?.let { it.isNotEmpty() && it.isBlank() } == true ||
            SafeRelativePath.parse(component.stylesheetPath()).isFailure
        ) {
            errors += ExtensionImportValidationError.THEME_STYLESHEET_PATH
        }
    }
}

private fun validateLanguagePackExtension(
    extension: LanguagePackExtension,
    errors: MutableSet<ExtensionImportValidationError>,
) {
    validateComponentGroup(extension.items, LanguageComponentIdRegex, errors)
    if (SafeRelativePath.parse(extension.hanShapeBasedSQLite).isFailure) {
        errors += ExtensionImportValidationError.LANGUAGE_SQLITE_PATH
    }
    for (component in extension.items) {
        val localeTag = component.locale.languageTag()
        if (
            localeTag.length !in 1..ImportLimits.LOCALE_TAG ||
            !LocaleTagRegex.matches(localeTag)
        ) {
            errors += ExtensionImportValidationError.LANGUAGE_LOCALE
        }
        val keyCodes = component.hanShapeBasedKeyCode
        if (
            !keyCodes.isBoundedRuntimeText(
                ImportLimits.LANGUAGE_KEY_CODES,
                allowEmpty = false,
            ) ||
            keyCodes.any { it.isWhitespace() || Character.isSurrogate(it) } ||
            keyCodes.toSet().size != keyCodes.length
        ) {
            errors += ExtensionImportValidationError.LANGUAGE_KEY_CODE
        }
        val table = component.hanShapeBasedTable
        if (
            table.isEmpty() ||
            table.length > ImportLimits.SQL_IDENTIFIER ||
            !SqlIdentifierRegex.matches(table)
        ) {
            errors += ExtensionImportValidationError.LANGUAGE_SQL_TABLE
        }
    }
}

private fun validateComponentGroup(
    components: List<ExtensionComponent>,
    idRegex: Regex,
    errors: MutableSet<ExtensionImportValidationError>,
) {
    if (components.size > ImportLimits.COMPONENTS) {
        errors += ExtensionImportValidationError.COMPONENT_LIMIT
    }
    if (
        components.any {
            it.id.length > ImportLimits.COMPONENT_ID || !idRegex.matches(it.id)
        }
    ) {
        errors += ExtensionImportValidationError.COMPONENT_ID
    }
    if (components.any { !it.label.isBoundedSingleLine(ImportLimits.COMPONENT_LABEL) }) {
        errors += ExtensionImportValidationError.COMPONENT_LABEL
    }
    if (
        components.any { component ->
            component.authors.isEmpty() ||
                component.authors.size > ImportLimits.AUTHORS ||
                component.authors.any { !it.isBoundedSingleLine(ImportLimits.AUTHOR) }
        }
    ) {
        errors += ExtensionImportValidationError.COMPONENT_AUTHORS
    }
    if (components.map(ExtensionComponent::id).distinct().size != components.size) {
        errors += ExtensionImportValidationError.DUPLICATE_COMPONENT_ID
    }
}

private fun validateSimpleComponentGroup(
    components: List<Pair<String, String>>,
    idRegex: Regex,
    errors: MutableSet<ExtensionImportValidationError>,
) {
    if (components.size > ImportLimits.COMPONENTS) {
        errors += ExtensionImportValidationError.COMPONENT_LIMIT
    }
    if (
        components.any { (id) ->
            id.length > ImportLimits.COMPONENT_ID || !idRegex.matches(id)
        }
    ) {
        errors += ExtensionImportValidationError.COMPONENT_ID
    }
    if (components.any { (_, label) -> !label.isBoundedSingleLine(ImportLimits.COMPONENT_LABEL) }) {
        errors += ExtensionImportValidationError.COMPONENT_LABEL
    }
    if (components.map(Pair<String, String>::first).distinct().size != components.size) {
        errors += ExtensionImportValidationError.DUPLICATE_COMPONENT_ID
    }
}

private fun isMetaId(value: String): Boolean {
    if (
        value.length !in 1..ImportLimits.META_ID ||
        !MetaIdRegex.matches(value)
    ) {
        return false
    }
    return value.split('.').all { it.length <= ImportLimits.COMPONENT_ID }
}

private fun List<String>?.areValidKeywords(): Boolean {
    if (this == null) {
        return true
    }
    return size <= ImportLimits.KEYWORDS &&
        all { it.isBoundedSingleLine(ImportLimits.KEYWORD) } &&
        distinct().size == size
}

private fun List<ExtensionMaintainer>.areValidMaintainers(): Boolean {
    if (size !in 1..ImportLimits.MAINTAINERS) {
        return false
    }
    return none { maintainer ->
        !maintainer.name.isBoundedSingleLine(ImportLimits.MAINTAINER_NAME) ||
            maintainer.email?.let {
                !it.isBoundedSingleLine(ImportLimits.MAINTAINER_EMAIL) || !EmailRegex.matches(it)
            } == true ||
            maintainer.url?.let { it.safeMaintainerWebUrlOrNull() == null } == true
    }
}

private fun String.isBoundedSingleLine(maxLength: Int): Boolean =
    length in 1..maxLength && isNotBlank() && none(Char::isISOControl)

private fun String.isBoundedRuntimeText(maxLength: Int, allowEmpty: Boolean = true): Boolean = length <= maxLength &&
    (allowEmpty || isNotEmpty()) &&
    none(Char::isISOControl) &&
    hasWellFormedUtf16()

private fun String.hasWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index++]
        when {
            Character.isHighSurrogate(char) -> {
                if (index >= length || !Character.isLowSurrogate(this[index++])) {
                    return false
                }
            }

            Character.isLowSurrogate(char) -> return false
        }
    }
    return true
}

private fun String.isBoundedMultiline(maxLength: Int): Boolean = length in 1..maxLength &&
    isNotBlank() &&
    none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }

private fun String.isBoundedWebLink(): Boolean = safeWebUrlOrNull(allowBareHost = false) != null

/** Accepts the documented bare-host form but always returns an HTTP(S) launch target. */
internal fun String.safeMaintainerWebUrlOrNull(): String? = safeWebUrlOrNull(allowBareHost = true)

private fun String.safeWebUrlOrNull(allowBareHost: Boolean): String? {
    if (
        length !in 1..ImportLimits.WEB_LINK ||
        any(Char::isISOControl)
    ) {
        return null
    }
    val target = if (allowBareHost && "://" !in this) "https://$this" else this
    return try {
        val uri = URI(target)
        target.takeIf {
            uri.isAbsolute &&
                !uri.isOpaque &&
                uri.scheme.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port in 1..ImportLimits.MAX_URI_PORT)
        }
    } catch (_: URISyntaxException) {
        return null
    }
}
