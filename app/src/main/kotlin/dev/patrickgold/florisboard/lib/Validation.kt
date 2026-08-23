/*
 * Copyright (C) 2022-2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.lib

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.kotlin.CurlyArg
import org.florisboard.lib.kotlin.curlyFormat
import kotlin.contracts.contract

sealed class ValidationResult {
    companion object {
        fun resultValid(): ValidationResult = Valid()

        fun resultValid(@StringRes hint: Int): ValidationResult = Valid(hint)

        fun resultInvalid(@StringRes error: Int, vararg args: CurlyArg): ValidationResult =
            Invalid(error, args.asList())
    }

    class Valid(@param:StringRes private val hintMessageId: Int? = null) : ValidationResult() {
        fun hasHintMessage() = hintMessageId != null

        @Composable
        fun hintMessage() = hintMessageId?.let { stringRes(it) }.orEmpty()
    }

    class Invalid(@param:StringRes private val errorMessageId: Int, private val args: List<CurlyArg>) :
        ValidationResult() {
        @Composable
        fun errorMessage() = stringRes(errorMessageId).curlyFormat(args)
    }

    fun isValid(): Boolean {
        contract {
            returns(true) implies (this@ValidationResult is Valid)
        }
        return this is Valid
    }

    fun isInvalid(): Boolean {
        contract {
            returns(true) implies (this@ValidationResult is Invalid)
        }
        return this is Invalid
    }
}

class ValidationRule<T : Any>(private val validator: ValidationResult.Companion.(T) -> ValidationResult) {
    fun validate(value: T) = validator.invoke(ValidationResult.Companion, value)
}

@Composable
fun <T : Any> rememberValidationResult(rule: ValidationRule<T>, value: T): ValidationResult =
    remember(rule, value) { rule.validate(value) }
