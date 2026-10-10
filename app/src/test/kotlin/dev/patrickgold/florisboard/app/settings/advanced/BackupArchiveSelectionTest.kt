/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.advanced

import androidx.compose.ui.state.ToggleableState
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BackupArchiveSelectionTest :
    FunSpec({
        test("selection maps to restore components without an independent media component") {
            val selector = Backup.FilesSelector()
            selector.snapshot() shouldBe setOf(
                BackupComponent.PREFERENCES,
                BackupComponent.KEYBOARD_EXTENSIONS,
                BackupComponent.THEME_EXTENSIONS,
            )
            selector.toggle(BackupComponent.KEYBOARD_EXTENSIONS)
            selector.toggle(BackupComponent.CLIPBOARD_IMAGES)
            selector.snapshot() shouldBe setOf(
                BackupComponent.PREFERENCES,
                BackupComponent.THEME_EXTENSIONS,
                BackupComponent.CLIPBOARD_IMAGES,
            )
            selector.snapshot().clipboardItemTypes() shouldBe setOf(ItemType.IMAGE)
        }

        test("a new restore selection defaults only available non-clipboard components") {
            val selector = Backup.FilesSelector()
            selector.toggle(BackupComponent.CLIPBOARD_IMAGES)

            selector.resetForRestore(
                setOf(
                    BackupComponent.THEME_EXTENSIONS,
                    BackupComponent.CLIPBOARD_TEXT,
                ),
            )

            selector.snapshot() shouldBe setOf(BackupComponent.THEME_EXTENSIONS)
            selector.isSelected(BackupComponent.CLIPBOARD_IMAGES) shouldBe false
        }

        test("clipboard tri-state only counts available clipboard components") {
            val selector = Backup.FilesSelector()
            val available = setOf(
                BackupComponent.CLIPBOARD_TEXT,
                BackupComponent.CLIPBOARD_VIDEOS,
            )

            selector.clipboardState(available) shouldBe ToggleableState.Off
            selector.toggle(BackupComponent.CLIPBOARD_TEXT)
            selector.clipboardState(available) shouldBe ToggleableState.Indeterminate
            selector.setClipboardSelected(selected = true, availableComponents = available)
            selector.clipboardState(available) shouldBe ToggleableState.On
            selector.isSelected(BackupComponent.CLIPBOARD_IMAGES) shouldBe false
            selector.toggle(BackupComponent.CLIPBOARD_IMAGES)
            selector.setClipboardSelected(selected = false, availableComponents = available)
            selector.clipboardState(available) shouldBe ToggleableState.Off
            selector.isSelected(BackupComponent.CLIPBOARD_IMAGES) shouldBe true
            selector.clipboardState(emptySet()) shouldBe ToggleableState.Off
        }

        test("snapshots are immutable and stay unchanged after later selection changes") {
            val selector = Backup.FilesSelector()
            val snapshot = selector.snapshot()
            shouldThrow<UnsupportedOperationException> {
                (snapshot as MutableSet<BackupComponent>).clear()
            }
            selector.toggle(BackupComponent.PREFERENCES)
            snapshot shouldBe setOf(
                BackupComponent.PREFERENCES,
                BackupComponent.KEYBOARD_EXTENSIONS,
                BackupComponent.THEME_EXTENSIONS,
            )
            selector.snapshot() shouldBe snapshot - BackupComponent.PREFERENCES
        }
    })
