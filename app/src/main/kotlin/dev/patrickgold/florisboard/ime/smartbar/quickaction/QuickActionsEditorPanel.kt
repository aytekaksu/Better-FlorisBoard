/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.toIntOffset
import org.florisboard.lib.compose.stringRes
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggColumn
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow
import org.florisboard.lib.snygg.ui.SnyggText

private val NoopAction = QuickAction.InsertKey(TextKeyData(code = KeyCode.NOOP))
private val DragMarkerAction = QuickAction.InsertKey(TextKeyData(code = KeyCode.DRAG_MARKER))

internal enum class QuickActionSection { STICKY, DYNAMIC, HIDDEN }

internal data class QuickActionSlot(val section: QuickActionSection, val index: Int = 0)

internal data class QuickActionDragState(
    // Ordered like QuickActionSection; the sticky section always has one slot.
    private val sections: List<List<QuickAction>>,
    val activeDragAction: QuickAction? = null,
) {
    constructor(sticky: QuickAction, dynamic: List<QuickAction>, hidden: List<QuickAction>) :
        this(listOf(listOf(sticky), dynamic, hidden))

    val stickyAction get() = sections[0][0]
    val dynamicActions get() = sections[1]
    val hiddenActions get() = sections[2]

    fun slotForGridIndex(index: Int): QuickActionSlot? = when {
        index == 1 -> QuickActionSlot(QuickActionSection.STICKY)
        index in 3 until dynamicActions.size + 3 -> QuickActionSlot(QuickActionSection.DYNAMIC, index - 3)
        index in dynamicActions.size + 4 until dynamicActions.size + hiddenActions.size + 4 ->
            QuickActionSlot(QuickActionSection.HIDDEN, index - dynamicActions.size - 4)
        else -> null
    }

    private fun actionAt(slot: QuickActionSlot) = sections[slot.section.ordinal].getOrNull(slot.index)

    private fun withSection(section: QuickActionSection, actions: List<QuickAction>) =
        copy(sections = sections.toMutableList().apply { this[section.ordinal] = actions })

    private fun withAction(slot: QuickActionSlot, action: QuickAction) = withSection(slot.section,
        sections[slot.section.ordinal].toMutableList().apply { this[slot.index] = action })

    private fun withoutMarker() = copy(sections = sections.map { actions ->
        if (DragMarkerAction !in actions) actions else actions.toMutableList().apply {
            remove(DragMarkerAction)
            if (isEmpty()) add(NoopAction)
        }
    })

    fun beginDrag(slot: QuickActionSlot): QuickActionDragState {
        if (activeDragAction != null) return this
        val action = actionAt(slot)?.takeUnless { it == NoopAction } ?: return this
        return withAction(slot, DragMarkerAction).copy(activeDragAction = action)
    }

    fun moveDrag(slot: QuickActionSlot): QuickActionDragState {
        if (activeDragAction == null) return this
        val target = actionAt(slot) ?: return this
        if (target == DragMarkerAction) return this
        return if (slot.section == QuickActionSection.STICKY) {
            val displaced = if (target == NoopAction) this else
                withSection(QuickActionSection.DYNAMIC, listOf(target) + dynamicActions)
            displaced.withoutMarker().withAction(slot, DragMarkerAction)
        } else {
            val cleared = withoutMarker()
            val markerBeforeTarget = sections[slot.section.ordinal].indexOf(DragMarkerAction) in 0 until slot.index
            cleared.withSection(slot.section, cleared.sections[slot.section.ordinal].toMutableList().apply {
                // A saved NOOP may follow the marker; removal shifts that slot left.
                if (target == NoopAction) this[slot.index - if (markerBeforeTarget) 1 else 0] = DragMarkerAction
                else add(slot.index, DragMarkerAction)
            })
        }
    }

    fun completeDrag(): QuickActionDragState {
        val action = activeDragAction ?: return this
        val markerSlot = sections.indices.firstNotNullOfOrNull { section ->
            sections[section].indexOf(DragMarkerAction).takeIf { it >= 0 }
                ?.let { QuickActionSlot(QuickActionSection.entries[section], it) }
        }
        return (markerSlot?.let { withAction(it, action) } ?: this).copy(activeDragAction = null)
    }

    fun toArrangement() = sections.map { section ->
        section.filterNot { it == NoopAction || it == DragMarkerAction }
    }.let { QuickActionArrangement(it[0].firstOrNull(), it[1], it[2]) }
}

@Composable
fun QuickActionsEditorPanel() {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()

    // We get the current arrangement once and do not observe on purpose
    val actionArrangement = remember { prefs.smartbar.actionArrangement.get().withAvailableActions() }
    var dragState by remember(actionArrangement) {
        mutableStateOf(QuickActionDragState(
            actionArrangement.stickyAction ?: NoopAction,
            actionArrangement.dynamicActions.ifEmpty { listOf(NoopAction) },
            actionArrangement.hiddenActions.ifEmpty { listOf(NoopAction) },
        ))
    }

    val evaluator by keyboardManager.activeSmartbarEvaluator.collectAsState()
    val gridState = rememberLazyGridState()
    var activeDragPosition by remember { mutableStateOf(IntOffset.Zero) }
    var activeDragSize by remember { mutableStateOf(IntSize.Zero) }

    fun findItemForOffsetOrClosestInRow(offset: IntOffset): LazyGridItemInfo? {
        var closestItemInRow: LazyGridItemInfo? = null
        // Using manual for loop with indices instead of firstOrNull() because this method gets
        // called a lot and firstOrNull allocates an iterator for each call
        for (index in gridState.layoutInfo.visibleItemsInfo.indices) {
            val item = gridState.layoutInfo.visibleItemsInfo[index]
            if (offset.y in item.offset.y..(item.offset.y + item.size.height)) {
                if (offset.x in item.offset.x..(item.offset.x + item.size.width)) {
                    return item
                }
                closestItemInRow = item
            }
        }
        return closestItemInRow
    }

    fun keyOf(action: QuickAction): Any? =
        if (action.keyData().code == KeyCode.NOOP) null else action.hashCode()

    fun beginDragGesture(pos: IntOffset) {
        val item = findItemForOffsetOrClosestInRow(pos) ?: return
        val slot = dragState.slotForGridIndex(item.index) ?: return
        val started = dragState.beginDrag(slot)
        if (started === dragState) return
        dragState = started
        activeDragPosition = pos
        activeDragSize = item.size
    }

    fun handleDragGestureChange(posChange: IntOffset) {
        if (dragState.activeDragAction == null) return
        val pos = activeDragPosition + posChange
        activeDragPosition = pos
        val item = findItemForOffsetOrClosestInRow(pos) ?: return
        dragState.slotForGridIndex(item.index)?.let { dragState = dragState.moveDrag(it) }
    }

    fun completeDragGestureAndCleanUp() {
        dragState = dragState.completeDrag()
        activeDragPosition = IntOffset.Zero
        activeDragSize = IntSize.Zero
    }

    DisposableEffect(Unit) {
        onDispose {
            completeDragGestureAndCleanUp()
            val newActionArrangement = dragState.toArrangement()
            runBlocking {
                prefs.smartbar.actionArrangement.set(newActionArrangement)
            }
            if (keyboardManager.activeState.isActionsEditorVisible) {
                keyboardManager.activeState.isActionsEditorVisible = false
            }
        }
    }

    SnyggColumn(FlorisImeUi.SmartbarActionsEditor.elementName, modifier = Modifier.safeDrawingPadding()) {
        SnyggRow(
            elementName = FlorisImeUi.SmartbarActionsEditorHeader.elementName,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Extra box wrapper is needed to enforce size constraint but still allow for Snygg margin to be used
            Box(modifier = Modifier.size(48.dp)) {
                SnyggIconButton(
                    elementName = FlorisImeUi.SmartbarActionsEditorHeaderButton.elementName,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    onClick = {
                        keyboardManager.activeState.isActionsEditorVisible = false
                    },
                ) {
                    SnyggIcon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    )
                }
            }
            SnyggText(
                modifier = Modifier.weight(1f),
                text = stringRes(R.string.quick_actions_editor__header),
            )
            Spacer(Modifier.size(48.dp))
        }

        SnyggBox(FlorisImeUi.SmartbarActionsEditorTileGrid.elementName) {
            LazyVerticalGrid(
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { beginDragGesture(it.toIntOffset()) },
                            onDrag = { _, it -> handleDragGestureChange(it.toIntOffset()) },
                            onDragEnd = { completeDragGestureAndCleanUp() },
                            onDragCancel = { completeDragGestureAndCleanUp() },
                        )
                    },
                columns = GridCells.Adaptive(FlorisImeSizing.smartbarHeight * 1.8f),
                state = gridState,
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val n = if (dragState.stickyAction != NoopAction) 1 else 0
                    Subheader(
                        text = stringRes(R.string.quick_actions_editor__subheader_sticky_action, "n" to n),
                    )
                }
                item(key = keyOf(dragState.stickyAction)) {
                    QuickActionButton(
                        modifier = Modifier.animateItem(),
                        action = dragState.stickyAction,
                        evaluator = evaluator,
                        type = QuickActionBarType.EDITOR_TILE,
                    )
                }
                for ((actions, title) in listOf(
                    dragState.dynamicActions to R.string.quick_actions_editor__subheader_dynamic_actions,
                    dragState.hiddenActions to R.string.quick_actions_editor__subheader_hidden_actions,
                )) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Subheader(text = stringRes(title, "n" to actions.count { it != NoopAction }))
                    }
                    itemsIndexed(actions, key = { i, a -> keyOf(a) ?: i }) { _, action ->
                        QuickActionButton(
                            modifier = Modifier.animateItem(),
                            action = action,
                            evaluator = evaluator,
                            type = QuickActionBarType.EDITOR_TILE,
                        )
                    }
                }
            }
            if (dragState.activeDragAction != null) {
                val size = with(LocalDensity.current) {
                    remember(activeDragSize) { activeDragSize.toSize().toDpSize() }
                }
                QuickActionButton(
                    modifier = Modifier
                        .size(size)
                        .offset { activeDragPosition }
                        .offset(-size.width / 2, -size.height / 2),
                    action = dragState.activeDragAction!!,
                    evaluator = evaluator,
                    type = QuickActionBarType.EDITOR_TILE,
                )
            }
        }
    }
}

@Composable
private fun Subheader(
    text: String,
    modifier: Modifier = Modifier,
) {
    SnyggText(
        elementName = FlorisImeUi.SmartbarActionsEditorSubheader.elementName,
        modifier = modifier.fillMaxWidth(),
        text = text,
    )
}
