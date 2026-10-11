/*
 * Copyright (C) 2024-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.clipboard.rememberClipboardAccessLocked
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.systemService
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

private class CandidatePointerKey(private val candidate: SuggestionCandidate) {
    override fun equals(other: Any?) = other is CandidatePointerKey && candidate === other.candidate
    override fun hashCode() = System.identityHashCode(candidate)
}

@Composable
fun CandidatesRow(candidates: List<SuggestionCandidate>, modifier: Modifier = Modifier) {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val nlpManager by context.nlpManager()
    val subtypeManager by context.subtypeManager()
    val keyguardManager = remember(context) {
        context.systemService(AndroidKeyguardManager::class)
    }
    val scope = rememberCoroutineScope()

    val displayMode by prefs.suggestion.displayMode.collectAsState()
    val matchKeyAppearance by prefs.suggestion.matchKeyAppearance.collectAsState()
    val keyboardState by keyboardManager.activeState.snapshots.collectAsState()
    val clipboardAccessLocked = rememberClipboardAccessLocked(context, keyguardManager)
    val visibleCandidates = if (clipboardAccessLocked || keyboardState.isIncognitoMode) {
        candidates.filterNot { it is ClipboardSuggestionCandidate }
    } else {
        candidates
    }
    val appearance = resolveCandidateAppearance(matchKeyAppearance, displayMode)

    SnyggRow(
        elementName = FlorisImeUi.SmartbarCandidatesRow.elementName,
        modifier = modifier
            .fillMaxSize()
            .conditional(displayMode == CandidatesDisplayMode.DYNAMIC_SCROLLABLE && visibleCandidates.size > 1) {
                florisHorizontalScroll(scrollbarHeight = CandidatesRowScrollbarHeight)
            },
        horizontalArrangement = if (visibleCandidates.size > 1) {
            Arrangement.Start
        } else {
            Arrangement.Center
        },
    ) {
        if (visibleCandidates.isNotEmpty()) {
            val candidateModifier = if (visibleCandidates.size == 1) {
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
            val list = candidatesForDisplay(visibleCandidates, displayMode)
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
                        keyboardManager.commitCandidate(candidate)
                    },
                    onLongPress = {
                        if (candidate.isEligibleForUserRemoval) {
                            scope.launch {
                                nlpManager.removeSuggestion(
                                    subtypeManager.activeSubtype,
                                    candidate,
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
internal fun CandidateItem(
    candidate: SuggestionCandidate,
    displayMode: CandidatesDisplayMode,
    appearance: CandidateAppearance,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { },
    onLongPress: () -> Boolean = { false },
    longPressDelay: Long,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentCandidate by rememberUpdatedState(candidate)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

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
            .pointerInput(CandidatePointerKey(candidate), longPressDelay) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    isPressed = true
                    try {
                        if (down.pressed != down.previousPressed) down.consume()
                        var upOrCancel: PointerInputChange? = null
                        try {
                            upOrCancel = withTimeout(longPressDelay) {
                                waitForUpOrCancellation()
                            }
                            upOrCancel?.let { if (it.pressed != it.previousPressed) it.consume() }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            if (candidate === currentCandidate && currentOnLongPress()) {
                                upOrCancel = null
                                isPressed = false
                            }
                            waitForUpOrCancellation()?.let { if (it.pressed != it.previousPressed) it.consume() }
                        }
                        if (upOrCancel != null && candidate === currentCandidate) currentOnClick()
                    } finally {
                        isPressed = false
                    }
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
