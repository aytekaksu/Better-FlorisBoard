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

package dev.patrickgold.florisboard.app.settings.advanced

import androidx.compose.ui.state.ToggleableState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BackupArchiveSelectionTest :
    FunSpec({
        test("selection maps to restore components without an independent media component") {
            Backup.Selection(
                jetprefDatastore = true,
                imeKeyboard = false,
                imeTheme = true,
                clipboardTextItems = false,
                clipboardImageItems = true,
                clipboardVideoItems = false,
            ).components() shouldBe setOf(
                BackupComponent.PREFERENCES,
                BackupComponent.THEME_EXTENSIONS,
                BackupComponent.CLIPBOARD_IMAGES,
            )
        }

        test("a new restore selection defaults only available non-clipboard components") {
            val selector = Backup.FilesSelector()

            selector.resetForRestore(
                setOf(
                    BackupComponent.THEME_EXTENSIONS,
                    BackupComponent.CLIPBOARD_TEXT,
                ),
            )

            selector.snapshot().components() shouldBe setOf(BackupComponent.THEME_EXTENSIONS)
        }

        test("clipboard tri-state only counts available clipboard components") {
            val selector = Backup.FilesSelector()
            val available = setOf(
                BackupComponent.CLIPBOARD_TEXT,
                BackupComponent.CLIPBOARD_VIDEOS,
            )

            selector.clipboardState(available) shouldBe ToggleableState.Off
            selector.clipboardTextItems = true
            selector.clipboardState(available) shouldBe ToggleableState.Indeterminate
            selector.setClipboardSelected(selected = true, availableComponents = available)
            selector.clipboardState(available) shouldBe ToggleableState.On
            selector.clipboardImageItems shouldBe false
        }
    })
