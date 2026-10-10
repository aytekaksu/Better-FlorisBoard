/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import org.florisboard.lib.compose.stringRes
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

@Composable
fun SelectSubtypePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val subtypeManager by context.subtypeManager()

    val listState = rememberLazyListState()
    val subtypes by subtypeManager.subtypesFlow.collectAsState()

    val currentlySelected = subtypeManager.activeSubtype.id

    SnyggColumn(FlorisImeUi.SubtypePanel.elementName, modifier = modifier.safeDrawingPadding()) {
        SnyggRow(
            elementName = FlorisImeUi.SubtypePanelHeader.elementName,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SnyggText(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(false) {},
                text = stringRes(R.string.select_subtype_panel__header),
            )
        }

        SnyggBox(FlorisImeUi.SubtypePanelList.elementName) {
            LazyColumn(
                state = listState,
            ) {
                items(
                    subtypes,
                    key = {
                        it.id
                    }
                ) {
                    val selector = SnyggSelector.NONE
                    val interactionSource = remember { MutableInteractionSource() }
                    SnyggRow(
                        elementName = FlorisImeUi.SubtypePanelListItem.elementName,
                        selector = selector,
                        modifier = Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = ripple(),
                            enabled = true,
                            onClickLabel = null,
                            role = null,
                            onClick = {
                                subtypeManager.switchToSubtypeById(it.id)
                                keyboardManager.activeState.isSubtypeSelectionVisible = false
                            },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SnyggBox(
                            elementName = FlorisImeUi.SubtypePanelListItemIconLeading.elementName,
                            selector = selector,
                        ) {
                            SnyggIcon(
                                imageVector = if (currentlySelected == it.id) {
                                    Icons.Default.RadioButtonChecked
                                } else {
                                    Icons.Default.RadioButtonUnchecked
                                },
                            )
                        }
                        SnyggText(
                            elementName = FlorisImeUi.SubtypePanelListItemText.elementName,
                            selector = selector,
                            modifier = Modifier.fillMaxWidth(),
                            text = it.primaryLocale.displayName(),
                        )
                    }
                }
            }
        }
    }
}
