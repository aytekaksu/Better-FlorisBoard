/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.smartbar

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.conditional
import org.florisboard.lib.compose.florisHorizontalScroll
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggSpacer
import org.florisboard.lib.snygg.ui.SnyggText
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

val CandidatesRowScrollbarHeight = 2.dp

internal data class CandidateAppearance(
    val useKeyStyle: Boolean,
    val fontSizeScale: Float,
    val useKeyColoredClassicSeparator: Boolean,
)

internal fun resolveCandidateAppearance(
    matchKeyAppearance: Boolean,
    displayMode: CandidatesDisplayMode,
): CandidateAppearance = if (matchKeyAppearance) {
    CandidateAppearance(
        useKeyStyle = true,
        fontSizeScale = 1.125f,
        useKeyColoredClassicSeparator = displayMode == CandidatesDisplayMode.CLASSIC,
    )
} else {
    CandidateAppearance(
        useKeyStyle = false,
        fontSizeScale = 1.0f,
        useKeyColoredClassicSeparator = false,
    )
}

internal fun <T> candidatesForDisplay(
    candidates: List<T>,
    displayMode: CandidatesDisplayMode,
) = if (displayMode == CandidatesDisplayMode.CLASSIC) candidates.take(3) else candidates

@Composable
fun CandidatesRow(modifier: Modifier = Modifier) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val nlpManager by context.nlpManager()
    val subtypeManager by context.subtypeManager()
    val scope = rememberCoroutineScope()

    val displayMode by prefs.suggestion.displayMode.collectAsState()
    val matchKeyAppearance by prefs.suggestion.matchKeyAppearance.collectAsState()
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()
    val appearance = resolveCandidateAppearance(matchKeyAppearance, displayMode)

    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier
            .fillMaxSize()
            .conditional(displayMode == CandidatesDisplayMode.DYNAMIC_SCROLLABLE && candidates.size > 1) {
                florisHorizontalScroll(scrollbarHeight = CandidatesRowScrollbarHeight)
            },
        horizontalArrangement = if (candidates.size > 1) {
            Arrangement.Start
        } else {
            Arrangement.Center
        },
    ) {
        if (candidates.isNotEmpty()) {
            val candidateModifier = if (candidates.size == 1) {
                Modifier
                    .fillMaxHeight()
                    .weight(1f, fill = false)
            } else {
                Modifier
                    .fillMaxHeight()
                    .conditional(displayMode == CandidatesDisplayMode.CLASSIC) {
                        weight(1f)
                    }
                    .conditional(displayMode != CandidatesDisplayMode.CLASSIC) {
                        wrapContentWidth().widthIn(max = 160.dp)
                    }
            }
            val list = candidatesForDisplay(candidates, displayMode)
            for ((n, candidate) in list.withIndex()) {
                if (n > 0) {
                    val separatorModifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(if (appearance.useKeyColoredClassicSeparator) 0.7f else 0.6f)
                        .align(Alignment.CenterVertically)
                    if (appearance.useKeyColoredClassicSeparator) {
                        val keyStyle = rememberSnyggThemeQuery(FlorisImeUi.Key.elementName)
                        Spacer(
                            modifier = separatorModifier.background(
                                keyStyle.foreground(Color.White).copy(alpha = 0.7f)
                            ),
                        )
                    } else {
                        SnyggSpacer(
                            elementName = FlorisImeUi.SmartbarCandidateSpacer.elementName,
                            modifier = separatorModifier,
                        )
                    }
                }
                CandidateItem(
                    modifier = candidateModifier,
                    candidate = candidate,
                    displayMode = displayMode,
                    appearance = appearance,
                    onClick = {
                        // Can't use candidate directly
                        keyboardManager.commitCandidate(candidates[n])
                    },
                    onLongPress = {
                        // Can't use candidate directly
                        val candidateItem = candidates[n]
                        if (candidateItem.isEligibleForUserRemoval) {
                            scope.launch {
                                nlpManager.removeSuggestion(
                                    subtypeManager.activeSubtype,
                                    candidateItem,
                                )
                            }
                            true
                        } else {
                            false
                        }
                    },
                    longPressDelay = prefs.keyboard.longPressDelay.get().toLong(),
                )
            }
        }
    }
}

@Composable
private fun CandidateItem(
    candidate: SuggestionCandidate,
    displayMode: CandidatesDisplayMode,
    appearance: CandidateAppearance,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { },
    onLongPress: () -> Boolean = { false },
    longPressDelay: Long,
) = with(LocalDensity.current) {
    var isPressed by remember { mutableStateOf(false) }

    val elementName = if (candidate is ClipboardSuggestionCandidate) {
        FlorisImeUi.SmartbarCandidateClip
    } else {
        FlorisImeUi.SmartbarCandidateWord
    }.elementName
    val attributes = mapOf(
        "auto-commit" to if (candidate.isEligibleForAutoCommit) 1 else 0,
        "kind" to candidate.kind.name.lowercase(),
    )
    val selector = if (isPressed) SnyggSelector.PRESSED else SnyggSelector.NONE

    SnyggRow(
        elementName = elementName,
        attributes = attributes,
        selector = selector,
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    isPressed = true
                    if (down.pressed != down.previousPressed) down.consume()
                    var upOrCancel: PointerInputChange? = null
                    try {
                        upOrCancel = withTimeout(longPressDelay) {
                            waitForUpOrCancellation()
                        }
                        upOrCancel?.let { if (it.pressed != it.previousPressed) it.consume() }
                    } catch (_: PointerEventTimeoutCancellationException) {
                        if (onLongPress()) {
                            upOrCancel = null
                            isPressed = false
                        }
                        waitForUpOrCancellation()?.let { if (it.pressed != it.previousPressed) it.consume() }
                    }
                    if (upOrCancel != null) {
                        onClick()
                    }
                    isPressed = false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (candidate.icon != null) {
            SnyggBox(
                elementName = "$elementName-icon",
                attributes = attributes,
                selector = selector,
            ) {
                SnyggIcon(imageVector = candidate.icon!!)
            }
        }
        SnyggColumn(
            modifier = if (displayMode == CandidatesDisplayMode.CLASSIC) Modifier.weight(1f) else Modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnyggText(
                elementName = "$elementName-text",
                attributes = attributes,
                selector = selector,
                text = candidate.text.toString(),
                contentStyleElementName = FlorisImeUi.Key.elementName.takeIf { appearance.useKeyStyle },
                fontSizeScale = appearance.fontSizeScale,
            )
            if (candidate.secondaryText != null) {
                SnyggText(
                    elementName = "$elementName-secondary-text",
                    attributes = attributes,
                    selector = selector,
                    text = candidate.secondaryText!!.toString(),
                )
            }
        }
    }
}
