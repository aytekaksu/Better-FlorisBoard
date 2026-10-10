/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.keyboard

import androidx.compose.ui.unit.LayoutDirection
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.sheet.isAnyBottomSheetVisible
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Packed runtime flags and small integers for keyboard rendering and input.
 * The masks below define the layout; this value is not persisted.
 *
 * Reads, writes and field updates use this instance's reentrant monitor.
 * Snapshots are detached copies; separate property operations are not a transaction.
 */
open class KeyboardState protected constructor(
    @get:Synchronized
    @set:Synchronized
    open var rawValue: ULong,
) {
    companion object {
        const val M_KEYBOARD_MODE: ULong =                  0x0Fu
        const val O_KEYBOARD_MODE: Int =                    0
        const val M_KEY_VARIATION: ULong =                  0x0Fu
        const val O_KEY_VARIATION: Int =                    4
        const val M_INPUT_SHIFT_STATE: ULong =              0x03u
        const val O_INPUT_SHIFT_STATE: Int =                8
        const val M_IME_UI_MODE: ULong =                    0x07u
        const val O_IME_UI_MODE: Int =                      24

        const val F_IS_SELECTION_MODE: ULong =              0x00000400u
        const val F_IS_MANUAL_SELECTION_MODE: ULong =       0x00000800u
        const val F_IS_MANUAL_SELECTION_MODE_START: ULong = 0x00001000u
        const val F_IS_MANUAL_SELECTION_MODE_END: ULong =   0x00002000u
        const val F_IS_INCOGNITO_MODE: ULong =              0x00008000u
        const val F_IS_ACTIONS_OVERFLOW_VISIBLE: ULong =    0x00010000u
        const val F_IS_ACTIONS_EDITOR_VISIBLE: ULong =      0x00020000u
        const val F_IS_COMPOSING_ENABLED: ULong =           0x00100000u

        const val F_IS_CHAR_HALF_WIDTH: ULong =             0x00200000u
        const val F_IS_KANA_KATA: ULong =                   0x00400000u

        const val F_IS_RTL_LAYOUT_DIRECTION: ULong =        0x08000000u

        const val F_IS_SUBTYPE_SELECTION_VISIBLE: ULong =   0x1_0000_0000u

        const val F_DEBUG_SHOW_DRAG_AND_DROP_HELPERS =      0x01_00_00_00_00_00_00_00uL

        const val STATE_ALL_ZERO: ULong =                   0uL

        fun new(value: ULong = STATE_ALL_ZERO) = KeyboardState(value)
    }

    @Synchronized
    fun snapshot(): KeyboardState {
        return new(rawValue)
    }

    private fun getFlag(f: ULong): Boolean {
        return (rawValue and f) != STATE_ALL_ZERO
    }

    @Synchronized
    private fun setFlag(f: ULong, v: Boolean) {
        rawValue = if (v) { rawValue or f } else { rawValue and f.inv() }
    }

    private fun getRegion(m: ULong, o: Int): Int {
        return ((rawValue shr o) and m).toInt()
    }

    @Synchronized
    private fun setRegion(m: ULong, o: Int, v: Int) {
        rawValue = (rawValue and (m shl o).inv()) or ((v.toULong() and m) shl o)
    }

    override fun hashCode(): Int {
        return rawValue.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is KeyboardState && other.rawValue == rawValue
    }

    override fun toString(): String {
        return "0x" + rawValue.toString(16).padStart(16, '0')
    }

    var keyVariation: KeyVariation
        get() = KeyVariation.fromInt(getRegion(M_KEY_VARIATION, O_KEY_VARIATION))
        set(v) { setRegion(M_KEY_VARIATION, O_KEY_VARIATION, v.toInt()) }

    var keyboardMode: KeyboardMode
        get() = KeyboardMode.fromInt(getRegion(M_KEYBOARD_MODE, O_KEYBOARD_MODE))
        set(v) { setRegion(M_KEYBOARD_MODE, O_KEYBOARD_MODE, v.toInt()) }

    var inputShiftState: InputShiftState
        get() = InputShiftState.fromInt(getRegion(M_INPUT_SHIFT_STATE, O_INPUT_SHIFT_STATE))
        set(v) { setRegion(M_INPUT_SHIFT_STATE, O_INPUT_SHIFT_STATE, v.toInt()) }

    var imeUiMode: ImeUiMode
        get() = ImeUiMode.fromInt(getRegion(M_IME_UI_MODE, O_IME_UI_MODE))
        set(v) { setRegion(M_IME_UI_MODE, O_IME_UI_MODE, v.toInt()) }

    var layoutDirection: LayoutDirection
        get() = if (getFlag(F_IS_RTL_LAYOUT_DIRECTION)) LayoutDirection.Rtl else LayoutDirection.Ltr
        set(v) { setFlag(F_IS_RTL_LAYOUT_DIRECTION, v == LayoutDirection.Rtl) }

    val isUppercase: Boolean
        get() = inputShiftState != InputShiftState.UNSHIFTED

    var isSelectionMode: Boolean
        get() = getFlag(F_IS_SELECTION_MODE)
        set(v) { setFlag(F_IS_SELECTION_MODE, v) }

    var isManualSelectionMode: Boolean
        get() = getFlag(F_IS_MANUAL_SELECTION_MODE)
        set(v) { setFlag(F_IS_MANUAL_SELECTION_MODE, v) }

    var isManualSelectionModeStart: Boolean
        get() = getFlag(F_IS_MANUAL_SELECTION_MODE_START)
        set(v) { setFlag(F_IS_MANUAL_SELECTION_MODE_START, v) }

    var isManualSelectionModeEnd: Boolean
        get() = getFlag(F_IS_MANUAL_SELECTION_MODE_END)
        set(v) { setFlag(F_IS_MANUAL_SELECTION_MODE_END, v) }

    var isIncognitoMode: Boolean
        get() = getFlag(F_IS_INCOGNITO_MODE)
        set(v) { setFlag(F_IS_INCOGNITO_MODE, v) }

    var isActionsOverflowVisible: Boolean
        get() = getFlag(F_IS_ACTIONS_OVERFLOW_VISIBLE)
        set(v) { setFlag(F_IS_ACTIONS_OVERFLOW_VISIBLE, v) }

    var isActionsEditorVisible: Boolean
        get() = getFlag(F_IS_ACTIONS_EDITOR_VISIBLE)
        set(v) { setFlag(F_IS_ACTIONS_EDITOR_VISIBLE, v) }

    var isSubtypeSelectionVisible: Boolean
        get() = getFlag(F_IS_SUBTYPE_SELECTION_VISIBLE)
        set(v) { setFlag(F_IS_SUBTYPE_SELECTION_VISIBLE, v) }

    var isComposingEnabled: Boolean
        get() = getFlag(F_IS_COMPOSING_ENABLED)
        set(v) { setFlag(F_IS_COMPOSING_ENABLED, v) }

    var isKanaKata: Boolean
        get() = getFlag(F_IS_KANA_KATA)
        set(v) { setFlag(F_IS_KANA_KATA, v) }

    var isCharHalfWidth: Boolean
        get() = getFlag(F_IS_CHAR_HALF_WIDTH)
        set(v) { setFlag(F_IS_CHAR_HALF_WIDTH, v) }

    var debugShowDragAndDropHelpers: Boolean
        get() = getFlag(F_DEBUG_SHOW_DRAG_AND_DROP_HELPERS)
        set(v) { setFlag(F_DEBUG_SHOW_DRAG_AND_DROP_HELPERS, v) }
}

internal fun KeyboardState.setManualSelectionEndpoint(isStart: Boolean) {
    isManualSelectionModeStart = isStart
    isManualSelectionModeEnd = !isStart
}

internal val KeyboardState.manualSelectionEndpointIsStart: Boolean?
    get() = isManualSelectionModeStart.takeIf {
        isManualSelectionModeStart != isManualSelectionModeEnd
    }

/** Publishes detached snapshots. Batches delay publication, not other threads' writes. */
class ObservableKeyboardState private constructor(
    initValue: ULong,
    private val dispatchFlow: MutableStateFlow<KeyboardState> = MutableStateFlow(KeyboardState.new(initValue)),
) : KeyboardState(initValue), StateFlow<KeyboardState> by dispatchFlow {

    companion object {
        const val BATCH_ZERO: Int = 0

        fun new(value: ULong = STATE_ALL_ZERO) = ObservableKeyboardState(value)
    }

    override var rawValue: ULong
        @Synchronized get() = super.rawValue
        @Synchronized set(value) {
            if (super.rawValue != value) {
                super.rawValue = value
                dispatchState()
            }
        }
    private var batchEditCount = BATCH_ZERO

    // Keep publication with the write so an older snapshot cannot replace a newer one.
    // Synchronous collectors may re-enter, but must not wait for another thread to access this state.
    @Synchronized
    private fun dispatchState() {
        if (batchEditCount == BATCH_ZERO) {
            dispatchFlow.value = this.snapshot()
        }
    }

    /** Delays publication until all nested or overlapping batches end. Callable from any thread. */
    @Synchronized
    fun beginBatchEdit() {
        batchEditCount++
    }

    /** Pairs with one [beginBatchEdit] and publishes when no batches remain. Callable from any thread. */
    @Synchronized
    fun endBatchEdit() {
        batchEditCount--
        dispatchState()
    }

    /** Does not wrap [block] in a lock; always ends the batch, even if [block] throws. */
    inline fun batchEdit(block: (ObservableKeyboardState) -> Unit) {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        beginBatchEdit()
        try {
            block(this)
        } finally {
            endBatchEdit()
        }
    }
}

fun KeyboardState.isFullscreenInputRequired(): Boolean {
    return isAnyBottomSheetVisible()
}
