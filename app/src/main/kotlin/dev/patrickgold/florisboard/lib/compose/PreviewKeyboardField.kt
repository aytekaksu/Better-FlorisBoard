/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.lib.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.compose.verticalTween

private const val ANIMATION_DURATION_MS = 200

private val PreviewEnterTransition = EnterTransition.verticalTween(ANIMATION_DURATION_MS)
private val PreviewExitTransition = ExitTransition.verticalTween(ANIMATION_DURATION_MS)

val LocalPreviewFieldController = staticCompositionLocalOf<PreviewFieldController?> { null }

@Composable
fun rememberPreviewFieldController(): PreviewFieldController = remember { PreviewFieldController() }

class PreviewFieldController {
    val focusRequester = FocusRequester()
    var isVisible by mutableStateOf(false)
    var text by mutableStateOf(TextFieldValue(""))
}

@Composable
fun PreviewKeyboardField(
    controller: PreviewFieldController,
    modifier: Modifier = Modifier,
    hint: String = stringRes(R.string.settings__preview_keyboard),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = controller.isVisible,
        enter = PreviewEnterTransition,
        exit = PreviewExitTransition,
    ) {
        SelectionContainer {
            TextField(
                modifier = modifier
                    .height(56.dp)
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Back) {
                            focusManager.clearFocus()
                        }
                        false
                    }
                    .focusRequester(controller.focusRequester),
                value = controller.text,
                onValueChange = { controller.text = it },
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.ContentOrLtr),
                placeholder = {
                    Text(
                        text = hint,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                },
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            if (!InputMethodUtils.showImePicker(context)) {
                                scope.launch {
                                    context.showShortToast("Error: InputMethodManager service not available!")
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = null,
                            )
                        }
                    }
                },
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                ),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = true),
                singleLine = true,
                shape = RectangleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
