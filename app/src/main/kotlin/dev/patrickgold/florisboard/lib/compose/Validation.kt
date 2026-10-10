/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.lib.ValidationResult

@Composable
fun Validation(showValidationErrors: Boolean, validationResult: ValidationResult?) {
    if (showValidationErrors) {
        if (validationResult is ValidationResult.Valid && validationResult.hasHintMessage()) {
            Text(
                text = validationResult.hintMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
            )
        }
        if (validationResult is ValidationResult.Invalid) {
            Text(
                text = validationResult.errorMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
