/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.PreferenceLayout
import dev.patrickgold.jetpref.material.ui.ColorRepresentation
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import org.florisboard.lib.compose.stringRes

private val FineTuneContentPadding = PaddingValues(horizontal = 8.dp)

@Composable
fun FineTuneDialog(onDismiss: () -> Unit) {
    JetPrefAlertDialog(
        title = stringRes(R.string.settings__theme_editor__fine_tune__title),
        onDismiss = onDismiss,
        contentPadding = FineTuneContentPadding,
    ) {
        PreferenceLayout(FlorisPreferenceStore, iconSpaceReserved = false) {
            ListPreference(
                listPref = prefs.theme.editorLevel,
                title = stringRes(R.string.settings__theme_editor__fine_tune__level),
                entries = enumDisplayEntriesOf(SnyggLevel::class),
            )
            ListPreference(
                listPref = prefs.theme.editorColorRepresentation,
                title = stringRes(R.string.settings__theme_editor__fine_tune__color_representation),
                entries = enumDisplayEntriesOf(ColorRepresentation::class),
            )
            ListPreference(
                listPref = prefs.theme.editorDisplayKbdAfterDialogs,
                title = stringRes(R.string.settings__theme_editor__fine_tune__display_kbd_after_dialogs),
                entries = enumDisplayEntriesOf(DisplayKbdAfterDialogs::class),
            )
        }
    }
}
