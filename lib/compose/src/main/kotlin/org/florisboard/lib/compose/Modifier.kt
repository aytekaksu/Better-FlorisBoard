/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package org.florisboard.lib.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun Modifier.conditional(
    condition: Boolean,
    modifier: @Composable Modifier.() -> Modifier,
): Modifier =
    if (condition) then(modifier(Modifier)) else this

@Composable
fun Modifier.fold(
    condition: Boolean,
    ifTrue: @Composable () -> Modifier,
    ifFalse: @Composable () -> Modifier,
): Modifier =
    if (condition) then(ifTrue()) else then(ifFalse())

@Composable
inline fun <reified T : Any> Modifier.ifIsInstance(
    value: Any,
    modifier: @Composable (T) -> Modifier,
): Modifier =
    if (value is T) then(modifier(value)) else this
