/*
 * Copyright (C) 2025-2026 The FlorisBoard Contributors
 * SPDX-License-Identifier: Apache-2.0
 * Modified in Better FlorisBoard: license header standardized.
 */

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.window.ExpectedDpChange.NOT_GREATER
import dev.patrickgold.florisboard.ime.window.ExpectedDpChange.NOT_LESS
import dev.patrickgold.florisboard.ime.window.ExpectedDpChange.SAME
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class ImeWindowControllerEditorMoveTest :
    FunSpec({
        val tolerance = 1e-3f.dp

        coroutineTestScope = true

        test("floating move docks at and below the threshold before config saving runs") {
            for (atThreshold in listOf(false, true)) {
                val fixture = floatingEditorFixture()
                try {
                    val offsetBottom = if (atThreshold) fixture.spec.constraints.dockToFixedHeight else 0.dp
                    val docked = fixture.spec.copy(props = fixture.spec.props.copy(offsetBottom = offsetBottom))

                    fixture.controller.editor.beginMoveGesture()
                    fixture.controller.editor.onSpecUpdated(docked)
                    fixture.controller.editor.endMoveGesture(docked)

                    fixture.controller.editor.state.value shouldBe ImeWindowController.EditorState.INACTIVE
                    fixture.controller.activeWindowConfig.value.mode shouldBe ImeWindowMode.FLOATING
                    fixture.scheduler.runCurrent()
                    fixture.controller.activeWindowConfig.value.mode shouldBe ImeWindowMode.FIXED
                    fixture.prefs.keyboard.windowConfig.get()[fixture.rootInsets.formFactor.typeGuess]?.mode shouldBe
                        ImeWindowMode.FIXED
                } finally {
                    fixture.scope.cancel()
                }
            }
        }

        test("moving above the dock threshold keeps editing active and saves floating props") {
            val fixture = floatingEditorFixture()
            try {
                val moved = fixture.spec.copy(
                    props = fixture.spec.props.copy(
                        offsetBottom = fixture.spec.constraints.dockToFixedHeight + 20.dp,
                    ),
                )

                fixture.controller.editor.beginMoveGesture()
                fixture.controller.editor.onSpecUpdated(moved)
                fixture.controller.editor.endMoveGesture(moved)

                fixture.controller.editor.state.value shouldBe ImeWindowController.EditorState.ACTIVE
                fixture.scheduler.runCurrent()
                fixture.controller.activeWindowConfig.value.mode shouldBe ImeWindowMode.FLOATING
                fixture.prefs.keyboard.windowConfig.get()[fixture.rootInsets.formFactor.typeGuess]
                    ?.floatingProps?.get(moved.floatingMode) shouldBe moved.props
            } finally {
                fixture.scope.cancel()
            }
        }

        context("for all root insets and fixed modes") {
            test("for all upward moves") {
                checkAll(
                    Arb.rootInsetsWithUpwardOffset(),
                    Arb.enum<ImeWindowMode.Fixed>(),
                ) { (rootInsets, offset), fixedMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = fixedMode),
                        backgroundScope,
                    ) { it.movedBy(offset, 4, 0) }
                        .assertAppliedGesture<ImeWindowSpec.Fixed> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.paddingLeft.shouldMatch(before.paddingLeft, SAME, tolerance, "padding left")
                            after.paddingRight.shouldMatch(before.paddingRight, SAME, tolerance, "padding right")
                            after.paddingBottom.shouldMatch(before.paddingBottom, NOT_LESS, tolerance, "padding bottom")
                            withClue("move operations upward must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all downward moves") {
                checkAll(
                    Arb.rootInsetsWithDownwardOffset(),
                    Arb.enum<ImeWindowMode.Fixed>(),
                ) { (rootInsets, offset), fixedMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = fixedMode),
                        backgroundScope,
                    ) { it.movedBy(offset, 4, 0) }
                        .assertAppliedGesture<ImeWindowSpec.Fixed> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.paddingLeft.shouldMatch(before.paddingLeft, SAME, tolerance, "padding left")
                            after.paddingRight.shouldMatch(before.paddingRight, SAME, tolerance, "padding right")
                            after.paddingBottom.shouldMatch(
                                before.paddingBottom,
                                NOT_GREATER,
                                tolerance,
                                "padding bottom",
                            )
                            withClue("move operations downward must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all leftward moves") {
                checkAll(
                    Arb.rootInsetsWithLeftwardOffset(),
                    Arb.enum<ImeWindowMode.Fixed>(),
                ) { (rootInsets, offset), fixedMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = fixedMode),
                        backgroundScope,
                    ) { it.movedBy(offset, 4, 0) }
                        .assertAppliedGesture<ImeWindowSpec.Fixed> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.paddingLeft.shouldMatch(before.paddingLeft, NOT_GREATER, tolerance, "padding left")
                            after.paddingRight.shouldMatch(before.paddingRight, NOT_LESS, tolerance, "padding right")
                            after.paddingBottom.shouldMatch(before.paddingBottom, SAME, tolerance, "padding bottom")
                            withClue("move operations leftward must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all rightward moves") {
                checkAll(
                    Arb.rootInsetsWithRightwardOffset(),
                    Arb.enum<ImeWindowMode.Fixed>(),
                ) { (rootInsets, offset), fixedMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FIXED, fixedMode = fixedMode),
                        backgroundScope,
                    ) { it.movedBy(offset, 4, 0) }
                        .assertAppliedGesture<ImeWindowSpec.Fixed> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.paddingLeft.shouldMatch(before.paddingLeft, NOT_LESS, tolerance, "padding left")
                            after.paddingRight.shouldMatch(before.paddingRight, NOT_GREATER, tolerance, "padding right")
                            after.paddingBottom.shouldMatch(before.paddingBottom, SAME, tolerance, "padding bottom")
                            withClue("move operations rightward must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }
        }

        context("for all root insets and floating modes") {
            test("for all upward moves") {
                checkAll(
                    Arb.rootInsetsWithUpwardOffset(),
                    Arb.enum<ImeWindowMode.Floating>(),
                ) { (rootInsets, offset), floatingMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FLOATING, floatingMode = floatingMode),
                        backgroundScope,
                    ) { it.movedBy(offset, 4, 0) }
                        .assertAppliedGesture<ImeWindowSpec.Floating> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.keyboardWidth.shouldMatch(before.keyboardWidth, SAME, tolerance, "keyboard width")
                            after.offsetLeft.shouldMatch(before.offsetLeft, SAME, tolerance, "offset left")
                            after.offsetBottom.shouldMatch(before.offsetBottom, NOT_LESS, tolerance, "offset bottom")
                            withClue("move operations upward must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all downward moves") {
                checkAll(
                    Arb.rootInsetsWithDownwardOffset(),
                    Arb.enum<ImeWindowMode.Floating>(),
                ) { (rootInsets, offset), floatingMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FLOATING, floatingMode = floatingMode),
                        backgroundScope,
                    ) { it.movedBy(offset, 4, 0) }
                        .assertAppliedGesture<ImeWindowSpec.Floating> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.keyboardWidth.shouldMatch(before.keyboardWidth, SAME, tolerance, "keyboard width")
                            after.offsetLeft.shouldMatch(before.offsetLeft, SAME, tolerance, "offset left")
                            after.offsetBottom.shouldMatch(before.offsetBottom, NOT_GREATER, tolerance, "offset bottom")
                            withClue("move operations downward must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all leftward moves") {
                checkAll(
                    Arb.rootInsetsWithLeftwardOffset(),
                    Arb.enum<ImeWindowMode.Floating>(),
                ) { (rootInsets, offset), floatingMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FLOATING, floatingMode = floatingMode),
                        backgroundScope,
                    ) { it.movedBy(offset, 4, 0) }
                        .assertAppliedGesture<ImeWindowSpec.Floating> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.keyboardWidth.shouldMatch(before.keyboardWidth, SAME, tolerance, "keyboard width")
                            after.offsetLeft.shouldMatch(before.offsetLeft, NOT_GREATER, tolerance, "offset left")
                            after.offsetBottom.shouldMatch(before.offsetBottom, SAME, tolerance, "offset bottom")
                            withClue("move operations leftward must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }

            test("for all rightward moves") {
                checkAll(
                    Arb.rootInsetsWithRightwardOffset(),
                    Arb.enum<ImeWindowMode.Floating>(),
                ) { (rootInsets, offset), floatingMode ->
                    runEditorGesture(
                        rootInsets,
                        ImeWindowConfig(ImeWindowMode.FLOATING, floatingMode = floatingMode),
                        backgroundScope,
                    ) { it.movedBy(offset, 4, 0) }
                        .assertAppliedGesture<ImeWindowSpec.Floating> { beforeSpec, afterSpec ->
                            val before = beforeSpec.props
                            val after = afterSpec.props
                            after.keyboardHeight.shouldMatch(before.keyboardHeight, SAME, tolerance, "keyboard height")
                            after.keyboardWidth.shouldMatch(before.keyboardWidth, SAME, tolerance, "keyboard width")
                            after.offsetLeft.shouldMatch(before.offsetLeft, NOT_LESS, tolerance, "offset left")
                            after.offsetBottom.shouldMatch(before.offsetBottom, SAME, tolerance, "offset bottom")
                            withClue("move operations rightward must not push window out of root bounds") {
                                after.shouldBeConstrainedTo(afterSpec.constraints, tolerance)
                            }
                        }
                }
            }
        }
    })
