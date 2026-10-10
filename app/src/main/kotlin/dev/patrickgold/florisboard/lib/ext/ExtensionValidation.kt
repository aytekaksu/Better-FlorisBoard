/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.ext

import androidx.annotation.StringRes
import androidx.core.text.trimmedLength
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.ValidationRule
import org.florisboard.lib.snygg.value.SnyggVarValue

object ExtensionValidation {
    private val MetaIdRegex = """^[a-z][a-z0-9_]*(\.[a-z0-9][a-z0-9_]*)*${'$'}""".toRegex()
    private val ComponentIdRegex = """^[a-z][a-z0-9_]*${'$'}""".toRegex()

    private fun requiredTextRule(@StringRes errorId: Int) = ValidationRule<String> { str ->
        if (str.isBlank()) resultInvalid(error = errorId) else resultValid()
    }

    val MetaId = ValidationRule<String> { str ->
        when {
            str.isBlank() -> resultInvalid(error = R.string.ext__validation__enter_package_name)
            MetaIdRegex.matches(str) -> resultValid()
            else -> resultInvalid(error = R.string.ext__validation__error_package_name, "id_regex" to MetaIdRegex)
        }
    }

    val MetaVersion = requiredTextRule(R.string.ext__validation__enter_version)
    val MetaTitle = requiredTextRule(R.string.ext__validation__enter_title)
    val MetaMaintainers = requiredTextRule(R.string.ext__validation__enter_maintainer)
    val MetaLicense = requiredTextRule(R.string.ext__validation__enter_license)

    val ComponentId = ValidationRule<String> { str ->
        when {
            str.isBlank() -> resultInvalid(error = R.string.ext__validation__enter_component_id)

            !ComponentIdRegex.matches(str) -> resultInvalid(
                error = R.string.ext__validation__error_component_id,
                "component_id_regex" to ComponentIdRegex,
            )

            else -> resultValid()
        }
    }

    val ComponentLabel = ValidationRule<String> { str ->
        when {
            str.isBlank() -> resultInvalid(error = R.string.ext__validation__enter_component_label)
            str.trimmedLength() > 30 -> resultValid(hint = R.string.ext__validation__hint_component_label_to_long)
            else -> resultValid()
        }
    }

    val ComponentAuthors = requiredTextRule(R.string.ext__validation__error_author)

    val ThemeComponentStylesheetPath = ValidationRule<String> { str ->
        when {
            str.isEmpty() -> resultValid()

            str.isBlank() -> resultInvalid(error = R.string.ext__validation__error_stylesheet_path_blank)

            SafeRelativePath.parse(str).isFailure -> resultInvalid(
                error = R.string.ext__validation__error_stylesheet_path,
                "stylesheet_path_regex" to "a safe relative path",
            )

            else -> resultValid()
        }
    }

    val ThemeComponentVariableName = ValidationRule<String> { input ->
        val str = input.trim()
        when {
            str.isBlank() -> resultInvalid(error = R.string.ext__validation__enter_property)

            !SnyggVarValue.VariableNameRegex.matches(str) -> resultInvalid(
                error = R.string.ext__validation__error_property,
                "variable_name_regex" to SnyggVarValue.VariableNameRegex,
            )

            else -> resultValid()
        }
    }

    val SnyggDpShapeValue = ValidationRule<String> { str ->
        val floatValue = str.toFloatOrNull()
        when {
            str.isBlank() -> resultInvalid(error = R.string.ext__validation__enter_dp_size)
            floatValue == null -> resultInvalid(error = R.string.ext__validation__enter_valid_number)
            floatValue < 0f -> resultInvalid(error = R.string.ext__validation__enter_positive_number)
            else -> resultValid()
        }
    }

    val SnyggPercentShapeValue = ValidationRule<String> { str ->
        val intValue = str.toIntOrNull()
        when {
            str.isBlank() -> resultInvalid(error = R.string.ext__validation__enter_percent_size)
            intValue == null -> resultInvalid(error = R.string.ext__validation__enter_valid_number)
            intValue !in 0..100 -> resultInvalid(error = R.string.ext__validation__enter_number_between_0_100)
            intValue > 50 -> resultValid(hint = R.string.ext__validation__hint_value_above_50_percent)
            else -> resultValid()
        }
    }
}
