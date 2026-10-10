/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.app.settings.about

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.chipColors
import com.mikepenz.aboutlibraries.ui.compose.m3.libraryColors
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import org.florisboard.lib.compose.florisScrollbar
import org.florisboard.lib.compose.stringRes

@Composable
fun ThirdPartyLicensesScreen() = FlorisScreen {
    title = stringRes(R.string.about__third_party_licenses__title)
    scrollable = false
    iconSpaceReserved = false

    val lazyListState = rememberLazyListState()
    val libs by produceLibraries(R.raw.aboutlibraries)

    content {
        LibrariesContainer(
            libraries = libs,
            modifier = Modifier
                .fillMaxSize()
                .florisScrollbar(lazyListState),
            colors = LibraryDefaults.libraryColors(
                libraryBackgroundColor = MaterialTheme.colorScheme.background,
                licenseChipColors = LibraryDefaults.chipColors(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                libraryContentColor = MaterialTheme.colorScheme.onBackground,
                dialogConfirmButtonColor = MaterialTheme.colorScheme.primary,
            ),
            lazyListState = lazyListState,
        )
    }
}
