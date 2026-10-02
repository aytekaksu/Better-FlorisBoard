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

package dev.patrickgold.florisboard.ime.keyboard

import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboard

private fun placeholderKeys(count: Int) = Array(count) { TextKey(data = TextKeyData(code = 0)) }

val PlaceholderLoadingKeyboard = TextKeyboard(
    arrangement = arrayOf(
        placeholderKeys(10),
        placeholderKeys(9),
        arrayOf(
            TextKey(data = TextKeyData(code = KeyCode.SHIFT, type = KeyType.MODIFIER, label = "shift")),
            *placeholderKeys(7),
            TextKey(data = TextKeyData(code = KeyCode.DELETE, type = KeyType.ENTER_EDITING, label = "delete")),
        ),
        arrayOf(
            TextKey(data = TextKeyData(code = KeyCode.VIEW_SYMBOLS, type = KeyType.SYSTEM_GUI, label = "view_symbols")),
            *placeholderKeys(2),
            TextKey(data = TextKeyData(code = KeyCode.SPACE, label = "space")),
            *placeholderKeys(1),
            TextKey(data = TextKeyData(code = KeyCode.ENTER, type = KeyType.ENTER_EDITING, label = "enter")),
        ),
    ),
    mode = KeyboardMode.CHARACTERS,
    extendedPopupMapping = null,
    extendedPopupMappingDefault = null,
)

val SmartbarQuickActionsKeyboard = TextKeyboard(
    arrangement = emptyArray(),
    mode = KeyboardMode.SMARTBAR_QUICK_ACTIONS,
    extendedPopupMapping = null,
    extendedPopupMappingDefault = null,
)
