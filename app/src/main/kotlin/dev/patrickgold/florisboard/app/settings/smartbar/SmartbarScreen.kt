/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.smartbar

import androidx.compose.runtime.Composable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.ime.smartbar.CandidatesDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.ExtendedActionsPlacement
import dev.patrickgold.florisboard.ime.smartbar.SharedActionsTransitionMode
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.ime.smartbar.SmartbarMotionMode
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.stringRes

@Composable
fun SmartbarScreen() = FlorisScreen {
    title = stringRes(R.string.settings__smartbar__title)
    previewFieldVisible = true

    content {
        SwitchPreference(
            prefs.smartbar.enabled,
            title = stringRes(R.string.pref__smartbar__enabled__label),
            summary = stringRes(R.string.pref__smartbar__enabled__summary),
        )
        ListPreference(
            listPref = prefs.smartbar.layout,
            title = stringRes(R.string.pref__smartbar__layout__label),
            entries = enumDisplayEntriesOf(SmartbarLayout::class),
            enabledIf = { prefs.smartbar.enabled isEqualTo true },
        )
        ListPreference(
            listPref = prefs.smartbar.motionMode,
            title = stringRes(R.string.pref__smartbar__motion_mode__label),
            entries = enumDisplayEntriesOf(SmartbarMotionMode::class),
            enabledIf = { prefs.smartbar.enabled isEqualTo true },
        )

        PreferenceGroup(title = stringRes(R.string.pref__smartbar__group_layout_specific__label)) {
            ListPreference(
                prefs.suggestion.displayMode,
                title = stringRes(R.string.pref__suggestion__display_mode__label),
                entries = enumDisplayEntriesOf(CandidatesDisplayMode::class),
                enabledIf = { prefs.smartbar.enabled isEqualTo true },
                visibleIf = { prefs.smartbar.layout isNotEqualTo SmartbarLayout.ACTIONS_ONLY },
            )
            SwitchPreference(
                prefs.suggestion.matchKeyAppearance,
                title = stringRes(R.string.pref__suggestion__match_key_appearance__label),
                enabledIf = { prefs.smartbar.enabled isEqualTo true },
                visibleIf = { prefs.smartbar.layout isNotEqualTo SmartbarLayout.ACTIONS_ONLY },
            )
            SwitchPreference(
                prefs.smartbar.flipToggles,
                title = stringRes(R.string.pref__smartbar__flip_toggles__label),
                summary = stringRes(R.string.pref__smartbar__flip_toggles__summary),
                enabledIf = { prefs.smartbar.enabled isEqualTo true },
                visibleIf = {
                    prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED ||
                    prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
                },
            )
            Preference(
                title = stringRes(R.string.pref__smartbar__shared_actions_auto_expand_collapse__label),
                summary = stringRes(R.string.pref__smartbar__shared_actions_auto_expand_collapse__summary),
                enabledIf = { false },
                visibleIf = { prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED },
            )
            ListPreference(
                prefs.smartbar.sharedActionsTransitionMode,
                title = stringRes(R.string.pref__smartbar__shared_actions_transition_mode__label),
                entries = enumDisplayEntriesOf(SharedActionsTransitionMode::class),
                enabledIf = { prefs.smartbar.enabled isEqualTo true },
                visibleIf = { prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED },
            )
            ListPreference(
                listPref = prefs.smartbar.extendedActionsPlacement,
                title = stringRes(R.string.pref__smartbar__extended_actions_placement__label),
                entries = enumDisplayEntriesOf(ExtendedActionsPlacement::class),
                enabledIf = { prefs.smartbar.enabled isEqualTo true },
                visibleIf = { prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED },
            )
        }
    }
}
